package com.allocator.services

import com.allocator.Cases
import com.allocator.PlanRunEvents
import com.allocator.PlanRuns
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Pure resolver for a case's "active" plan run id.
 *
 *  - If the user designated an active run, use that (when it's still a valid success id).
 *  - Otherwise fall back to the most recent successful run.
 *
 * Inputs are plain ids so this is trivially unit-testable without a database.
 * [successRunIds] must be in any order; the resolver picks the maximum.
 */
fun resolveActiveRunId(designatedId: Int?, successRunIds: Collection<Int>): Int? {
    if (designatedId != null && designatedId in successRunIds) return designatedId
    return successRunIds.maxOrNull()
}

/**
 * Makes [planRunId] — just saved or promoted — the case's active plan run: a newer plan
 * takes over from any manual "Set active" pin. When it's already the latest successful
 * run the pin is simply cleared, so active follows the default again; otherwise (a
 * promoted what-if older than a plan saved in the meantime) it's pinned, so the plan just
 * adopted still wins. Call inside a transaction, after the run's status became "success".
 */
fun adoptAsActivePlanRun(caseId: Int, planRunId: Int) {
    val latestSuccess = PlanRuns.select(PlanRuns.id)
        .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
        .maxOfOrNull { it[PlanRuns.id] }
    Cases.update({ Cases.id eq caseId }) {
        it[Cases.designatedActivePlanRunId] = if (planRunId == latestSuccess) null else planRunId
    }
}

/**
 * Pure resolver for a case's "initial" plan run id — the earliest persisted run
 * whose status is [success] or [contingent]. Failed runs don't count; the
 * initial represents the baseline the user started from.
 */
fun resolveInitialRunId(candidates: Collection<Pair<Int, String>>): Int? =
    candidates.asSequence()
        .filter { (_, status) -> status == "success" || status == "contingent" }
        .minByOrNull { (id, _) -> id }
        ?.first

/**
 * Insert a lifecycle event for a plan run. Callers must already be inside a
 * transaction (all existing PlanRuns mutation sites are). Payload is optional
 * and kind-specific; keep it small.
 */
fun emitPlanRunEvent(
    caseId: Int,
    planRunId: Int,
    kind: String,
    payload: JsonElement? = null,
) {
    PlanRunEvents.insert {
        it[PlanRunEvents.caseId] = caseId
        it[PlanRunEvents.planRunId] = planRunId
        it[PlanRunEvents.kind] = kind
        it[PlanRunEvents.payload] = payload?.toString()
    }
}

/** Convenience: emit an event in its own transaction (use only from non-transactional call sites). */
fun emitPlanRunEventTx(caseId: Int, planRunId: Int, kind: String, payload: JsonElement? = null) {
    transaction { emitPlanRunEvent(caseId, planRunId, kind, payload) }
}

/** Helper to build a two-field rename payload without ceremony. */
fun renamePayload(oldValue: String?, newValue: String?, field: String = "name"): JsonObject = buildJsonObject {
    val oldKey = "old_$field"
    val newKey = "new_$field"
    // kotlinx.serialization JsonObject builder requires explicit null handling via put(_, JsonNull)
    oldValue?.let { put(oldKey, kotlinx.serialization.json.JsonPrimitive(it)) }
        ?: put(oldKey, kotlinx.serialization.json.JsonNull)
    newValue?.let { put(newKey, kotlinx.serialization.json.JsonPrimitive(it)) }
        ?: put(newKey, kotlinx.serialization.json.JsonNull)
}
