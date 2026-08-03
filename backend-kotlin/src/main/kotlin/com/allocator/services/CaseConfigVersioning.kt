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

/**
 * Parses `config.detached_external_configs` — a list of [ConfigVersionKind.key] strings the
 * caller wants EXPLICITLY excluded from this run, regardless of whether the case has an existing
 * default version for that kind. This is a third state alongside "explicit version pick" and
 * "use the case's current default" (the plain absence of a `*_version_id` key): detaching means
 * "never resolve any version for this kind on this run, full stop" — the per-request analog of
 * [CaseConfigVersioning.resolveVersionId] returning null for a case that's never had a version at
 * all. Added specifically so a stale/no-longer-representative version (e.g. a Critical Raw
 * Allocation auto-seeded under a since-changed `purchase_allowed`) can be bypassed for one run
 * without deleting it or touching the case's default. Per-request only — never persisted as a
 * case-level setting.
 */
internal fun parseDetachedKinds(config: Map<String, Any?>?): Set<ConfigVersionKind> {
    val raw = config?.get("detached_external_configs") as? List<*> ?: return emptySet()
    val keys = raw.mapNotNull { (it as? String)?.trim() }.toSet()
    return ConfigVersionKind.values().filter { it.key in keys }.toSet()
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

    /** [requested] if it's a real version belonging to this case+kind, else the case's current
     *  default version id — or **null** if this case has never had a version for this kind at
     *  all. A new case does NOT have to be associated with any of the 5 external config objects;
     *  never creates anything. Use for GET/read routes and for planning-time resolution
     *  (resolveEffectiveConfig/CaseBootstrap) — planning against a case that never touched a
     *  given object just uses that object's own "empty" convention (no constraints / all
     *  purchasable / etc.), exactly as it did before versioning existed.
     *
     *  Previously this eagerly created an empty default version whenever none existed — which
     *  meant merely GET-ing a config page for a brand-new case (or submitting a plan run without
     *  ever visiting one) silently materialized a version and immediately stamped it onto the new
     *  plan_run row. Combined with [isVersionReferenced] counting any plan_run regardless of
     *  status, that empty version came back "in use — read only" with no way to edit or delete it
     *  — confirmed live on a fresh case. See [resolveOrCreateVersionId] for the one legitimate
     *  place a version should still be created on demand: an actual Save/Generate/Import action. */
    fun resolveVersionId(caseId: Int, kind: ConfigVersionKind, requested: Int?): Int? = transaction {
        if (requested != null) {
            val ok = CaseConfigVersions.selectAll()
                .where { (CaseConfigVersions.id eq requested) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
                .any()
            if (ok) return@transaction requested
        }
        defaultVersionId(caseId, kind)
    }

    /** Same resolution as [resolveVersionId], but materializes a fresh (empty, unnamed, default)
     *  version when the case has never had one for this kind. This is the ONE legitimate
     *  create-on-demand moment: a caller that is actually about to WRITE data against the
     *  version (Save / Generate / Import) — never call this from a GET/read/planning path. */
    fun resolveOrCreateVersionId(caseId: Int, kind: ConfigVersionKind, requested: Int?): Int =
        resolveVersionId(caseId, kind, requested)
            ?: createVersion(caseId, kind, name = null, comments = null, markDefault = true)

    /** Same [requested]-first resolution as [resolveVersionId], but for a SILENT system auto-seed
     *  (currently only the interactive plan path's Critical Raw Allocation snapshot — see
     *  Allocate.kt's runPlanBackground doc) rather than an explicit user Save/Generate/Import.
     *  Never touches [CaseConfigVersions.isDefault] — reuses the case+kind's most recently
     *  created version (regardless of default status) if [requested] doesn't resolve, or creates
     *  a fresh NON-default one if none exists at all.
     *
     *  Deliberately does NOT fall back to [defaultVersionId]/resolveOrCreateVersionId's
     *  markDefault=true: a plan run that had no explicit override for this kind genuinely used
     *  none, and silently promoting its auto-computed snapshot to the case's new default would
     *  retroactively change what every future no-override run resolves to — confirmed live: a
     *  Critical Raw Allocation version auto-seeded this way became case default the instant it
     *  was created (old createVersion "first version always becomes default" rule), even though
     *  the triggering run's own case_alloc_version_id stayed null throughout, then silently
     *  attached itself to every subsequent run/picker that resolved "current default". Still
     *  reuses (rather than re-creates) the latest existing version so repeated no-override plan
     *  runs refresh the same cache slot instead of accumulating a fresh version every time. */
    fun resolveOrCreateSystemVersionId(caseId: Int, kind: ConfigVersionKind, requested: Int?): Int = transaction {
        if (requested != null) {
            val ok = CaseConfigVersions.selectAll()
                .where { (CaseConfigVersions.id eq requested) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
                .any()
            if (ok) return@transaction requested
        }
        CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .orderBy(CaseConfigVersions.id, org.jetbrains.exposed.sql.SortOrder.DESC)
            .limit(1)
            .singleOrNull()?.get(CaseConfigVersions.id)
            ?: createVersion(caseId, kind, name = null, comments = null, markDefault = false, forceDefaultIfFirst = false)
    }

    /** Whether [versionId] is used by any COMPLETED plan_run or kb_record for [kind] — the gate
     *  for Save/Generate/Import/Clear/Delete having to become "Save As" instead.
     *
     *  Only "success"/"contingent" plan_runs count — NOT "running" or "failed". `resolveVersionId`
     *  (via `resolveEffectiveConfig`) resolves and stamps a case's default version onto a plan_run
     *  row the moment a plan is SUBMITTED (status="running" at that point, before the run even
     *  executes — see Allocate.kt's own doc on resolveEffectiveConfig/runPlanBackground). Counting
     *  those meant a brand-new, still-empty default version — auto-created the instant a user first
     *  clicks "Run Plan" without ever having visited this config page — became permanently "in use
     *  — read only" even if that very first run then failed, with no way to edit or delete it
     *  (confirmed live: a fresh case's Version N showed "in use — read only" with Save disabled).
     *  A genuinely completed run (success, or contingent — a negotiation-branch result pending
     *  promotion) still must lock its version: mutating the config in place would retroactively
     *  change what that historical run says it used. kb_records don't need the same filter — a KB
     *  record is only ever created for an already-successful, sound run (see KbStore's own doc). */
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
                    isDefault = row[CaseConfigVersions.isDefault],
                    createdAt = row[CaseConfigVersions.createdAt].toString(),
                    updatedAt = row[CaseConfigVersions.updatedAt].toString(),
                    referenced = isVersionReferenced(id, kind),
                )
            }
    }

    /** Creates a new (empty) version row. Caller is responsible for populating its data rows
     *  separately (this only creates the registry entry) — see each route file's "Save As"
     *  handler, which creates the version then writes the submitted rows against its id.
     *
     *  Always becomes the default when it's the case+kind's FIRST version ever, regardless of
     *  [markDefault] — every "Save As" handler (and the planning-copilot's own version-create
     *  calls) omits [markDefault] and relies on this default of `false`, which used to mean a
     *  case whose only interaction with an object was "Save As" ended up with a version but NO
     *  default at all. That violates the invariant [resolveVersionId]'s own doc assumes ("a
     *  version exists ⇒ a default exists for that kind") and silently broke both the run picker
     *  (its Preview button reads a version's id via `versions.find(v => v.is_default)`, which
     *  found nothing) and plan submission itself (an untouched picker resolves via the same
     *  `is_default` lookup server-side, so the run used NO override even though the UI showed a
     *  version). A second-or-later "Save As" still stays non-default as before — only the very
     *  first version for a case+kind is special-cased.
     *
     *  [forceDefaultIfFirst] (default true) is that special case's own switch — set false only by
     *  [resolveOrCreateSystemVersionId]'s silent background auto-seed, where being the first
     *  version must NOT imply becoming the case's default (see its own doc for why). */
    fun createVersion(caseId: Int, kind: ConfigVersionKind, name: String?, comments: String?, markDefault: Boolean = false, forceDefaultIfFirst: Boolean = true): Int = transaction {
        val isFirstVersion = !CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .any()
        val effectiveDefault = markDefault || (isFirstVersion && forceDefaultIfFirst)
        val id = CaseConfigVersions.insert {
            it[CaseConfigVersions.caseId] = caseId
            it[CaseConfigVersions.kind] = kind.key
            it[CaseConfigVersions.name] = name?.trim()?.takeIf { s -> s.isNotBlank() }
            it[CaseConfigVersions.comments] = comments?.trim()?.takeIf { s -> s.isNotBlank() }
            it[CaseConfigVersions.isDefault] = effectiveDefault
        }[CaseConfigVersions.id]
        if (effectiveDefault) {
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
     *  the case's default AND another version for this case+kind still exists (must set that
     *  other one as default first — the invariant [resolveVersionId] assumes, "a version exists
     *  ⇒ a default exists for that kind", would otherwise break).
     *
     *  Deleting the SOLE version for a case+kind is allowed even though it's necessarily the
     *  default (every first version is, per [createVersion]) — [setDefaultVersion] can only
     *  promote an EXISTING other version, so "set another as default first" is impossible when
     *  there is no other version, making this a dead end otherwise (confirmed live: a case's only,
     *  fully-detached-from-every-run Critical Raw Allocation version could never be removed).
     *  Afterward the case simply has zero versions for that kind again — already a normal,
     *  fully-supported state (a case never has to touch any of the 5 external config objects). */
    fun deleteVersion(caseId: Int, kind: ConfigVersionKind, versionId: Int) = transaction {
        val row = CaseConfigVersions.selectAll()
            .where { (CaseConfigVersions.id eq versionId) and (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) }
            .singleOrNull() ?: throw NoSuchElementException("Version not found")
        if (row[CaseConfigVersions.isDefault]) {
            val otherVersionExists = CaseConfigVersions.selectAll()
                .where { (CaseConfigVersions.caseId eq caseId) and (CaseConfigVersions.kind eq kind.key) and (CaseConfigVersions.id neq versionId) }
                .any()
            if (otherVersionExists) {
                throw VersionIsDefaultException("Cannot delete the default version — set another version as default first")
            }
        }
        if (isVersionReferenced(versionId, kind)) {
            throw VersionInUseException("Version is referenced by an existing plan run or KB record — use Save As instead")
        }
        CaseConfigVersions.deleteWhere { CaseConfigVersions.id eq versionId }
        Unit
    }
}
