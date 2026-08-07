package com.allocator.services

import com.allocator.CaseConfigVersions
import com.allocator.KbRecords
import com.allocator.PlanRuns
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Versioning for the 5 "external config objects" — see [CaseConfigVersions]' own doc in
 * Tables.kt for the overall shape. This file is the single place that knows how to resolve,
 * list, create, rename, and delete versions, and how to check whether a version is "referenced"
 * (used by some plan_run or kb_record) — the guard that decides whether an in-place
 * Save/Generate/Import/Clear/Delete is allowed, or whether the caller must "Save As" a new
 * version instead.
 *
 * No "default version" concept: [resolveVersionId] never falls back to anything when [requested]
 * is null — that's what "no explicit pick" IS, everywhere, always ("`-`" in the UI). See
 * [CaseConfigVersions]' own doc for why that replaced the old is_default-based fallback.
 */
enum class ConfigVersionKind(val key: String) {
    CASEALLOC("casealloc"),
    PREF("pref"),
    ORD("ord"),
    PURCHMAT("purchmat"),
    CONSTR("constr"),
}

data class VersionSummary(
    val id: Int,
    val name: String?,
    val comments: String?,
    val createdAt: String,
    val updatedAt: String,
    val referenced: Boolean,
)

object CaseConfigVersioning {

    private fun planRunColumn(kind: ConfigVersionKind): Column<Int?> = when (kind) {
        ConfigVersionKind.CASEALLOC -> PlanRuns.caseAllocVersionId
        ConfigVersionKind.PREF      -> PlanRuns.prefVersionId
        ConfigVersionKind.ORD       -> PlanRuns.ordVersionId
        ConfigVersionKind.PURCHMAT  -> PlanRuns.purchMatVersionId
        ConfigVersionKind.CONSTR    -> PlanRuns.constrVersionId
    }

    private fun kbRecordColumn(kind: ConfigVersionKind): Column<Int?> = when (kind) {
        ConfigVersionKind.CASEALLOC -> KbRecords.caseAllocVersionId
        ConfigVersionKind.PREF      -> KbRecords.prefVersionId
        ConfigVersionKind.ORD       -> KbRecords.ordVersionId
        ConfigVersionKind.PURCHMAT  -> KbRecords.purchMatVersionId
        ConfigVersionKind.CONSTR    -> KbRecords.constrVersionId
    }

