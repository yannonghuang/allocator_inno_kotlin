package com.allocator.api

import com.allocator.Cases
import com.allocator.PlanRuns
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * Resolves (caseId, planRunId) when one or both are absent.
 *
 *   - If `caseIdHint` is null: pick the latest case row with active=true. If
 *     none are active, fall back to the latest case overall by id. Returns
 *     null only if there are no cases at all.
 *   - If `planRunIdHint` is null: pick the latest run for the resolved case
 *     with `active=true AND status="success"`. If none qualify, fall back to
 *     the latest `status="success"` run overall. Transient runs (`contingent`,
 *     `running`, `failed`, `ready`) are skipped — they aren't loadable as
 *     baselines for impact analysis. Callers that genuinely want a non-success
 *     run must pass `planRunId` explicitly (the explicit-id path validates
 *     existence without status filtering).
 */
private val log = LoggerFactory.getLogger("com.allocator.ResolveRoutes")

@Serializable
data class ResolvedIds(
    val caseId: Int,
    val planRunId: Int,
    /** "explicit" | "active" | "fallback-latest" */
    val caseSource: String,
    /** "explicit" | "active" | "fallback-latest" */
    val runSource: String,
)

@Serializable
data class ResolveError(val error: String, val reason: String)

@Serializable
data class SetActiveRequest(val active: Boolean)

internal data class CaseLookup(val caseId: Int, val source: String)
internal data class RunLookup(val runId: Int, val source: String)

internal fun pickActiveCase(): CaseLookup? = transaction {
    Cases.selectAll().where { Cases.active eq true }
        .orderBy(Cases.id, SortOrder.DESC)
        .firstOrNull()
        ?.get(Cases.id)
        ?.let { return@transaction CaseLookup(it, "active") }
    Cases.selectAll()
        .orderBy(Cases.id, SortOrder.DESC)
        .firstOrNull()
        ?.get(Cases.id)
        ?.let { return@transaction CaseLookup(it, "fallback-latest") }
    null
}

internal fun pickActiveRun(caseId: Int): RunLookup? = transaction {
    // The resolver only ever returns runs with status="success" — those are the
    // baselines downstream tools can load. Transient runs (contingent / running
    // / failed / ready) are skipped even if marked active=true; if a caller
    // really wants them they must pass planRunId explicitly.
    PlanRuns.selectAll()
        .where {
            (PlanRuns.caseId eq caseId) and
                (PlanRuns.active eq true) and
                (PlanRuns.status eq "success")
        }
        .orderBy(PlanRuns.id, SortOrder.DESC)
        .firstOrNull()
        ?.get(PlanRuns.id)
        ?.let { return@transaction RunLookup(it, "active") }
    PlanRuns.selectAll()
        .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
        .orderBy(PlanRuns.id, SortOrder.DESC)
        .firstOrNull()
        ?.get(PlanRuns.id)
        ?.let { return@transaction RunLookup(it, "fallback-latest") }
    null
}

internal sealed class ResolveResult {
    data class Ok(val ids: ResolvedIds) : ResolveResult()
    data class Err(val status: HttpStatusCode, val error: String, val reason: String) : ResolveResult()
}

internal fun resolveActiveCaseAndRun(caseIdHint: Int?, planRunIdHint: Int?): ResolveResult {
    val (resolvedCaseId, caseSource) = if (caseIdHint != null) {
        val exists = transaction {
            Cases.selectAll().where { Cases.id eq caseIdHint }.firstOrNull() != null
        }
        if (!exists) {
            return ResolveResult.Err(HttpStatusCode.NotFound, "case_not_found", "case_id=$caseIdHint")
        }
        caseIdHint to "explicit"
    } else {
        val pick = pickActiveCase()
            ?: return ResolveResult.Err(HttpStatusCode.NotFound, "no_cases", "database has no cases")
        pick.caseId to pick.source
    }

    val (resolvedRunId, runSource) = if (planRunIdHint != null) {
        val exists = transaction {
            PlanRuns.selectAll()
                .where { (PlanRuns.id eq planRunIdHint) and (PlanRuns.caseId eq resolvedCaseId) }
                .firstOrNull() != null
        }
        if (!exists) {
            return ResolveResult.Err(
                HttpStatusCode.NotFound,
                "run_not_found",
                "plan_run_id=$planRunIdHint not found in case_id=$resolvedCaseId",
            )
        }
        planRunIdHint to "explicit"
    } else {
        val pick = pickActiveRun(resolvedCaseId)
            ?: return ResolveResult.Err(
                HttpStatusCode.NotFound,
                "no_runs",
                "case_id=$resolvedCaseId has no plan runs with status=\"success\" — run a plan first, or pass planRunId explicitly",
            )
        pick.runId to pick.source
    }

    return ResolveResult.Ok(
        ResolvedIds(
            caseId = resolvedCaseId,
            planRunId = resolvedRunId,
            caseSource = caseSource,
            runSource = runSource,
        )
    )
}

fun Routing.resolveRoutes() {

    // GET /resolve?caseId=N&planRunId=M  (both query params optional)
    get("/resolve") {
        val caseId = call.request.queryParameters["caseId"]?.toIntOrNull()
        val planRunId = call.request.queryParameters["planRunId"]?.toIntOrNull()
        log.info("resolve(caseId={}, planRunId={})", caseId, planRunId)
        when (val r = resolveActiveCaseAndRun(caseId, planRunId)) {
            is ResolveResult.Ok -> call.respond(r.ids)
            is ResolveResult.Err -> call.respond(r.status, ResolveError(r.error, r.reason))
        }
    }

    // POST /cases/{case_id}/active   { "active": true|false }
    post("/cases/{case_id}/active") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val body = Json.decodeFromString<SetActiveRequest>(call.receiveText().ifBlank { "{\"active\":true}" })
        val updated = transaction {
            Cases.update({ Cases.id eq caseId }) { it[Cases.active] = body.active }
        }
        if (updated == 0) {
            call.respond(HttpStatusCode.NotFound, ResolveError("case_not_found", "case_id=$caseId"))
            return@post
        }
        log.info("set case {} active={}", caseId, body.active)
        call.respond(buildJsonObject {
            put("caseId", caseId)
            put("active", body.active)
        })
    }

    // POST /cases/{case_id}/plan-runs/{run_id}/active   { "active": true|false }
    post("/cases/{case_id}/plan-runs/{run_id}/active") {
        val caseId = call.parameters["case_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid case_id")
        val runId = call.parameters["run_id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid run_id")
        val body = Json.decodeFromString<SetActiveRequest>(call.receiveText().ifBlank { "{\"active\":true}" })
        val updated = transaction {
            PlanRuns.update({ (PlanRuns.id eq runId) and (PlanRuns.caseId eq caseId) }) {
                it[PlanRuns.active] = body.active
            }
        }
        if (updated == 0) {
            call.respond(
                HttpStatusCode.NotFound,
                ResolveError("run_not_found", "plan_run_id=$runId not found in case_id=$caseId"),
            )
            return@post
        }
        log.info("set plan_run {} (case {}) active={}", runId, caseId, body.active)
        call.respond(buildJsonObject {
            put("caseId", caseId)
            put("planRunId", runId)
            put("active", body.active)
        })
    }
}
