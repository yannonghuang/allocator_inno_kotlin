package com.allocator.services

import com.allocator.KbRecords
import com.allocator.PlanRuns
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * One-shot data migration: rename `consolidation.engine` → `consolidation.scope`
 * and translate values `leaf-legacy`→`leaf-only`, `supply`→`all` in every
 * persisted PlanningConfig + bootstrap-metadata field.
 *
 * Touches:
 *   - plan_run.config        (JSON text)  : consolidation.engine → consolidation.scope
 *   - plan_run.metadata      (JSON text)  : primary_axis "engine" → "scope"
 *   - kb_record.config       (JSON text)  : consolidation.engine → consolidation.scope
 *   - kb_record.primary_axis (varchar)    : "engine" → "scope"
 *   - kb_record.preset_id    (varchar)    : "engine=supply"→"scope=all", "engine=leaf-legacy"→"scope=leaf-only"
 *   - kb_record.signature    (varchar)    : recomputed from new config (key "eng=" → "scope=")
 *
 * Idempotent — each rewrite is a no-op when the row already uses the new form.
 * Runs at startup after schema ready. Logged counts are zero on subsequent
 * boots once all rows have been migrated.
 */
object ScopeRenameMigration {

    private val log = LoggerFactory.getLogger("com.allocator.ScopeRenameMigration")
    private val json = Json { ignoreUnknownKeys = true }

    /** Translate the engine value to the scope value. Returns the input unchanged for unknown strings. */
    private fun translateValue(v: String): String = when (v) {
        "leaf-legacy" -> "leaf-only"
        "supply"      -> "all"
        else          -> v
    }

    /** Rewrite a single PlanningConfig JsonObject. Returns null if no changes were needed. */
    internal fun rewriteConfig(root: JsonObject): JsonObject? {
        val cs = (root["consolidation"] as? JsonObject) ?: return null
        // Already migrated? (`scope` present and `engine` absent)
        val hasEngine = cs.containsKey("engine")
        val hasScope = cs.containsKey("scope")
        if (!hasEngine && hasScope) return null
        if (!hasEngine && !hasScope) return null   // neither key — leave alone

        val engineEl = cs["engine"]
        val engineRaw = (engineEl as? JsonPrimitive)?.contentOrNull
        val newScopeValue = engineRaw?.let { translateValue(it) }

        val newCs = buildJsonObject {
            for ((k, v) in cs) {
                if (k == "engine") continue   // drop old key
                put(k, v)
            }
            // Insert / overwrite scope. If scope is already present and engine is also present,
            // engine's translated value wins (engine is the historical source of truth).
            if (newScopeValue != null) put("scope", JsonPrimitive(newScopeValue))
            else if (!hasScope) put("scope", JsonPrimitive("leaf-only"))   // engine null → default
        }

        val newRoot = buildJsonObject {
            for ((k, v) in root) {
                if (k == "consolidation") put("consolidation", newCs)
                else put(k, v)
            }
        }
        return newRoot
    }

    /** Rewrite plan_run.metadata JSON if it tags the run as bootstrap with primary_axis=engine. */
    internal fun rewriteMetadata(root: JsonObject): JsonObject? {
        val pa = (root["primary_axis"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (pa != "engine") return null
        return buildJsonObject {
            for ((k, v) in root) {
                if (k == "primary_axis") put("primary_axis", JsonPrimitive("scope"))
                else put(k, v)
            }
        }
    }

    /** preset_id translation. Idempotent. */
    internal fun translatePresetId(presetId: String?): String? = when (presetId) {
        null               -> null
        "engine=supply"      -> "scope=all"
        "engine=leaf-legacy" -> "scope=leaf-only"
        else                 -> presetId
    }

    /** primary_axis column translation. Idempotent. */
    internal fun translatePrimaryAxis(axis: String?): String? = when (axis) {
        null     -> null
        "engine" -> "scope"
        else     -> axis
    }

    /**
     * Rewrite a stored signature string literally — `eng=leaf-legacy` →
     * `scope=leaf-only`, `eng=supply` → `scope=all`. This preserves the
     * existing inter-row distinctness exactly. Signatures from before this
     * migration may use different numeric-formatting conventions across
     * rows (`0.0` vs `0` in weight tuples), and recomputing them with the
     * current canonical formatter would collapse pre-existing distinct rows
     * into duplicates and violate `uq_kb_record_case_signature`. String
     * substitution sidesteps that.
     */
    internal fun translateSignature(sig: String): String? {
        if (!sig.contains("eng=")) return null
        return sig
            .replace("eng=leaf-legacy", "scope=leaf-only")
            .replace("eng=supply", "scope=all")
    }

    fun run() = transaction {
        var planRunsUpdated = 0
        var kbRecordsUpdated = 0

        // ── plan_run ──
        PlanRuns.selectAll().forEach { row ->
            val id = row[PlanRuns.id]
            val configRaw = row[PlanRuns.config]
            val metaRaw = row[PlanRuns.metadata]

            val newConfig = configRaw?.let { raw ->
                runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?.let { rewriteConfig(it) }
            }
            val newMeta = metaRaw?.let { raw ->
                runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?.let { rewriteMetadata(it) }
            }
            if (newConfig == null && newMeta == null) return@forEach
            PlanRuns.update({ PlanRuns.id eq id }) {
                if (newConfig != null) it[PlanRuns.config] = newConfig.toString()
                if (newMeta != null)   it[PlanRuns.metadata] = newMeta.toString()
            }
            planRunsUpdated++
        }

        // ── kb_record ──
        KbRecords.selectAll().forEach { row ->
            val id = row[KbRecords.id]
            val configRaw = row[KbRecords.config]
            val oldPresetId = row[KbRecords.presetId]
            val oldPrimaryAxis = row[KbRecords.primaryAxis]
            val oldSignature = row[KbRecords.signature]

            val parsedConfig = runCatching { json.parseToJsonElement(configRaw).jsonObject }.getOrNull()
            val newConfig = parsedConfig?.let { rewriteConfig(it) }
            val newPresetId = translatePresetId(oldPresetId)
            val newPrimaryAxis = translatePrimaryAxis(oldPrimaryAxis)
            // Translate the signature LITERALLY — preserve existing row-level
            // distinctness. See translateSignature() for why we don't recompute.
            val newSignature = translateSignature(oldSignature)

            val anyChange = newConfig != null ||
                newPresetId != oldPresetId ||
                newPrimaryAxis != oldPrimaryAxis ||
                newSignature != null
            if (!anyChange) return@forEach

            KbRecords.update({ KbRecords.id eq id }) {
                if (newConfig != null)   it[KbRecords.config] = newConfig.toString()
                if (newPresetId != oldPresetId) it[KbRecords.presetId] = newPresetId
                if (newPrimaryAxis != oldPrimaryAxis) it[KbRecords.primaryAxis] = newPrimaryAxis
                if (newSignature != null) it[KbRecords.signature] = newSignature
                it[KbRecords.updatedAt] = Clock.System.now()
            }
            kbRecordsUpdated++
        }

        if (planRunsUpdated > 0 || kbRecordsUpdated > 0) {
            log.info(
                "ScopeRenameMigration: rewrote {} plan_run row(s) and {} kb_record row(s).",
                planRunsUpdated, kbRecordsUpdated,
            )
        } else {
            log.info("ScopeRenameMigration: nothing to migrate (already on scope=… form).")
        }
    }
}
