package com.allocator.services

import com.allocator.KbRecords
import com.allocator.PlanRuns
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * One-shot data migration: retire two config knobs whose runtime support was
 * removed in 2026-05.
 *
 *   1. `method_selection.depth_optimal` (bool, default false) — the
 *      optimal-depth iterative search runtime was deleted; the field is
 *      ignored by the parser. Stripped from persisted configs so future
 *      signatures don't carry the dead key.
 *
 *   2. `consolidation.scope` ("leaf-only"|"all", default "leaf-only") — the
 *      supply-level orchestrator (scope=all) was retired; leaf-only is now
 *      the only path. Old `scope=all` rows are silently coerced to leaf-only
 *      on parse, so config-level removal is purely cosmetic but it keeps
 *      signatures comparable.
 *
 * The migration ALSO recomputes signatures — without it, KB dedup breaks:
 * the new signatureFor() format ("m=...|max=...|d=...|bom=3|w=...|alloc=...|
 * cons=...|p=...|purch=...") drops the `dopt=`/`scope=` segments that old
 * signatures carry, so a re-saved equivalent run would produce a fresh row
 * instead of matching its KB ancestor. Recomputing the stored signature on
 * old rows aligns them with new ones for the comparison/dedup paths used by
 * KbStore.upsertFromPlanRun, query_kb_runs, find_paired_runs, and
 * is_signature_in_kb.
 *
 * Touches:
 *   - plan_run.config        (JSON text)  : drop `method_selection.depth_optimal`
 *                                            and `consolidation.scope`
 *   - kb_record.config       (JSON text)  : same scrub
 *   - kb_record.signature    (varchar)    : recomputed from scrubbed config
 *
 * Idempotent — each rewrite is a no-op when the row already lacks both keys.
 * Run at startup AFTER ScopeRenameMigration so the engine→scope rename has
 * already converged the data (we only delete `scope`, not `engine`).
 *
 * ## Dedup post-migration
 *
 * After signature recomputation, two distinct kb_record rows may share the
 * same signature (e.g. one had scope=leaf-only, another scope=all — both
 * collapse to the new format because scope is gone). The `kb_record` table
 * has a UNIQUE(case_id, signature) constraint, so we MUST resolve duplicates
 * before writing the recomputed signature.
 *
 * Resolution: keep the row with the larger `id` (most recent), delete the
 * loser. Both rows already represent the same logical config under the new
 * runtime (scope=all is gone), so they're semantically equivalent — the
 * loser carries no information the winner lacks.
 */
object ConfigRetirementMigration {

    private val log = LoggerFactory.getLogger("com.allocator.ConfigRetirementMigration")
    private val json = Json { ignoreUnknownKeys = true }

    /** Strip `method_selection.depth_optimal` and `consolidation.scope` from
     *  a PlanningConfig JsonObject. Returns null when no changes are needed. */
    internal fun scrubConfig(root: JsonObject): JsonObject? {
        val ms = root["method_selection"] as? JsonObject
        val cs = root["consolidation"] as? JsonObject
        val msHasDopt = ms?.containsKey("depth_optimal") == true
        val csHasScope = cs?.containsKey("scope") == true
        if (!msHasDopt && !csHasScope) return null

        val newMs = if (msHasDopt) buildJsonObject {
            for ((k, v) in ms!!) if (k != "depth_optimal") put(k, v)
        } else ms

        val newCs = if (csHasScope) buildJsonObject {
            for ((k, v) in cs!!) if (k != "scope") put(k, v)
        } else cs

        return buildJsonObject {
            for ((k, v) in root) {
                when (k) {
                    "method_selection" -> if (newMs != null) put(k, newMs) else put(k, v)
                    "consolidation" -> if (newCs != null) put(k, newCs) else put(k, v)
                    else -> put(k, v)
                }
            }
        }
    }

    /** Old-format signature → new-format signature, computed by re-running
     *  CaseBootstrap.signatureFor on the (already-scrubbed) config object.
     *  Returns null when the parse failed. */
    internal fun recomputeSignature(scrubbedConfig: JsonObject): String? =
        runCatching { CaseBootstrap.signatureFor(scrubbedConfig) }.getOrNull()

    fun run() = transaction {
        var planRunsUpdated = 0
        var kbRecordsScrubbed = 0
        var kbDuplicatesDropped = 0
        var kbSignaturesRewritten = 0

        // ── plan_run.config: scrub only (signatures aren't stored on plan_run; metadata.signature
        //    is recomputed lazily by KbStore.upsertFromPlanRun via signatureFor on the parsed config). ──
        PlanRuns.selectAll().forEach { row ->
            val id = row[PlanRuns.id]
            val configRaw = row[PlanRuns.config] ?: return@forEach
            val parsed = runCatching { json.parseToJsonElement(configRaw).jsonObject }.getOrNull() ?: return@forEach
            val scrubbed = scrubConfig(parsed) ?: return@forEach
            PlanRuns.update({ PlanRuns.id eq id }) {
                it[PlanRuns.config] = scrubbed.toString()
            }
            planRunsUpdated++
        }

        // ── kb_record: plan changes per row, then resolve duplicates, then commit. ──
        data class Plan(
            val id: Int,
            val caseId: Int,
            val newConfigJson: String?,
            val newSignature: String,
            val oldSignature: String,
        )

        val plans = mutableListOf<Plan>()
        val rowSignatures = mutableListOf<Triple<Int, Int, String>>()  // (id, caseId, signature) — every row

        KbRecords.selectAll().forEach { row ->
            val id = row[KbRecords.id]
            val caseId = row[KbRecords.caseId]
            val configRaw = row[KbRecords.config]
            val oldSig = row[KbRecords.signature]
            rowSignatures += Triple(id, caseId, oldSig)
            val parsed = runCatching { json.parseToJsonElement(configRaw).jsonObject }.getOrNull() ?: return@forEach
            val scrubbed = scrubConfig(parsed)
            val effective = scrubbed ?: parsed
            val newSig = recomputeSignature(effective) ?: return@forEach
            if (scrubbed == null && newSig == oldSig) return@forEach   // already migrated
            plans += Plan(id, caseId, scrubbed?.toString(), newSig, oldSig)
        }

        // Resolve (caseId, newSignature) collisions among the plan set.
        val planGroups = plans.groupBy { it.caseId to it.newSignature }
        val collisionDropIds = mutableSetOf<Int>()
        val collisionKeepIds = mutableSetOf<Int>()
        for ((_, group) in planGroups) {
            val sorted = group.sortedByDescending { it.id }
            collisionKeepIds += sorted.first().id
            sorted.drop(1).forEach { collisionDropIds += it.id }
        }

        // Also drop any plan whose newSignature collides with a row that
        // wasn't in the plan set (i.e. already canonical). Keep the canonical row.
        val canonicalSignatures: Set<Pair<Int, String>> = rowSignatures
            .filter { (id, _, _) -> id !in collisionKeepIds && id !in collisionDropIds }
            .map { (_, caseId, sig) -> caseId to sig }
            .toSet()
        val finalKeepIds = mutableSetOf<Int>()
        val finalDropIds = collisionDropIds.toMutableSet()
        for (id in collisionKeepIds) {
            val plan = plans.first { it.id == id }
            val key = plan.caseId to plan.newSignature
            if (key in canonicalSignatures) finalDropIds += id else finalKeepIds += id
        }

        // Phase 3: drop losers first (avoids violating uq_kb_record_case_signature
        // when the kept-row update lands), then update keepers.
        for (id in finalDropIds) {
            KbRecords.deleteWhere { KbRecords.id eq id }
            kbDuplicatesDropped++
        }
        for (id in finalKeepIds) {
            val plan = plans.first { it.id == id }
            KbRecords.update({ KbRecords.id eq id }) {
                if (plan.newConfigJson != null) {
                    it[KbRecords.config] = plan.newConfigJson
                    kbRecordsScrubbed++
                }
                if (plan.newSignature != plan.oldSignature) {
                    it[KbRecords.signature] = plan.newSignature
                    kbSignaturesRewritten++
                }
                it[KbRecords.updatedAt] = Clock.System.now()
            }
        }

        if (planRunsUpdated > 0 || kbRecordsScrubbed > 0 || kbSignaturesRewritten > 0 || kbDuplicatesDropped > 0) {
            log.info("ConfigRetirementMigration: plan_runs scrubbed={}, kb configs scrubbed={}, " +
                "kb signatures rewritten={}, kb duplicates dropped={}",
                planRunsUpdated, kbRecordsScrubbed, kbSignaturesRewritten, kbDuplicatesDropped)
        }
    }
}