    /** [requested] if it's a real version belonging to this case+kind, else **null** — never a
     *  fallback lookup of any kind. Null means "no override for this kind," full stop, whether
     *  because the case has never touched this object or because the caller explicitly chose
     *  "`-`". Use for GET/read routes and for planning-time resolution (resolveEffectiveConfig/
     *  CaseBootstrap) alike — there is no different behavior between them anymore. */
    fun resolveVersionId(caseId: Int, kind: ConfigVersionKind, requested: Int?): Int? = transaction {
        if (requested == null) return@transaction null
        val ok = CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.id eq requested) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .any()
        if (ok) requested else null
    }

    /** [requested] if valid, else reuses the case+kind's most recently created version if one
     *  exists, else creates a fresh unnamed one. The ONE place a version gets materialized on
     *  demand: any caller about to WRITE data against it (Save / Generate / Import) — never call
     *  this from a GET/read/planning path. Reusing the latest existing version (rather than
     *  always creating new) means repeated no-pick writes refresh the same cache slot instead of
     *  accumulating a version every call. */
    fun resolveOrCreateVersionId(caseId: Int, kind: ConfigVersionKind, requested: Int?): Int = transaction {
        resolveVersionId(caseId, kind, requested)?.let { return@transaction it }
        CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .orderBy(CaseConfigVersions.id, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(1)
            .singleOrNull()?.get(CaseConfigVersions.id)
            ?: createVersion(caseId, kind, name = null, comments = null)
    }

    /** Whether [versionId] is used by any COMPLETED plan_run or kb_record for [kind] — the gate
     *  for Save/Generate/Import/Clear/Delete having to become "Save As" instead, and for Delete
     *  being blocked outright.
     *
     *  Only "success"/"contingent" plan_runs count — NOT "running" or "failed". A genuinely
     *  completed run (success, or contingent — a negotiation-branch result pending promotion)
     *  must lock its version: mutating the config in place would retroactively change what that
     *  historical run says it used. kb_records don't need the same filter — a KB record is only
     *  ever created for an already-successful, sound run (see KbStore's own doc). */
    fun isVersionReferenced(versionId: Int, kind: ConfigVersionKind): Boolean = transaction {
        val inPlanRuns = PlanRuns.selectAll()
            .where { (planRunColumn(kind) eq versionId) and (PlanRuns.status inList listOf("success", "contingent")) }
            .any()
        if (inPlanRuns) return@transaction true
        KbRecords.selectAll().where { kbRecordColumn(kind) eq versionId }.any()
    }

    fun listVersions(caseId: Int, kind: ConfigVersionKind): List<VersionSummary> = transaction {
        CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .orderBy(CaseConfigVersions.id)
            .map { row ->
                val id = row[CaseConfigVersions.id]
                VersionSummary(
                    id = id,
                    name = row[CaseConfigVersions.name],
                    comments = row[CaseConfigVersions.comments],
                    createdAt = row[CaseConfigVersions.createdAt].toString(),
                    updatedAt = row[CaseConfigVersions.updatedAt].toString(),
                    referenced = isVersionReferenced(id, kind),
                )
            }
    }

    /** Creates a new (empty) version row. Caller is responsible for populating its data rows
     *  separately (this only creates the registry entry) — see each route file's "Save As"
     *  handler, which creates the version then writes the submitted rows against its id. */
    fun createVersion(caseId: Int, kind: ConfigVersionKind, name: String?, comments: String?): Int = transaction {
        CaseConfigVersions.insert {
            it[CaseConfigVersions.caseId] = caseId
            it[CaseConfigVersions.kind] = kind.key
            it[CaseConfigVersions.name] = name?.trim()?.takeIf { s -> s.isNotBlank() }
            it[CaseConfigVersions.comments] = comments?.trim()?.takeIf { s -> s.isNotBlank() }
        }[CaseConfigVersions.id]
    }

    /** Rename/edit comments — always allowed, even when referenced: metadata edits don't affect
     *  the historical fidelity of the DATA a run actually used. */
    fun renameVersion(versionId: Int, name: String?, comments: String?) = transaction {
        CaseConfigVersions.update({ CaseConfigVersions.id eq versionId }) {
            it[CaseConfigVersions.name] = name?.trim()?.takeIf { s -> s.isNotBlank() }
            it[CaseConfigVersions.comments] = comments?.trim()?.takeIf { s -> s.isNotBlank() }
            it[CaseConfigVersions.updatedAt] = Clock.System.now()
        }
        Unit
    }

    class VersionInUseException(message: String) : IllegalStateException(message)

    /** Deletes a version's registry row (its data rows cascade-delete with it — see
     *  CaseAllocations.versionId et al.'s own doc). Throws only if referenced by an existing
     *  completed plan run or KB record — no other guard. (There used to also be an is_default
     *  guard here; removed along with the whole default concept — see [CaseConfigVersions]' own
     *  doc.) */
    fun deleteVersion(caseId: Int, kind: ConfigVersionKind, versionId: Int) = transaction {
        CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.id eq versionId) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .singleOrNull() ?: throw NoSuchElementException("Version not found")
        if (isVersionReferenced(versionId, kind)) {
            throw VersionInUseException("Version is referenced by an existing plan run or KB record — use Save As instead")
        }
        CaseConfigVersions.deleteWhere { CaseConfigVersions.id eq versionId }
        Unit
    }
}
