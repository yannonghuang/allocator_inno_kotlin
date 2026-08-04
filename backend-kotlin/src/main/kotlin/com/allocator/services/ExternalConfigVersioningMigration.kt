package com.allocator.services

import com.allocator.CaseAllocationConfigs
import com.allocator.CaseAllocations
import com.allocator.CaseConstraintConfigs
import com.allocator.CaseConstraints
import com.allocator.CaseDemandOrderConfigs
import com.allocator.CaseDemandOrders
import com.allocator.CasePreferenceConfigs
import com.allocator.CasePreferences
import com.allocator.CasePurchasableMaterialConfigs
import com.allocator.CasePurchasableMaterials
import com.allocator.KbRecords
import com.allocator.PlanRuns
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory

/**
 * One-shot data migration: backfills `version_id` for the 5 external config objects (Critical
 * Raw Allocation / Supply Preferences / Demand Ordering / Purchasable Materials / Constraints)
 * whose rows predate the versioning feature — see [CaseConfigVersions][com.allocator.CaseConfigVersions]'
 * own doc in Tables.kt for the overall shape.
 *
 * For each case that has data-table rows or a companion "config" row for a given kind, but no
 * `version_id` on them yet, creates exactly one (unnamed) version and points those rows at it.
 * Every historical `plan_run`/`kb_record` row for that case is ALSO backfilled
 * to reference this same version for that kind — accurate, because before this feature there
 * was only ever one state per case, now captured as that version. This also correctly makes the
 * version immediately "referenced," protecting it from being silently overwritten right after
 * migration (see `CaseConfigVersioning.isVersionReferenced`).
 *
 * Idempotent — driven entirely off rows whose `version_id` is still null, so a case+kind that's
 * already been migrated contributes nothing on a re-run.
 */
object ExternalConfigVersioningMigration {
    private val log = LoggerFactory.getLogger("com.allocator.ExternalConfigVersioningMigration")

