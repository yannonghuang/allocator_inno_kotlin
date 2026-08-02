package com.allocator

import com.allocator.services.applyAppSpecificConfig
import com.allocator.services.caseHasWipSupply
import com.allocator.services.resolveWipSupplyDates
import com.allocator.services.wipSupplyIds
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * App-specific config (ASC): each SUPPLY_DATE="wip" supply row is its own independent lot with
 * its own readiness schedule — resolved PER supply_id (config override, else horizon start —
 * same "auto" convention as method_selection.horizon_start), then substituted into
 * `data["supply"]` before planning ever sees them. See PlanningEngine.kt's ASC section doc.
 */
class AppSpecificConfigTest : FunSpec({

    val wipRow1 = mapOf("supply_id" to "wip_1", "product_id" to "P1", "location_id" to "L1", "supply_date" to "wip", "qty" to 10.0)
    val wipRow2 = mapOf("supply_id" to "wip_2", "product_id" to "P2", "location_id" to "L1", "supply_date" to "wip", "qty" to 5.0)
    val dateRow = mapOf("supply_id" to "s1", "product_id" to "P1", "location_id" to "L1", "supply_date" to "2026-01-15", "qty" to 20.0)
    val demandsWithDueDate = listOf(mapOf("request_due_time" to "2026-03-10"))

    // ── caseHasWipSupply / wipSupplyIds ──────────────────────────────────────

    test("caseHasWipSupply is true when at least one row is literally wip") {
        caseHasWipSupply(listOf(wipRow1, dateRow)) shouldBe true
    }

    test("caseHasWipSupply is false when no row is wip") {
        caseHasWipSupply(listOf(dateRow)) shouldBe false
        caseHasWipSupply(emptyList()) shouldBe false
    }

    test("caseHasWipSupply is case-sensitive and trims whitespace") {
        caseHasWipSupply(listOf(dateRow + mapOf("supply_date" to " wip "))) shouldBe true
        caseHasWipSupply(listOf(dateRow + mapOf("supply_date" to "WIP"))) shouldBe false
    }

    test("wipSupplyIds lists every distinct wip supply_id, ignoring non-wip rows") {
        wipSupplyIds(listOf(wipRow1, wipRow2, dateRow)) shouldBe listOf("wip_1", "wip_2")
        wipSupplyIds(listOf(dateRow)) shouldBe emptyList()
    }

    // ── resolveWipSupplyDates ─────────────────────────────────────────────────

    test("resolveWipSupplyDates defaults every lot to horizon start when absent") {
        val resolved = resolveWipSupplyDates(null, demandsWithDueDate, listOf(wipRow1, wipRow2))
        resolved["wip_1"].toString() shouldBe "2026-02-28"
        resolved["wip_2"].toString() shouldBe "2026-02-28"
    }

    test("resolveWipSupplyDates returns an empty map when the case has no wip lots") {
        resolveWipSupplyDates(null, demandsWithDueDate, listOf(dateRow)) shouldBe emptyMap()
    }

    test("resolveWipSupplyDates treats blank/auto override as absent for that lot") {
        val cfg = mapOf("app_specific_config" to mapOf("wip_supply_dates" to mapOf("wip_1" to "")))
        resolveWipSupplyDates(cfg, demandsWithDueDate, listOf(wipRow1))["wip_1"].toString() shouldBe "2026-02-28"
        val cfgAuto = mapOf("app_specific_config" to mapOf("wip_supply_dates" to mapOf("wip_1" to "auto")))
        resolveWipSupplyDates(cfgAuto, demandsWithDueDate, listOf(wipRow1))["wip_1"].toString() shouldBe "2026-02-28"
    }

    test("resolveWipSupplyDates resolves each lot independently — one override doesn't affect the other") {
        val cfg = mapOf("app_specific_config" to mapOf("wip_supply_dates" to mapOf("wip_1" to "2026-07-04")))
        val resolved = resolveWipSupplyDates(cfg, demandsWithDueDate, listOf(wipRow1, wipRow2))
        resolved["wip_1"].toString() shouldBe "2026-07-04"
        resolved["wip_2"].toString() shouldBe "2026-02-28"  // untouched lot still falls back to horizon start
    }

    test("resolveWipSupplyDates falls back to horizon start on an unparseable override for that lot only") {
        val cfg = mapOf("app_specific_config" to mapOf("wip_supply_dates" to mapOf("wip_1" to "not-a-date")))
        val resolved = resolveWipSupplyDates(cfg, demandsWithDueDate, listOf(wipRow1, wipRow2))
        resolved["wip_1"].toString() shouldBe "2026-02-28"
        resolved["wip_2"].toString() shouldBe "2026-02-28"
    }

    test("resolveWipSupplyDates' auto default cascades through an explicit horizon_start override") {
        val cfg = mapOf("method_selection" to mapOf("horizon_start" to "2026-05-01"))
        resolveWipSupplyDates(cfg, demandsWithDueDate, listOf(wipRow1))["wip_1"].toString() shouldBe "2026-05-01"
    }

    // ── applyAppSpecificConfig ────────────────────────────────────────────────

    test("applyAppSpecificConfig substitutes each lot with its own resolved date, others untouched") {
        val data = mapOf("supply" to listOf(wipRow1, wipRow2, dateRow), "demand" to demandsWithDueDate)
        val wipDates = mapOf(
            "wip_1" to java.time.LocalDate.parse("2026-06-01"),
            "wip_2" to java.time.LocalDate.parse("2026-09-15"),
        )
        val result = applyAppSpecificConfig(data, wipDates)
        val supplies = result["supply"]!!
        supplies.first { it["supply_id"] == "wip_1" }["supply_date"] shouldBe "2026-06-01"
        supplies.first { it["supply_id"] == "wip_2" }["supply_date"] shouldBe "2026-09-15"
        supplies.first { it["supply_id"] == "s1" }["supply_date"] shouldBe "2026-01-15"
    }

    test("applyAppSpecificConfig is a no-op when wipSupplyDates is empty") {
        val data = mapOf("supply" to listOf(wipRow1))
        applyAppSpecificConfig(data, emptyMap()) shouldBe data
    }

    test("applyAppSpecificConfig leaves a wip row unresolved if its supply_id is missing from the map") {
        val data = mapOf("supply" to listOf(wipRow1, wipRow2))
        val result = applyAppSpecificConfig(data, mapOf("wip_1" to java.time.LocalDate.parse("2026-06-01")))
        val supplies = result["supply"]!!
        supplies.first { it["supply_id"] == "wip_1" }["supply_date"] shouldBe "2026-06-01"
        supplies.first { it["supply_id"] == "wip_2" }["supply_date"] shouldBe "wip"
    }

    test("applyAppSpecificConfig preserves other data keys unchanged") {
        val data = mapOf("supply" to listOf(wipRow1), "demand" to demandsWithDueDate, "bom" to emptyList<Map<String, Any?>>())
        val result = applyAppSpecificConfig(data, mapOf("wip_1" to java.time.LocalDate.parse("2026-06-01")))
        result["demand"] shouldBe demandsWithDueDate
        result["bom"] shouldBe emptyList()
    }
})
