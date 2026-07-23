package com.allocator.services

import com.allocator.CaseAllocationConfigs
import com.allocator.CaseConstraintConfigs
import com.allocator.CaseDemandOrderConfigs
import com.allocator.CasePreferenceConfigs
import com.allocator.CasePurchasableMaterialConfigs
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest

/**
 * Shared canonicalization/hashing for the small "content_hash" columns on
 * [com.allocator.CaseAllocationConfigs] / [com.allocator.CasePreferenceConfigs] /
 * [com.allocator.CaseDemandOrderConfigs]. Each hash is a cheap, incrementally-maintained
 * stand-in for "does this case's current allocation/preference/demand-order row set differ
 * from before" — read by the KB signature's fingerprint injection (see
 * [CaseBootstrap.signatureFor] and `Allocate.kt`'s `resolveEffectiveConfig`) instead of
 * re-hashing potentially thousands of rows on every plan submission.
 *
 * Not a security hash — collision resistance only needs to be "good enough to dedup KB
 * signatures," not cryptographic. Truncated to 16 hex chars (64 bits) to keep the eventual
 * signature string compact (`kb_record.signature` is `varchar(512)`).
 */
internal object KbFingerprint {

    /** Canonical hash of a row set, given each row already rendered as one delimited string
     *  (caller is responsible for consistent field order/formatting so identical logical
     *  content always produces the same string). Sorts rows so hash is insertion-order-
     *  independent. Returns null for an empty row set — distinguishes "no rows for this case"
     *  from "rows exist" at the type level, matching how absence is handled everywhere else
     *  in this fingerprint scheme (see signatureFor's "none" vs a real hash). */
    fun hashRows(rows: List<String>): String? {
        if (rows.isEmpty()) return null
        val canonical = rows.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** The raw (pre-formatted) segment values embedded into `plan_run.config`'s
     *  `_kb_fingerprint` object at submission time, and later read back verbatim by
     *  [CaseBootstrap.signatureFor] — see its own doc for the full "why" (point-in-time
     *  correctness: a signature must reflect what was true when a run actually executed, not
     *  what's live in these tables NOW). [purchmat]/[constr] are always real (hash or "none")
     *  on both submission paths — unlike [casealloc]/[pref]/[ord], purchasable-materials and
     *  constraints are real business/data constraints that bootstrap presets also respect (see
     *  this class's own doc on [buildFingerprint]'s `consultsOverrideTables` for why those
     *  three differ). */
    data class Segments(
        val casealloc: String,
        val pref: String,
        val ord: String,
        val purchmat: String,
        val constr: String,
    )

    /**
     * Build the fingerprint segments for [caseId] to embed in a NEWLY SUBMITTED run's config.
     *
     * [consultsOverrideTables] gates ONLY `casealloc`/`pref`/`ord` — it must be `false` for the
     * bootstrap-preset submission path (`runOneBootstrapPreset`) — bootstrap runs `runPlanning`
     * with allocation/preference/demand-order overrides left `null` regardless of what's in
     * those tables (confirmed via `PlanningEngine.kt`'s `runPlanning` defaults), so embedding a
     * real, live-state-dependent hash there would make two byte-identical bootstrap presets, run
     * weeks apart, collide onto DIFFERENT KB signatures for a distinction that never affected
     * their actual output — false novelty, the mirror-image bug of the one this fingerprint
     * exists to fix. Those three segments become the fixed sentinel `"na"` in that case,
     * regardless of live table state.
     *
     * `purchmat`/`constr` are NOT gated by this flag — `Allocate.kt`'s `resolveEffectiveConfig`
     * reads purchasable-materials/constraints from their own tables unconditionally on both
     * submission paths (bootstrap presets respect real business constraints too), so these two
     * segments are always computed live regardless of [consultsOverrideTables].
     *
     * `"none"` (distinct from `"na"`) means: this run DOES consult the table, and the table is
     * genuinely empty for this case — a real, meaningful value, not "not applicable."
     */
    fun buildFingerprint(caseId: Int, consultsOverrideTables: Boolean): Segments = transaction {
        val purchMatHash = CasePurchasableMaterialConfigs.selectAll()
            .where { CasePurchasableMaterialConfigs.caseId eq caseId }
            .singleOrNull()?.get(CasePurchasableMaterialConfigs.contentHash)
        val constrHash = CaseConstraintConfigs.selectAll()
            .where { CaseConstraintConfigs.caseId eq caseId }
            .singleOrNull()?.get(CaseConstraintConfigs.contentHash)
        if (!consultsOverrideTables) {
            return@transaction Segments("na", "na", "na", purchMatHash ?: "none", constrHash ?: "none")
        }
        val allocHash = CaseAllocationConfigs.selectAll().where { CaseAllocationConfigs.caseId eq caseId }
            .singleOrNull()?.get(CaseAllocationConfigs.contentHash)
        val prefCfg = CasePreferenceConfigs.selectAll().where { CasePreferenceConfigs.caseId eq caseId }
            .singleOrNull()
        val ordHash = CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.caseId eq caseId }
            .singleOrNull()?.get(CaseDemandOrderConfigs.contentHash)
        val prefSegment = if (prefCfg == null) "none" else {
            val depth = prefCfg[CasePreferenceConfigs.maxBomDepth]
            val wD = prefCfg[CasePreferenceConfigs.deliveryWeight]
            val wI = prefCfg[CasePreferenceConfigs.inventoryWeight]
            val wC = prefCfg[CasePreferenceConfigs.criticalMaterialWeight]
            val hash = prefCfg[CasePreferenceConfigs.contentHash] ?: "none"
            "$depth,$wD,$wI,$wC,$hash"
        }
        Segments(
            casealloc = allocHash ?: "none",
            pref = prefSegment,
            ord = ordHash ?: "none",
            purchmat = purchMatHash ?: "none",
            constr = constrHash ?: "none",
        )
    }
}
