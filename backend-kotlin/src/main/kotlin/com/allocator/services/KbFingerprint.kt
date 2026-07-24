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
     *  what's live in these tables NOW). Every run — manual Plan Run submission or KB-seeding —
     *  consults all 5 external config objects (see `runOneBootstrapPreset`'s own doc), so all 5
     *  segments are always real (a hash, or `"none"` for a genuinely empty resolved version). */
    data class Segments(
        val casealloc: String,
        val pref: String,
        val ord: String,
        val purchmat: String,
        val constr: String,
    )

    /**
     * Build the fingerprint segments to embed in a NEWLY SUBMITTED run's config, given the
     * already-resolved (explicit-or-default — see [com.allocator.services.CaseConfigVersioning
     * .resolveVersionId]) version id for each of the 5 external config objects.
     *
     * `"none"` means: the resolved version is genuinely empty — a real, meaningful value, not
     * "not applicable."
     */
    fun buildFingerprint(
        caseAllocVersionId: Int,
        prefVersionId: Int,
        ordVersionId: Int,
        purchMatVersionId: Int,
        constrVersionId: Int,
    ): Segments = transaction {
        val purchMatHash = CasePurchasableMaterialConfigs.selectAll()
            .where { CasePurchasableMaterialConfigs.versionId eq purchMatVersionId }
            .singleOrNull()?.get(CasePurchasableMaterialConfigs.contentHash)
        val constrHash = CaseConstraintConfigs.selectAll()
            .where { CaseConstraintConfigs.versionId eq constrVersionId }
            .singleOrNull()?.get(CaseConstraintConfigs.contentHash)
        val allocHash = CaseAllocationConfigs.selectAll().where { CaseAllocationConfigs.versionId eq caseAllocVersionId }
            .singleOrNull()?.get(CaseAllocationConfigs.contentHash)
        val prefCfg = CasePreferenceConfigs.selectAll().where { CasePreferenceConfigs.versionId eq prefVersionId }
            .singleOrNull()
        val ordHash = CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.versionId eq ordVersionId }
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
