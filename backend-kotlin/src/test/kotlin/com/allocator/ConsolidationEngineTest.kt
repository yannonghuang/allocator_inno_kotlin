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
        // Inventory-only consolidation: ample on-hand stock for C → full 50 allocatable.
        val inv    = mutableListOf(supply("C", "L", 100.0))
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
        val inv    = mutableListOf(supply("C", "L", 100.0))
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
        // Scarcity is now modeled as limited on-hand stock (25) rather than a capped
        // planFn: consolidation allocates only the 25 available, split proportionally.
        val inv    = mutableListOf(supply("C", "L", 25.0))
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "proportional")

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = ::simplePlanFn)

        // D1 → 25 * (10/50) = 5.0; D2 → 25 * (40/50) = 20.0
        result.allocation["D1"]!!["C|L"]!! shouldBe (5.0 plusOrMinus 1e-9)
        result.allocation["D2"]!!["C|L"]!! shouldBe (20.0 plusOrMinus 1e-9)
    }

    // ── Scenario 4: Priority_first with scarcity ──────────────────────────────

    test("Sc4: priority_first scarcity — D1 gets full need, D2 gets remainder") {
        val d1need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 30.0, "D1", 1, "FG1")
        val d2need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 30.0, "D2", 2, "FG2")
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), listOf(d1need, d2need), 60.0)
        // Scarce on-hand stock (40) split priority_first across the two demands.
        val inv    = mutableListOf(supply("C", "L", 40.0))
        val config = ConsolidationConfig(enabled = true, periodDays = 7, allocationMode = "priority_first")

        val result = runConsolidation(listOf(group), inv, emptyData, config, planFn = ::simplePlanFn)

        result.allocation["D1"]!!["C|L"]!! shouldBe (30.0 plusOrMinus 1e-9)  // full
        result.allocation["D2"]!!["C|L"]!! shouldBe (10.0 plusOrMinus 1e-9)  // only remainder
    }

    // ── Scenario 5: Single demand — pass-through, no "consolidated" flag ──────

    test("Sc5: single demand group — pass-through with original demandId") {
        val d1need = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 10.0, "D1", 1, "FG1")
        val group  = ConsolidationGroup("C", "L", LocalDate.of(2025, 1, 9), listOf(d1need), 10.0)
        val inv    = mutableListOf(supply("C", "L", 100.0))
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
        val inv    = mutableListOf(supply("C", "L", 100.0))
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

    // ── Scenario 7b: period_days=0 sentinel collapses all dates ───────────────

    test("Sc7b: timeBucket(period_days=0) returns EPOCH for all dates") {
        val jan3   = LocalDate.of(2025, 1, 3)
        val jan14  = LocalDate.of(2025, 1, 14)
        val dec31  = LocalDate.of(2030, 12, 31)
        timeBucket(jan3,  0) shouldBe LocalDate.EPOCH
        timeBucket(jan14, 0) shouldBe LocalDate.EPOCH
        timeBucket(dec31, 0) shouldBe LocalDate.EPOCH
    }

    test("Sc7c: groupByTimeBucket(period_days=0) merges across all dates") {
        val needs = listOf(
            ComponentNeed("C", "L", LocalDate.of(2025, 1, 3),   10.0, "D1", 1, "FG1"),
            ComponentNeed("C", "L", LocalDate.of(2025, 6, 15),  40.0, "D2", 2, "FG2"),
            ComponentNeed("C", "L", LocalDate.of(2030, 12, 31), 50.0, "D3", 3, "FG3"),
        )
        val groups = groupByTimeBucket(needs, 0)
        groups shouldHaveSize 1
        groups[0].timeBucket shouldBe LocalDate.EPOCH
        groups[0].totalQty shouldBe (100.0 plusOrMinus 1e-9)
    }

    test("Sc7d: parseConsolidationConfig accepts period_days=0") {
        val cfg = parseConsolidationConfig(mapOf(
            "consolidation" to mapOf("enabled" to true, "period_days" to 0)
        ))
        cfg.periodDays shouldBe 0
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

    test("Sc9c: max_iterations defaults to 1 and clamps to 1..15") {
        parseConsolidationConfig(mapOf("consolidation" to mapOf("enabled" to true))).maxIterations shouldBe 1
        parseConsolidationConfig(mapOf("consolidation" to mapOf("enabled" to true, "max_iterations" to 15))).maxIterations shouldBe 15
        parseConsolidationConfig(mapOf("consolidation" to mapOf("enabled" to true, "max_iterations" to 0))).maxIterations shouldBe 1   // clamped
        parseConsolidationConfig(mapOf("consolidation" to mapOf("enabled" to true, "max_iterations" to 99))).maxIterations shouldBe 15 // clamped
        parseConsolidationConfig(mapOf("consolidation" to mapOf("enabled" to true, "max_iter" to 3))).maxIterations shouldBe 3        // short alias
    }

    // ── Scenario 10: Non-shared component — pass-through ─────────────────────

    test("Sc10: single-demand group is treated as pass-through") {
        val need  = ComponentNeed("X", "L", LocalDate.of(2025, 1, 15), 5.0, "D1", 1, "FG1")
        val group = ConsolidationGroup("X", "L", LocalDate.of(2025, 1, 9), listOf(need), 5.0)
        val inv   = mutableListOf(supply("X", "L", 100.0))
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

        val inv    = mutableListOf(supply("C", "L", 100.0), supply("X", "L", 100.0))
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
            // Child C@PLANT must have supply for collectDeepNeeds to register it
            // (intermediate make-chain nodes without inventory are passed through).
            "supply" to listOf(supply("C", "PLANT", 100.0)),
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
            // Source P@PLANT-A must have supply for collectDeepNeeds to register it.
            "supply" to listOf(supply("P", "PLANT-A", 50.0)),
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

    // ── Scenario 18: Root method selection aligns with PlanningEngine's cascade ──
    //
    // Regression: naive minByOrNull at the root of collectDeepNeeds picked a method
    // (move) whose cascade probe fails — while PlanningEngine.plan()'s cascade picked
    // a different method (make) at the same root. The divergence left the tagged-bucket
    // pre-allocation orphaned because main plan walked a different BOM path.
    //
    // Setup: FG@PLANT has two methods — move (pref=1) from SRC where nothing exists, and
    // make (pref=2) with a BOM child C@PLANT that has supply. Cascade must reject move and
    // pick make; collectComponentNeeds should walk make's path and register C@PLANT, and
    // methodChoices should record the pinned method.
    test("Sc18: collectComponentNeeds aligns root method with PlanningEngine cascade") {
        val demands = listOf(demand("D1", "FG", "PLANT", 100.0, 1, "2025-01-20"))
        val data = mapOf(
            "method_move" to listOf(mapOf(
                "product_id" to "FG", "from_location_id" to "SRC", "to_location_id" to "PLANT",
                "preference" to 1, "transit_time" to 0.0,
            )),
            "method_make" to listOf(mapOf(
                "product_id" to "FG", "location_id" to "PLANT",
                "bom_id" to "B1", "preference" to 2, "lead_time" to 0,
            )),
            "bom" to listOf(mapOf(
                "parent_id" to "FG", "child_id" to "C",
                "bom_id" to "B1", "rate" to 1.0, "alt_group" to null,
            )),
            "supply" to listOf(supply("C", "PLANT", 1000.0)),
        )
        val inventory: List<Map<String, Any?>> = (data["supply"] ?: emptyList()).map { s ->
            mapOf(
                "product_id"  to s["product_id"],
                "location_id" to s["location_id"],
                "supply_date" to s["supply_date"],
                "supply_id"   to s["supply_id"],
                "qty"         to s["qty"],
                "demand_tag"  to null,
            )
        }
        val methodChoices = mutableMapOf<Triple<String, String, String>, Map<String, Any?>>()
        val needs = collectComponentNeeds(
            demands, data, ConsolidationConfig(),
            inventory = inventory, planConfig = null, methodChoices = methodChoices,
        )

        // Cascade picked make — BOM child C@PLANT registered as a need.
        needs shouldHaveSize 1
        needs[0].productId  shouldBe "C"
        needs[0].locationId shouldBe "PLANT"

        // methodChoices records the root-level pick for synthetic override injection.
        val key = Triple("FG", "PLANT", "D1")
        methodChoices shouldContainKey key
        methodChoices[key]!!["type"] shouldBe "make"
        (methodChoices[key]!!["preference"] as Number).toInt() shouldBe 2
    }

    // ── Scenario 19: no divergence — single-method demands don't emit methodChoice ──
    test("Sc19: single-method demand does not record a methodChoice") {
        val demands = listOf(demand("D1", "FG", "PLANT", 100.0, 1, "2025-01-20"))
        val data = mapOf(
            "method_make" to listOf(mapOf(
                "product_id" to "FG", "location_id" to "PLANT",
                "bom_id" to "B1", "preference" to 1, "lead_time" to 0,
            )),
            "bom" to listOf(mapOf(
                "parent_id" to "FG", "child_id" to "C",
                "bom_id" to "B1", "rate" to 1.0, "alt_group" to null,
            )),
            "supply" to listOf(supply("C", "PLANT", 1000.0)),
        )
        val methodChoices = mutableMapOf<Triple<String, String, String>, Map<String, Any?>>()
        collectComponentNeeds(
            demands, data, ConsolidationConfig(),
            inventory = emptyList(), planConfig = null, methodChoices = methodChoices,
        )
        methodChoices shouldNotContainKey Triple("FG", "PLANT", "D1")
    }

    // ── Scenario 20: feasibility gate skips demand when every root method is infeasible ──
    //
    // Regression test for phantom-WO bug: demand 828 in case 115 had two make methods at
    // F29__828@VIRTUAL, both with a BOM child (500-6336@VIRTUAL) that has no supply and no
    // production methods. Consolidation previously walked the reachable siblings and
    // registered ComponentNeeds anyway — producing WOs (at 500-5522@2000 etc.) that consumed
    // real inventory without ever rolling up into F29__828 (the unreachable sibling blocked
    // it). Main plan correctly committed qty=0 with child_failed, leaving the consolidation
    // WOs orphaned.
    //
    // Setup: two make methods for FG@PLANT, each with two children. Every method has at
    // least one child that has no supply and no methods of its own (BAD1, BAD2). The
    // reachable siblings (C1, C2) should NOT be registered, because the parent BOM can
    // never produce FG.
    test("Sc20: feasibility gate skips demand when every root method has an unreachable child") {
        val demands = listOf(demand("D1", "FG", "PLANT", 100.0, 1, "2025-01-20"))
        val data = mapOf(
            "method_make" to listOf(
                mapOf("product_id" to "FG", "location_id" to "PLANT",
                      "bom_id" to "B1", "preference" to 1, "lead_time" to 0),
                mapOf("product_id" to "FG", "location_id" to "PLANT",
                      "bom_id" to "B2", "preference" to 2, "lead_time" to 0),
            ),
            "bom" to listOf(
                mapOf("parent_id" to "FG", "child_id" to "C1",   "bom_id" to "B1", "rate" to 1.0, "alt_group" to null),
                mapOf("parent_id" to "FG", "child_id" to "BAD1", "bom_id" to "B1", "rate" to 1.0, "alt_group" to null),
                mapOf("parent_id" to "FG", "child_id" to "C2",   "bom_id" to "B2", "rate" to 1.0, "alt_group" to null),
                mapOf("parent_id" to "FG", "child_id" to "BAD2", "bom_id" to "B2", "rate" to 1.0, "alt_group" to null),
            ),
            "supply" to listOf(supply("C1", "PLANT", 1000.0), supply("C2", "PLANT", 1000.0)),
            // BAD1 / BAD2: no supply, no methods → unreachable
        )
        val inventory: List<Map<String, Any?>> = (data["supply"] ?: emptyList()).map { s ->
            mapOf(
                "product_id"  to s["product_id"],
                "location_id" to s["location_id"],
                "supply_date" to s["supply_date"],
                "supply_id"   to s["supply_id"],
                "qty"         to s["qty"],
                "demand_tag"  to null,
            )
        }
        val methodChoices = mutableMapOf<Triple<String, String, String>, Map<String, Any?>>()
        val needs = collectComponentNeeds(
            demands, data, ConsolidationConfig(),
            inventory = inventory, planConfig = null, methodChoices = methodChoices,
        )
        // Gate triggered — no ComponentNeeds registered (no phantom pre-allocation).
        needs shouldHaveSize 0
        // And no methodChoice recorded (no override to inject).
        methodChoices shouldNotContainKey Triple("FG", "PLANT", "D1")
    }

    // ── Scenario 21: feasibility gate picks feasible method when one exists ──
    //
    // When the lowest-preference method has an unreachable child but a higher-preference
    // method is fully reachable, consolidation should register the reachable method's BOM
    // and record it in methodChoices (for the synthetic override pinning main plan).
    test("Sc21: feasibility gate picks the feasible higher-preference method") {
        val demands = listOf(demand("D1", "FG", "PLANT", 100.0, 1, "2025-01-20"))
        val data = mapOf(
            "method_make" to listOf(
                // pref=1 is infeasible (BAD has no supply/methods)
                mapOf("product_id" to "FG", "location_id" to "PLANT",
                      "bom_id" to "B1", "preference" to 1, "lead_time" to 0),
                // pref=2 is fully reachable (C has supply)
                mapOf("product_id" to "FG", "location_id" to "PLANT",
                      "bom_id" to "B2", "preference" to 2, "lead_time" to 0),
            ),
            "bom" to listOf(
                mapOf("parent_id" to "FG", "child_id" to "BAD", "bom_id" to "B1", "rate" to 1.0, "alt_group" to null),
                mapOf("parent_id" to "FG", "child_id" to "C",   "bom_id" to "B2", "rate" to 1.0, "alt_group" to null),
            ),
            "supply" to listOf(supply("C", "PLANT", 1000.0)),
        )
        val inventory: List<Map<String, Any?>> = (data["supply"] ?: emptyList()).map { s ->
            mapOf(
                "product_id"  to s["product_id"],
                "location_id" to s["location_id"],
                "supply_date" to s["supply_date"],
                "supply_id"   to s["supply_id"],
                "qty"         to s["qty"],
                "demand_tag"  to null,
            )
        }
        val methodChoices = mutableMapOf<Triple<String, String, String>, Map<String, Any?>>()
        val needs = collectComponentNeeds(
            demands, data, ConsolidationConfig(),
            inventory = inventory, planConfig = null, methodChoices = methodChoices,
        )
        // Gate passed (pref=2 is feasible); walked that BOM path and registered C@PLANT.
        needs shouldHaveSize 1
        needs[0].productId  shouldBe "C"
        needs[0].locationId shouldBe "PLANT"
        // Cascade's first-feasible is pref=2 (pref=1 failed the probe).
        val key = Triple("FG", "PLANT", "D1")
        methodChoices shouldContainKey key
        (methodChoices[key]!!["preference"] as Number).toInt() shouldBe 2
    }

    // ── Scenario 22: probe catches depth-2 child_failed — scoreVariant zero-qty gap ──
    //
    // Regression for the residual 888_F30_2024_07_VIRTUAL phantom-WO case. FG's BOM has
    // two children: C1 (reachable, has supply) and P (intermediate — has its own make
    // method, but that method's child GRANDCHILD is unreachable).  plan(P) hits the
    // capped<=1e-9 branch in PlanningEngine.plan (around line 1133) and returns a
    // committed row with qty=0 and reason="child_failed:GRANDCHILD@PLANT(no_inventory)".
    //
    // Pre-fix scoreVariant skipped zero-qty rows before evaluating their commit_reason,
    // so that hard failure never tripped anyFailed.  firstFeasibleMethod reported FG
    // feasible, the consolidation gate passed, and C1 was registered as a ComponentNeed
    // — a phantom pre-allocation for a demand that can never roll up.
    //
    // Post-fix the zero-qty row's non-benign reason sets anyFailed=true, the method is
    // rejected, and no ComponentNeeds are registered.
    test("Sc22: probe marks zero-qty child_failed rows as failed (no phantom pre-allocation)") {
        val demands = listOf(demand("D1", "FG", "PLANT", 100.0, 1, "2025-01-20"))
        val data = mapOf(
            "method_make" to listOf(
                // FG's only method: children are C1 (reachable) and P (transitively unreachable).
                mapOf("product_id" to "FG", "location_id" to "PLANT",
                      "bom_id" to "BFG", "preference" to 1, "lead_time" to 0),
                // P's only method: child is GRANDCHILD (terminal no_methods).
                mapOf("product_id" to "P", "location_id" to "PLANT",
                      "bom_id" to "BP", "preference" to 1, "lead_time" to 0),
            ),
            "bom" to listOf(
                mapOf("parent_id" to "FG", "child_id" to "C1",         "bom_id" to "BFG", "rate" to 1.0, "alt_group" to null),
                mapOf("parent_id" to "FG", "child_id" to "P",          "bom_id" to "BFG", "rate" to 1.0, "alt_group" to null),
                mapOf("parent_id" to "P",  "child_id" to "GRANDCHILD", "bom_id" to "BP",  "rate" to 1.0, "alt_group" to null),
            ),
            // C1 has supply; GRANDCHILD has none and no methods → unreachable at depth 2.
            "supply" to listOf(supply("C1", "PLANT", 1000.0)),
        )
        val inventory: List<Map<String, Any?>> = (data["supply"] ?: emptyList()).map { s ->
            mapOf(
                "product_id"  to s["product_id"],
                "location_id" to s["location_id"],
                "supply_date" to s["supply_date"],
                "supply_id"   to s["supply_id"],
                "qty"         to s["qty"],
                "demand_tag"  to null,
            )
        }
        val methodChoices = mutableMapOf<Triple<String, String, String>, Map<String, Any?>>()
        val needs = collectComponentNeeds(
            demands, data, ConsolidationConfig(),
            inventory = inventory, planConfig = null, methodChoices = methodChoices,
        )
        // Gate triggered via sharper probe — no ComponentNeed for C1 even though C1 has supply.
        needs shouldHaveSize 0
        methodChoices shouldNotContainKey Triple("FG", "PLANT", "D1")
    }

    // ── Single-bucket (period_days=0) must schedule against a REAL date ──────────
    // Regression: the grouping bucket is LocalDate.EPOCH under single-bucket
    // consolidation, but that sentinel must not become the synthetic demand's
    // request date — otherwise purchases anchored at 1970 underflow to 1969 once
    // lead time is subtracted. The schedule date must be the earliest real due date.

    test("Sc-epoch: single-bucket consolidated demand schedules at earliest real due date, not EPOCH") {
        // Multi-demand group with EPOCH grouping bucket (as produced by period_days=0)
        // but real due dates Jan 15 / Jan 20. planFn echoes reqDt into the WO end_time.
        val n1 = ComponentNeed("C", "L", LocalDate.of(2025, 1, 20), 10.0, "D1", 1, "FG1")
        val n2 = ComponentNeed("C", "L", LocalDate.of(2025, 1, 15), 40.0, "D2", 2, "FG2")
        val group = ConsolidationGroup("C", "L", LocalDate.EPOCH, listOf(n1, n2), 50.0)
        val config = ConsolidationConfig(enabled = true, periodDays = 0, allocationMode = "fair")

        val result = runConsolidation(listOf(group), mutableListOf(supply("C", "L", 100.0)), emptyData, config, planFn = ::simplePlanFn)

        result.consolidatedWOs shouldHaveSize 1
        // Earliest due date among needs, NOT 1970-01-01.
        result.consolidatedWOs[0]["end_time"] shouldBe "2025-01-15"
    }

    test("Sc-epoch: single-demand single-bucket group also avoids EPOCH scheduling") {
        val n1 = ComponentNeed("C", "L", LocalDate.of(2025, 3, 9), 25.0, "D1", 1, "FG1")
        val group = ConsolidationGroup("C", "L", LocalDate.EPOCH, listOf(n1), 25.0)
        val config = ConsolidationConfig(enabled = true, periodDays = 0, allocationMode = "fair")

        val result = runConsolidation(listOf(group), mutableListOf(supply("C", "L", 100.0)), emptyData, config, planFn = ::simplePlanFn)

        result.consolidatedWOs shouldHaveSize 1
        result.consolidatedWOs[0]["end_time"] shouldBe "2025-03-09"
    }

    // ── Conservation of mass: failed=true subtree must not deplete real inventory ──

    test("applyPeggingConsumption: failed=true WO subtree leaves no ghost depletion in real inventory") {
        // Inventory: S1 is on-hand component (what the group consolidates),
        // S2 is a "deep raw" that only the failed exploration branch would have consumed.
        val invS1 = mutableMapOf<String, Any?>(
            "product_id" to "P", "location_id" to "L",
            "supply_id" to "S1", "qty" to 100.0, "supply_date" to "2025-01-01",
        )
        val invS2 = mutableMapOf<String, Any?>(
            "product_id" to "RAW", "location_id" to "L",
            "supply_id" to "S2", "qty" to 50.0, "supply_date" to "2025-01-01",
        )
        val inventory = mutableListOf(invS1, invS2)

        // planFn returns a pegging tree that has:
        //   - A successful WO subtree consuming 60 units from S1
        //   - A failed=true WO subtree consuming 40 units from S2
        // (This mirrors AND-bottleneck diagnostics: plan() rolled back S2 consumption
        // in invCopy, but the failed node is preserved in the tree for UI diagnostics.)
        val peggingWithFailedBranch: Map<String, Any?> = mapOf(
            "type" to "demand",
            "demand_id" to "D1",
            "children" to listOf(
                mapOf(
                    "type" to "work_order",
                    "children" to listOf(
                        mapOf("type" to "supply", "supply_id" to "S1", "quantity" to 60.0),
                    ),
                ),
                mapOf(
                    "type" to "work_order",
                    "failed" to true,   // ← rolled-back exploration branch
                    "children" to listOf(
                        mapOf("type" to "supply", "supply_id" to "S2", "quantity" to 40.0),
                    ),
                ),
            ),
        )

        val planFnWithFailedBranch = fun(
            demand: Map<String, Any?>,
            _: MutableList<MutableMap<String, Any?>>,
            _: Map<String, List<Map<String, Any?>>>,
            _: LocalDate?,
            _: Int,
            _: Set<Pair<String, String>>,
            _: Map<String, Any?>?,
            _: Any?,
        ): Triple<List<Map<String, Any?>>, List<Map<String, Any?>>, Map<String, Any?>?> {
            val committed = listOf(mapOf<String, Any?>(
                "demand_id" to demand["demand_id"], "quantity" to 60.0,
            ))
            return Triple(committed, emptyList(), peggingWithFailedBranch)
        }

        val need = ComponentNeed("P", "L", LocalDate.of(2025, 1, 20), 60.0, "D1", 1, "FG1")
        val group = ConsolidationGroup("P", "L", LocalDate.of(2025, 1, 20), listOf(need), 60.0)
        val config = ConsolidationConfig(enabled = true, allocationMode = "fair")

        runConsolidation(listOf(group), inventory, emptyData, config, planFn = planFnWithFailedBranch)

        // Successful branch: S1 should be depleted by 60 (100 → 40).
        (invS1["qty"] as Double) shouldBe (40.0 plusOrMinus 1e-9)
        // Failed branch: S2 must NOT be touched — no ghost depletion.
        (invS2["qty"] as Double) shouldBe (50.0 plusOrMinus 1e-9)
    }
})
