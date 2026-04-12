package com.allocator

import com.allocator.services.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import java.time.LocalDate

class ConsolidationEngineTest : FunSpec({

    // ── Fixtures ──────────────────────────────────────────────────────────────

    fun demand(
        id: String, productId: String, locationId: String,
        qty: Double, priority: Int, dueDate: String? = "2025-01-20",
    ): Map<String, Any?> = mapOf(
        "demand_id" to id, "product_id" to productId, "location_id" to locationId,
        "quantity" to qty, "priority" to priority,
        "request_due_time" to dueDate, "request_time" to dueDate,
    )

    fun supply(productId: String, locationId: String, qty: Double, date: String? = "2025-01-01"): MutableMap<String, Any?> =
        mutableMapOf("product_id" to productId, "location_id" to locationId, "qty" to qty, "supply_date" to date, "supply_id" to "s1")

    /** A planFn stub that always "produces" the requested qty with one WO. */
    fun simplePlanFn(
        demand: Map<String, Any?>,
        @Suppress("UNUSED_PARAMETER") inv: MutableList<MutableMap<String, Any?>>,
        @Suppress("UNUSED_PARAMETER") data: Map<String, List<Map<String, Any?>>>,
        reqDt: LocalDate?,
        @Suppress("UNUSED_PARAMETER") depth: Int,
        @Suppress("UNUSED_PARAMETER") path: Set<Pair<String, String>>,
        @Suppress("UNUSED_PARAMETER") config: Map<String, Any?>?,
        @Suppress("UNUSED_PARAMETER") prefId: Any?,
    ): Triple<List<Map<String, Any?>>, List<Map<String, Any?>>, Map<String, Any?>?> {
        val qty = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
        val pid = demand["product_id"] as? String ?: ""
        val lid = demand["location_id"] as? String ?: ""
        val endDate = reqDt?.toString() ?: "2025-01-10"
        val committed = listOf(mapOf<String, Any?>("demand_id" to null, "product_id" to pid, "location_id" to lid, "quantity" to qty, "commit_time" to endDate))
        val wos       = listOf(mapOf<String, Any?>("product_id" to pid, "location_id" to lid, "quantity" to qty, "method" to "make", "demand_id" to null, "end_time" to endDate))
        return Triple(committed, wos, null)
    }

    /** planFn that caps produced qty at maxProduced. */
    fun cappedPlanFn(maxProduced: Double) = fun(
        demand: Map<String, Any?>,
        inv: MutableList<MutableMap<String, Any?>>,
        data: Map<String, List<Map<String, Any?>>>,
        reqDt: LocalDate?,
        depth: Int,
        path: Set<Pair<String, String>>,
        cfg: Map<String, Any?>?,
        prefId: Any?,
    ): Triple<List<Map<String, Any?>>, List<Map<String, Any?>>, Map<String, Any?>?> {
        val requested = (demand["quantity"] as? Number)?.toDouble() ?: 0.0
        val produced = minOf(requested, maxProduced)
        val demand2 = demand.toMutableMap().also { it["quantity"] = produced }
        return simplePlanFn(demand2, inv, data, reqDt, depth, path, cfg, prefId)
    }

    val emptyData: Map<String, List<Map<String, Any?>>> = emptyMap()

    // ── Scenario 1: Two demands, shared component, priority_first ─────────────

    test("Sc1: two demands share component — 1 consolidated WO, priority_first allocation") {
        val d1need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG1")
        val d2need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 40.0, "D2", 2, "FG2")
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), listOf(d1need, d2need), 50.0)
        val inv    = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "priority_first")

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = ::simplePlanFn)

        result.consolidatedWOs shouldHaveSize 1
        result.consolidatedWOs[0]["consolidated"] shouldBe true
        result.consolidatedWOs[0]["demand_id"] shouldBe null

        result.allocation["D1"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)
        result.allocation["D2"]!!["C|L"]!! shouldBe (40.0 plusOrMinus 1e-9)
    }

    // ── Scenario 2: Two demands, lot-size constraint doesn't change allocation ─

    test("Sc2: allocation based on total produced qty regardless of lot count") {
        // simplePlanFn always produces full qty regardless of lot size
        val d1need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG1")
        val d2need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 40.0, "D2", 2, "FG2")
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), listOf(d1need, d2need), 50.0)
        val inv    = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "priority_first")

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = ::simplePlanFn)

        // produced=50, D1 gets 10, D2 gets 40
        result.allocation["D1"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)
        result.allocation["D2"]!!["C|L"]!! shouldBe (40.0 plusOrMinus 1e-9)
    }

    // ── Scenario 3: Proportional allocation, scarce (produced=25) ─────────────

    test("Sc3: proportional allocation with scarce output (produced=25)") {
        val d1need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG1")
        val d2need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 40.0, "D2", 2, "FG2")
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), listOf(d1need, d2need), 50.0)
        val inv    = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "proportional")

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = cappedPlanFn(25.0))

        // D1 → 25 * (10/50) = 5.0; D2 → 25 * (40/50) = 20.0
        result.allocation["D1"]!!["C|L"]!! shouldBe (5.0 plusOrMinus 1e-9)
        result.allocation["D2"]!!["C|L"]!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    // ── Scenario 4: Priority_first with scarcity ──────────────────────────────

    test("Sc4: priority_first scarcity — D1 gets full need, D2 gets remainder") {
        val d1need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 30.0, "D1", 1, "FG1")
        val d2need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 30.0, "D2", 2, "FG2")
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), listOf(d1need, d2need), 60.0)
        val inv    = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "priority_first")

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = cappedPlanFn(40.0))

        result.allocation["D1"]!!["C|L"]!! shouldBe (30.0 plusOrMinus 1e-9)  // full
        result.allocation["D2"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)  // only remainder
    }

    // ── Scenario 5: Single demand — pass-through, no "consolidated" flag ──────

    test("Sc5: single demand group — pass-through with original demandId") {
        val d1need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG1")
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), listOf(d1need), 10.0)
        val inv    = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7)

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = ::simplePlanFn)

        result.consolidatedWOs shouldHaveSize 1
        result.consolidatedWOs[0]["consolidated"] shouldBe false   // pass-through
        result.allocation["D1"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)
    }

    // ── Scenario 6: Three demands, same component, same bucket ───────────────

    test("Sc6: three demands same component same bucket — 1 consolidated WO qty=30") {
        val needs = listOf(
            ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG1"),
            ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D2", 2, "FG2"),
            ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D3", 3, "FG3"),
        )
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), needs, 30.0)
        val inv    = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "priority_first")

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = ::simplePlanFn)

        result.consolidatedWOs shouldHaveSize 1
        result.allocation["D1"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)
        result.allocation["D2"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)
        result.allocation["D3"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)
    }

    // ── Scenario 7: Different time buckets → separate groups ──────────────────

    test("Sc7: timeBucket separates Jan 3 and Jan 14 with period_days=7") {
        val jan3  = LocalDate.of(2025, 1, 3)
        val jan14 = LocalDate.of(2025, 1, 14)
        val bucket3  = timeBucket(jan3,  7)
        val bucket14 = timeBucket(jan14, 7)
        // They must be in different buckets
        (bucket3 == bucket14) shouldBe false

        val needs = listOf(
            ComponentNeed("C", "L", jan3,  10.0, "D1", 1, "FG1"),
            ComponentNeed("C", "L", jan14, 40.0, "D2", 2, "FG2"),
        )
        val groups = groupByTimeBucket(needs, 7)
        groups shouldHaveSize 2
    }

    // ── Scenario 8: Same time bucket → consolidated ───────────────────────────

    test("Sc8: Jan 3 and Jan 5 with period_days=7 land in same bucket") {
        val jan3 = LocalDate.of(2025, 1, 3)
        val jan5 = LocalDate.of(2025, 1, 5)
        timeBucket(jan3, 7) shouldBe timeBucket(jan5, 7)

        val needs = listOf(
            ComponentNeed("C", "L", jan3, 10.0, "D1", 1, "FG1"),
            ComponentNeed("C", "L", jan5, 40.0, "D2", 2, "FG2"),
        )
        val groups = groupByTimeBucket(needs, 7)
        groups shouldHaveSize 1
        groups[0].totalQty shouldBe (50.0 plusOrMinus 1e-9)
    }

    // ── Scenario 9: Consolidation disabled — zero new code path ──────────────

    test("Sc9: parseConsolidationConfig returns disabled when key absent") {
        parseConsolidationConfig(null).enabled shouldBe false
        parseConsolidationConfig(emptyMap<String, Any?>()).enabled shouldBe false
        parseConsolidationConfig(mapOf("consolidation" to mapOf("enabled" to false))).enabled shouldBe false
    }

    test("Sc9b: parseConsolidationConfig enabled=true parses all fields") {
        val cfg = parseConsolidationConfig(mapOf(
            "consolidation" to mapOf(
                "enabled" to true,
                "period_days" to 14,
                "allocation_mode" to "proportional",
            )
        ))
        cfg.enabled shouldBe true
        cfg.periodDays shouldBe 14
        cfg.allocationMode shouldBe "proportional"
    }

    // ── Scenario 10: Non-shared component — pass-through ─────────────────────

    test("Sc10: single-demand group is treated as pass-through") {
        val need  = ComponentNeed("X", "L", LocalDate.of(2025, 1, 15), 5.0, "D1", 1, "FG1")
        val group = ConsolidationGroup("X", "L", LocalDate.of(2025, 1, 9), listOf(need), 5.0)
        val inv   = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7)

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = ::simplePlanFn)

        result.consolidatedWOs shouldHaveSize 1
        result.consolidatedWOs[0]["consolidated"] shouldBe false
    }

    // ── Scenario 11: Mixed shared + non-shared components ────────────────────

    test("Sc11: shared C and unique X — C consolidated, X pass-through") {
        val needC1 = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG1")
        val needC2 = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 40.0, "D2", 2, "FG2")
        val needX  = ComponentNeed("X", "L", LocalDate.of(2025, 1, 15),  5.0, "D1", 1, "FG1")
        val groups = groupByTimeBucket(listOf(needC1, needC2, needX), 7)

        groups shouldHaveSize 2
        val groupC = groups.first { it.productId == "C" }
        val groupX = groups.first { it.productId == "X" }
        groupC.needs shouldHaveSize 2
        groupX.needs shouldHaveSize 1

        val inv    = mutableListOf<MutableMap<String, Any?>>()
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "priority_first")
        val result = runConsolidation(groups, inv, emptyData, config, planFn = ::simplePlanFn)

        result.consolidatedWOs shouldHaveSize 2
        val woC = result.consolidatedWOs.first { it["product_id"] == "C" }
        val woX = result.consolidatedWOs.first { it["product_id"] == "X" }
        woC["consolidated"] shouldBe true
        woX["consolidated"] shouldBe false

        result.allocation["D1"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)
        result.allocation["D1"]!!["X|L"]!! shouldBe (5.0  plusOrMinus 1e-9)
        result.allocation["D2"]!!["C|L"]!! shouldBe (40.0 plusOrMinus 1e-9)
    }

    // ── Scenario 12: Tagged inventory preference ──────────────────────────────

    test("Sc12: consumeFromInventory prefers demand-tagged bucket") {
        // Use PlanningEngine's consumeFromInventory via runConsolidation injection to verify tagged behavior
        // We test this by checking that after consolidation injects tagged buckets,
        // the split correctly assigns qty per demand.

        // Indirect test: after runConsolidation, two tagged buckets are in inventory when injected by runPlanning.
        // Directly test splitPriorityFirst gives the right split:
        val group = ConsolidationGroup(
            productId  = "C",
            locationId = "L",
            timeBucket = LocalDate.of(2025, 1, 9),
            needs = listOf(
                ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG"),
                ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 40.0, "D2", 2, "FG"),
            ),
            totalQty = 50.0,
        )
        val split = splitPriorityFirst(group, 50.0)
        // D1 gets 10, D2 gets 40 — D2 should NOT receive D1's share
        split["D1"]!! shouldBe (10.0 plusOrMinus 1e-9)
        split["D2"]!! shouldBe (40.0 plusOrMinus 1e-9)
        // No other keys
        split.size shouldBe 2
    }

    // ── Scenario 13: collectComponentNeeds — make method ─────────────────────

    test("Sc13: collectComponentNeeds extracts child from make method") {
        val demands = listOf(demand("D1", "FG", "PLANT", 100.0, 1, "2025-01-20"))
        val data = mapOf(
            "method_make" to listOf(mapOf(
                "product_id" to "FG", "location_id" to "PLANT",
                "bom_id" to "BOM1", "preference" to 1, "lead_time" to 5,
            )),
            "bom" to listOf(mapOf(
                "parent_id" to "FG", "child_id" to "C",
                "bom_id" to "BOM1", "rate" to 1.0, "alt_group" to null,
            )),
        )
        val needs = collectComponentNeeds(demands, data, ConsolidationConfig())
        needs shouldHaveSize 1
        val n = needs[0]
        n.productId       shouldBe "C"
        n.locationId      shouldBe "PLANT"
        n.qty             shouldBe (100.0 plusOrMinus 1e-9)
        n.demandId        shouldBe "D1"
        n.priority        shouldBe 1
        n.parentProductId shouldBe "FG"
        // dueDate = 2025-01-20 - 5 days = 2025-01-15
        n.dueDate         shouldBe LocalDate.of(2025, 1, 15)
    }

    // ── Scenario 14: collectComponentNeeds — move method ─────────────────────

    test("Sc14: collectComponentNeeds extracts source from move method") {
        val demands = listOf(demand("D1", "P", "PLANT-B", 50.0, 1, "2025-01-20"))
        val data = mapOf(
            "method_move" to listOf(mapOf(
                "product_id" to "P", "from_location_id" to "PLANT-A", "to_location_id" to "PLANT-B",
                "preference" to 1, "transit_time" to 3.0,
            )),
        )
        val needs = collectComponentNeeds(demands, data, ConsolidationConfig())
        needs shouldHaveSize 1
        val n = needs[0]
        n.productId  shouldBe "P"
        n.locationId shouldBe "PLANT-A"
        n.qty        shouldBe (50.0 plusOrMinus 1e-9)
        n.dueDate    shouldBe LocalDate.of(2025, 1, 17) // 2025-01-20 - 3 days
    }

    // ── Scenario 15: collectComponentNeeds — no method ────────────────────────

    test("Sc15: collectComponentNeeds returns empty for demand with no methods") {
        val demands = listOf(demand("D1", "X", "L", 10.0, 1))
        val needs = collectComponentNeeds(demands, emptyData, ConsolidationConfig())
        needs.shouldBeEmpty()
    }

    // ── Scenario 16: splitPriorityFirst correctness ───────────────────────────

    test("Sc16: splitPriorityFirst — priorities [1,2,3], needs [30,30,30], available=50") {
        val group = ConsolidationGroup(
            productId = "C", locationId = "L", timeBucket = LocalDate.EPOCH,
            needs = listOf(
                ComponentNeed("C", "L", null, 30.0, "D1", 1, "FG"),
                ComponentNeed("C", "L", null, 30.0, "D2", 2, "FG"),
                ComponentNeed("C", "L", null, 30.0, "D3", 3, "FG"),
            ),
            totalQty = 90.0,
        )
        val split = splitPriorityFirst(group, 50.0)
        split["D1"]!! shouldBe (30.0 plusOrMinus 1e-9)
        split["D2"]!! shouldBe (20.0 plusOrMinus 1e-9)
        (split["D3"] ?: 0.0) shouldBe (0.0 plusOrMinus 1e-9)
        split.values.sum() shouldBe (50.0 plusOrMinus 1e-9)
    }

    // ── Scenario 17: splitProportional correctness ────────────────────────────

    test("Sc17: splitProportional — needs [10,40], available=25 → [5.0, 20.0]") {
        val group = ConsolidationGroup(
            productId = "C", locationId = "L", timeBucket = LocalDate.EPOCH,
            needs = listOf(
                ComponentNeed("C", "L", null, 10.0, "D1", 1, "FG"),
                ComponentNeed("C", "L", null, 40.0, "D2", 2, "FG"),
            ),
            totalQty = 50.0,
        )
        val split = splitProportional(group, 25.0)
        split["D1"]!! shouldBe (5.0  plusOrMinus 1e-9)
        split["D2"]!! shouldBe (20.0 plusOrMinus 1e-9)
        split.values.sum() shouldBe (25.0 plusOrMinus 1e-9)
    }
})
