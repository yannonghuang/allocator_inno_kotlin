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
 * list, create, rename, default, and delete versions, and how to check whether a version is
 * "referenced" (used by some plan_run or kb_record) — the guard that decides whether an
 * in-place Save/Generate/Import/Clear/Delete is allowed, or whether the caller must "Save As" a
 * new version instead.
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
    val isDefault: Boolean,
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

    /** The case's current default version id for [kind], or null if none exists yet
     *  (untouched case — same "nothing yet" state these objects had before versioning). */
    fun defaultVersionId(caseId: Int, kind: ConfigVersionKind): Int? = transaction {
        CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) and (CaseConfigVersions.isDefault eq true) }
            .singleOrNull()?.get(CaseConfigVersions.id)
    }

    /** [requested] if it's a real version belonging to this case+kind, else the case's default,
     *  lazily creating an (empty, unnamed, default) version if none exists yet at all — mirrors
     *  how these objects previously came into existence implicitly on first Save/Generate. */
    fun resolveVersionId(caseId: Int, kind: ConfigVersionKind, requested: Int?): Int = transaction {
        if (requested != null) {
            val ok = CaseConfigVersions.selectAll()
                .where { (CaseConfigVersions.id eq requested) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
                .any()
            if (ok) return@transaction requested
        }
        defaultVersionId(caseId, kind) ?: createVersion(caseId, kind, name = null, comments = null, markDefault = true)
    }

    /** Whether [versionId] is used by any plan_run or kb_record for [kind] — the gate for
     *  Save/Generate/Import/Clear/Delete having to become "Save As" instead. */
    fun isVersionReferenced(versionId: Int, kind: ConfigVersionKind): Boolean = transaction {
        val inPlanRuns = PlanRuns.selectAll().where { planRunColumn(kind) eq versionId }.any()
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
                    isDefault = row[CaseConfigVersions.isDefault],
                    createdAt = row[CaseConfigVersions.createdAt].toString(),
                    updatedAt = row[CaseConfigVersions.updatedAt].toString(),
                    referenced = isVersionReferenced(id, kind),
                )
            }
    }

    /** Creates a new (empty) version row. Caller is responsible for populating its data rows
     *  separately (this only creates the registry entry) — see each route file's "Save As"
     *  handler, which creates the version then writes the submitted rows against its id. */
    fun createVersion(caseId: Int, kind: ConfigVersionKind, name: String?, comments: String?, markDefault: Boolean = false): Int = transaction {
        val id = CaseConfigVersions.insert {
            it[CaseConfigVersions.caseId] = caseId
            it[CaseConfigVersions.kind] = kind.key
            it[CaseConfigVersions.name] = name?.trim()?.takeIf { s -> s.isNotBlank() }
            it[CaseConfigVersions.comments] = comments?.trim()?.takeIf { s -> s.isNotBlank() }
            it[CaseConfigVersions.isDefault] = markDefault
        }[CaseConfigVersions.id]
        if (markDefault) {
            CaseConfigVersions.update({ (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) and (CaseConfigVersions.id neq id) }) {
                it[CaseConfigVersions.isDefault] = false
            }
        }
        id
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

    /** Always allowed — repointing which version is "current" never mutates any version's data. */
    fun setDefaultVersion(caseId: Int, kind: ConfigVersionKind, versionId: Int) = transaction {
        val belongs = CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.id eq versionId) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .any()
        if (!belongs) throw IllegalArgumentException("Version $versionId does not belong to case $caseId / kind ${kind.key}")
        CaseConfigVersions.update({ (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }) {
            it[CaseConfigVersions.isDefault] = false
        }
        CaseConfigVersions.update({ CaseConfigVersions.id eq versionId }) {
            it[CaseConfigVersions.isDefault] = true
            it[CaseConfigVersions.updatedAt] = Clock.System.now()
        }
        Unit
    }

    class VersionInUseException(message: String) : IllegalStateException(message)
    class VersionIsDefaultException(message: String) : IllegalStateException(message)

    /** Deletes a version's registry row (its data rows cascade-delete with it — see
     *  CaseAllocations.versionId et al.'s own doc). Throws if referenced by any run, or if it's
     *  the case's current default (must set another version as default first). */
    fun deleteVersion(caseId: Int, kind: ConfigVersionKind, versionId: Int) = transaction {
        val row = CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.id eq versionId) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .singleOrNull() ?: throw NoSuchElementException("Version not found")
        if (row[CaseConfigVersions.isDefault]) {
            throw VersionIsDefaultException("Cannot delete the default version — set another version as default first")
        }
        if (isVersionReferenced(versionId, kind)) {
            throw VersionInUseException("Version is referenced by an existing plan run or KB record — use Save As instead")
        }
        CaseConfigVersions.deleteWhere { CaseConfigVersions.id eq versionId }
        Unit
    }
}