    fun run() = transaction {
        var created = 0

        created += migrateKind(
            kind = ConfigVersionKind.CASEALLOC,
            dataCaseIds = { CaseAllocations.selectAll().where { CaseAllocations.versionId.isNull() }.map { it[CaseAllocations.caseId] }.toSet() },
            configCaseIds = { CaseAllocationConfigs.selectAll().where { CaseAllocationConfigs.versionId.isNull() }.map { it[CaseAllocationConfigs.caseId] }.toSet() },
            backfillData = { caseId, versionId -> CaseAllocations.update({ (CaseAllocations.caseId eq caseId) and (CaseAllocations.versionId.isNull()) }) { it[CaseAllocations.versionId] = versionId } },
            backfillConfig = { caseId, versionId -> CaseAllocationConfigs.update({ (CaseAllocationConfigs.caseId eq caseId) and (CaseAllocationConfigs.versionId.isNull()) }) { it[CaseAllocationConfigs.versionId] = versionId } },
            planRunCol = PlanRuns.caseAllocVersionId,
            kbRecordCol = KbRecords.caseAllocVersionId,
        )

        created += migrateKind(
            kind = ConfigVersionKind.PREF,
            dataCaseIds = { CasePreferences.selectAll().where { CasePreferences.versionId.isNull() }.map { it[CasePreferences.caseId] }.toSet() },
            configCaseIds = { CasePreferenceConfigs.selectAll().where { CasePreferenceConfigs.versionId.isNull() }.map { it[CasePreferenceConfigs.caseId] }.toSet() },
            backfillData = { caseId, versionId -> CasePreferences.update({ (CasePreferences.caseId eq caseId) and (CasePreferences.versionId.isNull()) }) { it[CasePreferences.versionId] = versionId } },
            backfillConfig = { caseId, versionId -> CasePreferenceConfigs.update({ (CasePreferenceConfigs.caseId eq caseId) and (CasePreferenceConfigs.versionId.isNull()) }) { it[CasePreferenceConfigs.versionId] = versionId } },
            planRunCol = PlanRuns.prefVersionId,
            kbRecordCol = KbRecords.prefVersionId,
        )

        created += migrateKind(
            kind = ConfigVersionKind.ORD,
            dataCaseIds = { CaseDemandOrders.selectAll().where { CaseDemandOrders.versionId.isNull() }.map { it[CaseDemandOrders.caseId] }.toSet() },
            configCaseIds = { CaseDemandOrderConfigs.selectAll().where { CaseDemandOrderConfigs.versionId.isNull() }.map { it[CaseDemandOrderConfigs.caseId] }.toSet() },
            backfillData = { caseId, versionId -> CaseDemandOrders.update({ (CaseDemandOrders.caseId eq caseId) and (CaseDemandOrders.versionId.isNull()) }) { it[CaseDemandOrders.versionId] = versionId } },
            backfillConfig = { caseId, versionId -> CaseDemandOrderConfigs.update({ (CaseDemandOrderConfigs.caseId eq caseId) and (CaseDemandOrderConfigs.versionId.isNull()) }) { it[CaseDemandOrderConfigs.versionId] = versionId } },
            planRunCol = PlanRuns.ordVersionId,
            kbRecordCol = KbRecords.ordVersionId,
        )

        created += migrateKind(
            kind = ConfigVersionKind.PURCHMAT,
            dataCaseIds = { CasePurchasableMaterials.selectAll().where { CasePurchasableMaterials.versionId.isNull() }.map { it[CasePurchasableMaterials.caseId] }.toSet() },
            configCaseIds = { CasePurchasableMaterialConfigs.selectAll().where { CasePurchasableMaterialConfigs.versionId.isNull() }.map { it[CasePurchasableMaterialConfigs.caseId] }.toSet() },
            backfillData = { caseId, versionId -> CasePurchasableMaterials.update({ (CasePurchasableMaterials.caseId eq caseId) and (CasePurchasableMaterials.versionId.isNull()) }) { it[CasePurchasableMaterials.versionId] = versionId } },
            backfillConfig = { caseId, versionId -> CasePurchasableMaterialConfigs.update({ (CasePurchasableMaterialConfigs.caseId eq caseId) and (CasePurchasableMaterialConfigs.versionId.isNull()) }) { it[CasePurchasableMaterialConfigs.versionId] = versionId } },
            planRunCol = PlanRuns.purchMatVersionId,
            kbRecordCol = KbRecords.purchMatVersionId,
        )

        created += migrateKind(
            kind = ConfigVersionKind.CONSTR,
            dataCaseIds = { CaseConstraints.selectAll().where { CaseConstraints.versionId.isNull() }.map { it[CaseConstraints.caseId] }.toSet() },
            configCaseIds = { CaseConstraintConfigs.selectAll().where { CaseConstraintConfigs.versionId.isNull() }.map { it[CaseConstraintConfigs.caseId] }.toSet() },
            backfillData = { caseId, versionId -> CaseConstraints.update({ (CaseConstraints.caseId eq caseId) and (CaseConstraints.versionId.isNull()) }) { it[CaseConstraints.versionId] = versionId } },
            backfillConfig = { caseId, versionId -> CaseConstraintConfigs.update({ (CaseConstraintConfigs.caseId eq caseId) and (CaseConstraintConfigs.versionId.isNull()) }) { it[CaseConstraintConfigs.versionId] = versionId } },
            planRunCol = PlanRuns.constrVersionId,
            kbRecordCol = KbRecords.constrVersionId,
        )

        if (created > 0) {
            log.info("ExternalConfigVersioningMigration: created {} default versions", created)
        }
    }

    private fun migrateKind(
        kind: ConfigVersionKind,
        dataCaseIds: () -> Set<Int>,
        configCaseIds: () -> Set<Int>,
        backfillData: (caseId: Int, versionId: Int) -> Unit,
        backfillConfig: (caseId: Int, versionId: Int) -> Unit,
        planRunCol: Column<Int?>,
        kbRecordCol: Column<Int?>,
    ): Int {
        val caseIds = dataCaseIds() + configCaseIds()
        for (caseId in caseIds) {
            val versionId = CaseConfigVersioning.createVersion(caseId, kind, name = null, comments = null)
            backfillData(caseId, versionId)
            backfillConfig(caseId, versionId)
            PlanRuns.update({ (PlanRuns.caseId eq caseId) and (planRunCol.isNull()) }) { it[planRunCol] = versionId }
            KbRecords.update({ (KbRecords.caseId eq caseId) and (kbRecordCol.isNull()) }) { it[kbRecordCol] = versionId }
        }
        return caseIds.size
    }
}
