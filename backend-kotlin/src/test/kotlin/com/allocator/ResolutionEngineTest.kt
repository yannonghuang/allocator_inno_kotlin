package com.allocator

import com.allocator.services.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [ResolutionEngine] — Phase 1 of the plan-then-consolidate refactor.
 *
 * Phase 1 builds a [ResolutionGraph] (one path per demand → leaf) and a [BomAncestry]
 * index. It walks BOM/methods inventory-blind so resolution is deterministic across
 * iterations. The graph + ancestry feed Phase 2's merge step.
 */
class ResolutionEngineTest : FunSpec({

    // ── Fixtures ──────────────────────────────────────────────────────────────

    fun bom(parent: String, child: String, rate: Double, altGroup: String? = null, bomId: String = "BOM_$parent"): Map<String, Any?> =
        mapOf("bom_id" to bomId, "parent_id" to parent, "child_id" to child, "rate" to rate, "alt_group" to altGroup)

    fun mk(productId: String, locationId: String, preference: Int = -1, leadTime: Double = 0.0, bomId: String = "BOM_$productId"): Map<String, Any?> =
        mapOf("bom_id" to bomId, "product_id" to productId, "location_id" to locationId, "preference" to preference, "lead_time" to leadTime, "type" to "make")

    fun supply(productId: String, locationId: String, qty: Double, supplyId: String): Map<String, Any?> = mapOf(
        "supply_id" to supplyId, "product_id" to productId, "location_id" to locationId, "supply_date" to "2024-01-01", "qty" to qty,
    )

    fun demand(id: String, productId: String, locationId: String, qty: Double, due: String = "2024-12-31", priority: Int = 0): Map<String, Any?> = mapOf(
        "demand_id" to id, "product_id" to productId, "location_id" to locationId,
        "quantity" to qty, "request_due_time" to due, "request_time" to due,
        "priority" to priority,
    )

    // ── BomAncestry ──────────────────────────────────────────────────────────

    test("BomAncestry: identity returns 1.0") {
        val ancestry = BomAncestry(emptyList())
        ancestry.cumulativeRate("X", "X") shouldBe 1.0
    }

    test("BomAncestry: single-edge rate") {
        val ancestry = BomAncestry(listOf(bom("A", "B", 3.0)))
        ancestry.cumulativeRate("A", "B")!!.shouldBe(3.0 plusOrMinus 1e-9)
        ancestry.cumulativeRate("B", "A").shouldBeNull()
    }

    test("BomAncestry: multi-level chain multiplies rates") {
        val ancestry = BomAncestry(listOf(
            bom("FG",  "SUB", 2.0),
            bom("SUB", "RAW", 5.0),
        ))
        ancestry.cumulativeRate("FG",  "RAW")!!.shouldBe(10.0 plusOrMinus 1e-9)
        ancestry.cumulativeRate("FG",  "SUB")!!.shouldBe(2.0  plusOrMinus 1e-9)
        ancestry.cumulativeRate("SUB", "RAW")!!.shouldBe(5.0  plusOrMinus 1e-9)
    }

    test("BomAncestry: isAncestor disambiguates direction") {
        val ancestry = BomAncestry(listOf(bom("A", "B", 1.0)))
        ancestry.isAncestor("A", "B") shouldBe true
        ancestry.isAncestor("B", "A") shouldBe false
        ancestry.isAncestor("A", "A") shouldBe false  // identity is not "ancestor"
    }

    test("BomAncestry: unconnected pair returns null") {
        val ancestry = BomAncestry(listOf(bom("A", "B", 1.0), bom("C", "D", 1.0)))
        ancestry.cumulativeRate("A", "D").shouldBeNull()
    }

    // ── ResolutionGraph: stop rule ───────────────────────────────────────────

    test("buildResolutionGraph: stops at first inventory-bearing (pid, lid)") {
        // FG → SUB → RAW; only SUB has supply. The walk should stop at SUB.
        val data = mapOf(
            "bom" to listOf(
                bom("FG",  "SUB", 1.0),
                bom("SUB", "RAW", 2.0),
            ),
            "method_make" to listOf(
                mk("FG",  "L1"),
                mk("SUB", "L1"),
                mk("RAW", "L1"),  // would be reachable but should never be visited
            ),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(supply("SUB", "L1", 100.0, "S_SUB")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val graph = buildResolutionGraph(demands, data)
        graph.paths shouldHaveSize 1
        val path = graph.paths[0]
        path.leaf.productId shouldBe "SUB"
        path.leaf.locationId shouldBe "L1"
        path.leaf.cumulativeRate shouldBe (1.0 plusOrMinus 1e-9)
        path.leafQuantity() shouldBe (10.0 plusOrMinus 1e-9)  // 10 FG × rate 1.0 to SUB
        path.nodes.map { it.productId } shouldBe listOf("FG", "SUB")
    }

    test("buildResolutionGraph: descends past intermediate when no supply there") {
        // FG → SUB → RAW; only RAW has supply.
        val data = mapOf(
            "bom" to listOf(
                bom("FG",  "SUB", 1.0),
                bom("SUB", "RAW", 2.0),
            ),
            "method_make" to listOf(
                mk("FG",  "L1"),
                mk("SUB", "L1"),
            ),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(supply("RAW", "L1", 100.0, "S_RAW")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val graph = buildResolutionGraph(demands, data)
        graph.paths shouldHaveSize 1
        val path = graph.paths[0]
        path.leaf.productId shouldBe "RAW"
        path.leafQuantity() shouldBe (20.0 plusOrMinus 1e-9)  // 10 × 1 × 2 = 20
        path.nodes.map { it.productId } shouldBe listOf("FG", "SUB", "RAW")
    }

    test("buildResolutionGraph: rate-multiplied leaf quantity is correct") {
        val data = mapOf(
            "bom" to listOf(
                bom("FG",  "SUB", 3.0),
                bom("SUB", "RAW", 5.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("SUB", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(supply("RAW", "L1", 1000.0, "S_RAW")),
        )
        val demands = listOf(demand("D1", "FG", "L1", 7.0))

        val graph = buildResolutionGraph(demands, data)
        graph.paths shouldHaveSize 1
        graph.paths[0].leafQuantity() shouldBe (7.0 * 3.0 * 5.0 plusOrMinus 1e-9)  // 105
    }

    // ── ResolutionGraph: multi-component BOM ─────────────────────────────────

    test("buildResolutionGraph: emits one path per alt_group child") {
        // FG has two BOM children (different alt_groups → both required).
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "C1", 2.0, altGroup = "g1"),
                bom("FG", "C2", 4.0, altGroup = "g2"),
            ),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(
                supply("C1", "L1", 100.0, "S_C1"),
                supply("C2", "L1", 100.0, "S_C2"),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val graph = buildResolutionGraph(demands, data)
        graph.paths shouldHaveSize 2
        val byLeaf = graph.paths.associate { it.leaf.productId to it.leafQuantity() }
        byLeaf["C1"]!! shouldBe (20.0 plusOrMinus 1e-9)
        byLeaf["C2"]!! shouldBe (40.0 plusOrMinus 1e-9)
    }

    test("buildResolutionGraph: OR alt_group enumerates every child (union-alt)") {
        // FG → (C1 OR C2): same alt_group; resolution emits one path per alternative
        // so consolidation can form merged groups at every candidate leaf. Phase 3
        // picks the actual alt at runtime; the cap loop drives unpicked alts to zero.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "C1", 1.0, altGroup = "or1"),
                bom("FG", "C2", 1.0, altGroup = "or1"),
            ),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(
                supply("C1", "L1", 100.0, "S_C1"),
                supply("C2", "L1", 100.0, "S_C2"),
            ),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))

        val graph = buildResolutionGraph(demands, data)
        graph.paths shouldHaveSize 2
        val byLeaf = graph.paths.associate { it.leaf.productId to it.leafQuantity() }
        byLeaf["C1"]!! shouldBe (10.0 plusOrMinus 1e-9)
        byLeaf["C2"]!! shouldBe (10.0 plusOrMinus 1e-9)
    }

    // ── Inventory-blindness ──────────────────────────────────────────────────

    test("buildResolutionGraph: ignores inventory levels (does not run cascade probe)") {
        // Two demands competing for the same SUB whose supply is too small for both. Legacy's
        // cascade probe might steer the second demand to a different alternative; v2 must NOT —
        // both demands resolve identically inventory-blind.
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "SUB", 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(supply("SUB", "L1", 5.0, "S_SUB")),  // tiny — would trigger cascade
        )
        val demands = listOf(
            demand("D1", "FG", "L1", 100.0, priority = 10),
            demand("D2", "FG", "L1", 100.0, priority = 20),
        )

        val graph = buildResolutionGraph(demands, data)
        graph.paths shouldHaveSize 2
        graph.paths.map { it.leaf.productId }.toSet() shouldBe setOf("SUB")
        graph.paths.map { it.demandId } shouldContainExactlyInAnyOrder listOf("D1", "D2")
    }

    // ── ComponentNeed conversion ─────────────────────────────────────────────

    test("toComponentNeeds: produces one need per resolved leaf") {
        val data = mapOf(
            "bom" to listOf(
                bom("FG", "SUB", 2.0),
            ),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(supply("SUB", "L1", 1000.0, "S_SUB")),
        )
        val demands = listOf(
            demand("D1", "FG", "L1", 10.0, priority = 100),
            demand("D2", "FG", "L1", 30.0, priority = 200),
        )

        val needs = buildResolutionGraph(demands, data).toComponentNeeds()
        needs shouldHaveSize 2
        val byDid = needs.associateBy { it.demandId }
        byDid["D1"]!!.let { n ->
            n.productId shouldBe "SUB"
            n.locationId shouldBe "L1"
            n.qty shouldBe (20.0 plusOrMinus 1e-9)
            n.priority shouldBe 100
            n.parentProductId shouldBe "FG"
            n.viaOrAlternative shouldBe false
        }
        byDid["D2"]!!.qty shouldBe (60.0 plusOrMinus 1e-9)
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    test("buildResolutionGraph: demand with no methods produces no path") {
        val data = mapOf(
            "bom"         to emptyList<Map<String, Any?>>(),
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))
        val graph = buildResolutionGraph(demands, data)
        graph.paths.shouldBeEmpty()
    }

    test("buildResolutionGraph: cycle in BOM is dropped silently") {
        // FG → SUB → FG (cycle); no supply. The cycle visited-set must prevent infinite recursion.
        val data = mapOf(
            "bom" to listOf(
                bom("FG",  "SUB", 1.0),
                bom("SUB", "FG",  1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("SUB", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(demand("D1", "FG", "L1", 10.0))
        val graph = buildResolutionGraph(demands, data)
        // Should not loop forever; should produce no leaves (no terminal supply or purchase).
        graph.paths.shouldBeEmpty()
    }

    // ── Merge step ───────────────────────────────────────────────────────────

    test("mergeGroups: distinct leaves with no ancestry stay as separate groups") {
        // Two demands resolve to two different leaves; neither is an ancestor of the other.
        val data = mapOf(
            "bom"         to emptyList<Map<String, Any?>>(),
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(
                supply("A", "L1", 100.0, "S_A"),
                supply("B", "L1", 100.0, "S_B"),
            ),
        )
        val demands = listOf(
            demand("D1", "A", "L1", 10.0),
            demand("D2", "B", "L1", 20.0),
        )
        val graph = buildResolutionGraph(demands, data)
        val ancestry = BomAncestry(emptyList())
        val merged = mergeGroups(graph, ancestry, periodDays = 0)
        merged shouldHaveSize 2
        merged.map { it.leafPid }.toSet() shouldBe setOf("A", "B")
    }

    test("mergeGroups: linear ancestry collapses shallow group into deepest descendant") {
        // BOM: SHALLOW → DEEP (rate 3). Demand D1 resolves to SHALLOW, D2 to DEEP.
        // Expectation: only one group survives, at DEEP, with both members present.
        val bomRows = listOf(bom("SHALLOW", "DEEP", 3.0))
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(mk("SHALLOW", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("SHALLOW", "L1", 100.0, "S_SHALLOW"),
                supply("DEEP",    "L1", 100.0, "S_DEEP"),
            ),
        )
        val demands = listOf(
            demand("D1", "SHALLOW", "L1", 10.0, priority = 100),
            demand("D2", "DEEP",    "L1", 5.0,  priority = 200),
        )
        val graph = buildResolutionGraph(demands, data)
        val ancestry = BomAncestry(bomRows)
        val merged = mergeGroups(graph, ancestry, periodDays = 0)
        merged shouldHaveSize 1
        val g = merged[0]
        g.leafPid shouldBe "DEEP"
        g.members shouldHaveSize 2
        // D1 promoted from SHALLOW to DEEP at rate 3 → 10 × 3 = 30.
        // D2 stays as itself (qty 5).
        val byDid = g.members.associateBy { it.demandId }
        byDid["D1"]!!.qty shouldBe (30.0 plusOrMinus 1e-9)
        byDid["D1"]!!.originalLeafPid shouldBe "SHALLOW"
        byDid["D1"]!!.promotionRate shouldBe (3.0 plusOrMinus 1e-9)
        byDid["D2"]!!.qty shouldBe (5.0 plusOrMinus 1e-9)
        byDid["D2"]!!.originalLeafPid shouldBe "DEEP"
        byDid["D2"]!!.promotionRate shouldBe (1.0 plusOrMinus 1e-9)
        g.totalQty shouldBe (35.0 plusOrMinus 1e-9)
    }

    test("mergeGroups: chain A→B→C with all three as groups → all collapse to C") {
        // BOM: A → B (rate 2) → C (rate 5). All three have supply, three demands one per leaf.
        val bomRows = listOf(
            bom("A", "B", 2.0),
            bom("B", "C", 5.0),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(mk("A", "L1"), mk("B", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("A", "L1", 100.0, "S_A"),
                supply("B", "L1", 100.0, "S_B"),
                supply("C", "L1", 100.0, "S_C"),
            ),
        )
        val demands = listOf(
            demand("DA", "A", "L1", 1.0),
            demand("DB", "B", "L1", 1.0),
            demand("DC", "C", "L1", 1.0),
        )
        val merged = mergeGroups(buildResolutionGraph(demands, data), BomAncestry(bomRows), periodDays = 0)
        merged shouldHaveSize 1
        val g = merged[0]
        g.leafPid shouldBe "C"
        val byDid = g.members.associateBy { it.demandId }
        // DA → C at rate 2*5 = 10 → 1 × 10 = 10.
        byDid["DA"]!!.qty shouldBe (10.0 plusOrMinus 1e-9)
        // DB → C at rate 5 → 1 × 5 = 5.
        byDid["DB"]!!.qty shouldBe (5.0 plusOrMinus 1e-9)
        // DC stays as itself → 1.
        byDid["DC"]!!.qty shouldBe (1.0 plusOrMinus 1e-9)
        g.totalQty shouldBe (16.0 plusOrMinus 1e-9)
    }

    test("mergeGroups: diamond branching split-promotes shallow into both descendants") {
        // BOM: A → B (rate 2) AND A → C (rate 4); B and C are non-comparable.
        val bomRows = listOf(
            bom("A", "B", 2.0, altGroup = "g1"),
            bom("A", "C", 4.0, altGroup = "g2"),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(mk("A", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("A", "L1", 100.0, "S_A"),
                supply("B", "L1", 100.0, "S_B"),
                supply("C", "L1", 100.0, "S_C"),
            ),
        )
        val demands = listOf(
            demand("DA", "A", "L1", 1.0),
            demand("DB", "B", "L1", 7.0),
            demand("DC", "C", "L1", 11.0),
        )
        val merged = mergeGroups(buildResolutionGraph(demands, data), BomAncestry(bomRows), periodDays = 0)
        // Two surviving groups (B and C); A gets split-promoted into both.
        merged shouldHaveSize 2
        val byLeaf = merged.associateBy { it.leafPid }
        // B group: original DB (7) + promoted DA (1 × rate 2 = 2) = 9.
        byLeaf["B"]!!.totalQty shouldBe (9.0 plusOrMinus 1e-9)
        byLeaf["B"]!!.members.map { it.demandId }.toSet() shouldBe setOf("DA", "DB")
        // C group: original DC (11) + promoted DA (1 × rate 4 = 4) = 15.
        byLeaf["C"]!!.totalQty shouldBe (15.0 plusOrMinus 1e-9)
        byLeaf["C"]!!.members.map { it.demandId }.toSet() shouldBe setOf("DA", "DC")
    }

    test("mergeGroups: ancestry across DIFFERENT time buckets does NOT merge") {
        // Same BOM SHALLOW→DEEP, but demands due in different periods so they fall into
        // different time buckets. Each bucket has only its own group → no merge.
        val bomRows = listOf(bom("SHALLOW", "DEEP", 2.0))
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(mk("SHALLOW", "L1")),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("SHALLOW", "L1", 100.0, "S_S"),
                supply("DEEP",    "L1", 100.0, "S_D"),
            ),
        )
        val demands = listOf(
            demand("D1", "SHALLOW", "L1", 10.0, due = "2024-01-15"),
            demand("D2", "DEEP",    "L1", 5.0,  due = "2024-12-15"),
        )
        val merged = mergeGroups(buildResolutionGraph(demands, data), BomAncestry(bomRows), periodDays = 30)
        // Different buckets → both groups survive.
        merged shouldHaveSize 2
        merged.map { it.leafPid }.toSet() shouldBe setOf("SHALLOW", "DEEP")
    }

    test("mergeGroups: empty graph yields empty list") {
        val merged = mergeGroups(ResolutionGraph(emptyList()), BomAncestry(emptyList()), periodDays = 0)
        merged.shouldBeEmpty()
    }

    // ── End-to-end: runPlanning with engine = v2 ────────────────────────────

    test("runPlanning v2: multi-level scenario produces ONE consolidation group at deepest shared component") {
        // FG_A → 500-5391 → 502-2588; FG_B → 502-2588 (direct).
        // Only 502-2588 carries supply — DA's chain must traverse all the way to the
        // deepest shared component, so both DA and DB compete at 502-2588 in phase 3.
        // Legacy v1 produces two groups; v2 must produce one consolidation group at
        // 502-2588 with both demands as members (and Stage 4b iteration must NOT trim
        // either out, since both actually consume at the leaf).
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_A",   "parent_id" to "FG_A",     "child_id" to "500-5391", "rate" to 1.0,  "alt_group" to null),
            mapOf("bom_id" to "BOM_5391","parent_id" to "500-5391", "child_id" to "502-2588", "rate" to 1.0,  "alt_group" to null),
            mapOf("bom_id" to "BOM_B",   "parent_id" to "FG_B",     "child_id" to "502-2588", "rate" to 1.0,  "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A",    "product_id" to "FG_A",     "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B",    "product_id" to "FG_B",     "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_5391", "product_id" to "500-5391", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("502-2588", "L1", 60.0, "S_2588"),
            ),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )

        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to false,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        @Suppress("UNCHECKED_CAST")
        val pegging = result["planning_pegging"] as List<Map<String, Any?>>
        // Find consolidated entries (engine output for the merged group).
        val consolidatedEntries = pegging.filter { it["consolidated"] == true }
        // Exactly one consolidated group — the merged 502-2588 group containing both demands.
        consolidatedEntries shouldHaveSize 1
        val entry = consolidatedEntries[0]
        @Suppress("UNCHECKED_CAST")
        val tree = entry["tree"] as Map<String, Any?>
        tree["product_id"] shouldBe "502-2588"
        @Suppress("UNCHECKED_CAST")
        val sharers = entry["consolidated_demand_ids"] as List<Any?>
        sharers.toSet() shouldBe setOf("DA", "DB")
    }

    test("buildResolutionGraph: purchase method is a leaf") {
        val data = mapOf(
            "bom"         to emptyList<Map<String, Any?>>(),
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy"  to listOf(mapOf(
                "product_id" to "RAW", "location_id" to "L1",
                "preference" to -1, "lead_time" to 0.0, "type" to "purchase",
            )),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(demand("D1", "RAW", "L1", 50.0))
        val graph = buildResolutionGraph(demands, data)
        graph.paths shouldHaveSize 1
        val path = graph.paths[0]
        path.leaf.productId shouldBe "RAW"
        path.leaf.isLeaf shouldBe true
        path.leaf.method.shouldNotBeNull()
        (path.leaf.method!!["type"]) shouldBe "purchase"
    }

    // ── Stage 3: budget-driven plan() ────────────────────────────────────────

    test("plan: budget caps consumption at the merged-leaf component") {
        // 100 supply at (RAW, L1); a demand of 50 with a budget of 30 must take only 30,
        // leaving 70 in inventory. Budget map is decremented to 0.
        val data = mapOf(
            "bom"         to emptyList<Map<String, Any?>>(),
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(supply("RAW", "L1", 100.0, "S1")),
        )
        val inventory = mutableListOf(
            mutableMapOf<String, Any?>(
                "product_id" to "RAW", "location_id" to "L1",
                "supply_date" to "2024-01-01", "supply_id" to "S1", "qty" to 100.0,
            )
        )
        val budget: MutableMap<String, Double> = mutableMapOf("RAW|L1" to 30.0)
        val (committed, _, _) = plan(
            demand = demand("D1", "RAW", "L1", 50.0),
            inventory = inventory,
            data = data,
            requestTimeDt = null,
            budget = budget,
        )
        // Only 30 came from inventory (the rest fell through to "no_methods" since RAW has no make/buy method).
        val inventoryCommitted = committed
            .filter { (it["commit_reason"] as? String) == "inventory" }
            .sumOf { (it["quantity"] as Number).toDouble() }
        inventoryCommitted shouldBe (30.0 plusOrMinus 1e-9)
        // 70 remains in inventory (only 30 consumed).
        (inventory[0]["qty"] as Number).toDouble() shouldBe (70.0 plusOrMinus 1e-9)
        // Budget exhausted.
        budget["RAW|L1"]!! shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("plan: budget = null preserves legacy unlimited-consumption behavior") {
        val data = mapOf(
            "bom"         to emptyList<Map<String, Any?>>(),
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"      to listOf(supply("RAW", "L1", 100.0, "S1")),
        )
        val inventory = mutableListOf(
            mutableMapOf<String, Any?>(
                "product_id" to "RAW", "location_id" to "L1",
                "supply_date" to "2024-01-01", "supply_id" to "S1", "qty" to 100.0,
            )
        )
        val (committed, _, _) = plan(
            demand = demand("D1", "RAW", "L1", 50.0),
            inventory = inventory,
            data = data,
            requestTimeDt = null,
            budget = null,
        )
        val committedQty = committed.sumOf { (it["quantity"] as Number).toDouble() }
        committedQty shouldBe (50.0 plusOrMinus 1e-9)
    }

    test("runPlanning v2: emits NO demand-tagged synthetic supply IDs") {
        // v2 must not produce supply_ids of the form `consolidated_${demandId}_${pid}` —
        // those are the v1 tagged-synthetic signature. v2 emits one untagged bucket per
        // (pid, lid) named `consolidated_${pid}_${lid}`.
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_A",   "parent_id" to "FG_A", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_B",   "parent_id" to "FG_B", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG_A", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG_B", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RAW", "L1", 100.0, "S_RAW")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )
        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to false,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        // Walk every pegging tree and collect every supply_id observed at supply nodes.
        @Suppress("UNCHECKED_CAST")
        val pegging = result["planning_pegging"] as List<Map<String, Any?>>
        val seenSupplyIds = mutableSetOf<String>()
        fun collect(node: Map<String, Any?>) {
            val sid = node["supply_id"] as? String
            if (!sid.isNullOrBlank()) seenSupplyIds.add(sid)
            @Suppress("UNCHECKED_CAST")
            (node["children"] as? List<Map<String, Any?>>)?.forEach { collect(it) }
        }
        for (entry in pegging) {
            @Suppress("UNCHECKED_CAST")
            (entry["tree"] as? Map<String, Any?>)?.let { collect(it) }
        }

        // No tagged synthetic IDs in v2 mode (the old v1 pattern was `consolidated_<demandId>_<pid>`).
        val taggedPattern = Regex("^consolidated_(DA|DB)_.*")
        seenSupplyIds.none { taggedPattern.matches(it) } shouldBe true
    }

    test("runPlanning v2: per-demand allocation respected at merged leaf via budget") {
        // RAW supply = 10 (tight). FG_A, FG_B both need 10 → 20 total, allocation_mode=fair
        // splits the 10 RAW evenly → each demand gets 5. v2 caps each demand at 5 via budget,
        // not via demand_tagged buckets.
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_A", "parent_id" to "FG_A", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_B", "parent_id" to "FG_B", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG_A", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG_B", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RAW", "L1", 10.0, "S_RAW")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )
        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to false,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        // Each demand gets a fair 5 of the RAW supply (no demand monopolizes via FIFO).
        @Suppress("UNCHECKED_CAST")
        val committed = result["committed_demands"] as List<Map<String, Any?>>
        val byDemand = committed
            .filter { (it["commit_reason"] as? String) != "no_methods" }
            .groupBy { it["demand_id"] as String }
            .mapValues { (_, rows) ->
                rows.sumOf { r ->
                    val reason = r["commit_reason"] as? String
                    if (reason == "cycle_stopped" || reason == "cycle_detected") 0.0
                    else (r["quantity"] as? Number)?.toDouble() ?: 0.0
                }
            }
        // 10 RAW / 2 demands = 5 each. Both demands should report ~5.0.
        (byDemand["DA"] ?: 0.0) shouldBe (5.0 plusOrMinus 1e-6)
        (byDemand["DB"] ?: 0.0) shouldBe (5.0 plusOrMinus 1e-6)
    }

    // ── Stage 4a: passive over-production trim ───────────────────────────────

    test("runPlanning v2: consolidated WO is right-sized when chain consumes from intermediate supply") {
        // FG_A → 500-5391 → 502-2588 (multi-level); FG_B → 502-2588 (direct).
        // 500-5391 has 5 supply at L1 (intermediate); 502-2588 is purchasable (must
        // consolidate-and-buy). DA(FG_A)=10 → at 500-5391 the chain consumes 5 from
        // intermediate supply, then recurses for 5 more at 502-2588 (consumes 5 of leaf
        // budget). DB(FG_B)=10 takes its full 10 budget. Total consumed at 502-2588 = 15.
        // Phase-2 sized the consolidated purchase at 20. Stage 4a trims 20 → 15.
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_A",   "parent_id" to "FG_A",     "child_id" to "500-5391", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_5391","parent_id" to "500-5391", "child_id" to "502-2588", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_B",   "parent_id" to "FG_B",     "child_id" to "502-2588", "rate" to 1.0, "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A",    "product_id" to "FG_A",     "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B",    "product_id" to "FG_B",     "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_5391", "product_id" to "500-5391", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to listOf(mapOf(
                "product_id" to "502-2588", "location_id" to "L1",
                "preference" to -1, "lead_time" to 0.0, "type" to "purchase",
            )),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("500-5391", "L1", 5.0, "S_5391")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )
        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to true,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        @Suppress("UNCHECKED_CAST")
        val workOrders = result["work_orders"] as List<Map<String, Any?>>
        // Find the consolidated WO(s) at 502-2588. Multiple lots may have been emitted
        // if lot_size constraint applies; sum across all consolidated lots.
        val consolidatedAt2588 = workOrders.filter {
            it["product_id"] == "502-2588" && it["location_id"] == "L1" && it["consolidated"] == true
        }
        val totalQty = consolidatedAt2588.sumOf { (it["quantity"] as Number).toDouble() }
        // 5 of DA's 502-2588 budget went unused (DA consumed 5 from 500-5391 supply instead).
        // Trim: 20 → 15.
        totalQty shouldBe (15.0 plusOrMinus 1e-6)
    }

    test("runPlanning v2: untagged supply is trimmed alongside the consolidated WO") {
        // Same scenario as the right-sizing test. After Stage 4a, both demands commit
        // fully (10 each); the system did not need to over-produce at the leaf despite
        // phase-2 originally sizing the WO at 20.
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_A",   "parent_id" to "FG_A",     "child_id" to "500-5391", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_5391","parent_id" to "500-5391", "child_id" to "502-2588", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_B",   "parent_id" to "FG_B",     "child_id" to "502-2588", "rate" to 1.0, "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A",    "product_id" to "FG_A",     "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B",    "product_id" to "FG_B",     "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_5391", "product_id" to "500-5391", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to listOf(mapOf(
                "product_id" to "502-2588", "location_id" to "L1",
                "preference" to -1, "lead_time" to 0.0, "type" to "purchase",
            )),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("500-5391", "L1", 5.0, "S_5391")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )
        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to true,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        @Suppress("UNCHECKED_CAST")
        val committed = result["committed_demands"] as List<Map<String, Any?>>
        val daCommitted = committed
            .filter { it["demand_id"] == "DA" && (it["commit_reason"] as? String) != "no_methods" }
            .sumOf { (it["quantity"] as Number).toDouble() }
        val dbCommitted = committed
            .filter { it["demand_id"] == "DB" && (it["commit_reason"] as? String) != "no_methods" }
            .sumOf { (it["quantity"] as Number).toDouble() }
        daCommitted shouldBe (10.0 plusOrMinus 1e-6)
        dbCommitted shouldBe (10.0 plusOrMinus 1e-6)
    }

    test("runPlanning v2: full budget consumption is a no-op for reconciliation") {
        // Single-level merge with both demands directly on RAW (purchasable). Each demand
        // consumes its full budget through the chain. reconcileOverProduction should
        // detect zero leftover and not modify any WO — total stays at 20.
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_A", "parent_id" to "FG_A", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_B", "parent_id" to "FG_B", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG_A", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG_B", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to listOf(mapOf(
                "product_id" to "RAW", "location_id" to "L1",
                "preference" to -1, "lead_time" to 0.0, "type" to "purchase",
            )),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )
        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to true,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        @Suppress("UNCHECKED_CAST")
        val workOrders = result["work_orders"] as List<Map<String, Any?>>
        val consolidatedAtRaw = workOrders.filter {
            it["product_id"] == "RAW" && it["location_id"] == "L1" && it["consolidated"] == true
        }
        // Sum across all consolidated lots at RAW.
        val totalQty = consolidatedAtRaw.sumOf { (it["quantity"] as Number).toDouble() }
        // No trim — total stays at full demand qty (20).
        totalQty shouldBe (20.0 plusOrMinus 1e-6)
    }

    // ── Stage 4b: fixed-point iteration / cascade trim ───────────────────────

    test("withMemberCaps: cap below current qty scales the member proportionally") {
        val members = listOf(
            MergedMember(demandId = "DA", qty = 10.0, priority = 0, dueDate = null,
                originalLeafPid = "X", originalLeafLid = "L1", promotionRate = 1.0),
            MergedMember(demandId = "DB", qty = 20.0, priority = 0, dueDate = null,
                originalLeafPid = "X", originalLeafLid = "L1", promotionRate = 1.0),
        )
        val g = MergedGroup("X", "L1", java.time.LocalDate.parse("2024-01-01"), members)
        val capped = g.withMemberCaps(mapOf("DA" to 4.0))  // DB uncapped
        val byDid = capped.members.associateBy { it.demandId }
        byDid["DA"]!!.qty shouldBe (4.0 plusOrMinus 1e-9)
        byDid["DB"]!!.qty shouldBe (20.0 plusOrMinus 1e-9)
        capped.totalQty shouldBe (24.0 plusOrMinus 1e-9)
    }

    test("withMemberCaps: cap of 0 drops the demand entirely") {
        val members = listOf(
            MergedMember("DA", 10.0, 0, null, "X", "L1", 1.0),
            MergedMember("DB", 10.0, 0, null, "X", "L1", 1.0),
        )
        val g = MergedGroup("X", "L1", java.time.LocalDate.parse("2024-01-01"), members)
        val capped = g.withMemberCaps(mapOf("DA" to 0.0))
        capped.members.map { it.demandId } shouldBe listOf("DB")
        capped.totalQty shouldBe (10.0 plusOrMinus 1e-9)
    }

    test("withMemberCaps: cap >= current sum is a no-op for that demand") {
        val members = listOf(MergedMember("DA", 10.0, 0, null, "X", "L1", 1.0))
        val g = MergedGroup("X", "L1", java.time.LocalDate.parse("2024-01-01"), members)
        val capped = g.withMemberCaps(mapOf("DA" to 99.0))
        capped.members.single().qty shouldBe (10.0 plusOrMinus 1e-9)
    }

    test("withMemberCaps: split-promoted demand is scaled proportionally across its members") {
        // Same demand contributes via two split-promoted members (different rates).
        val members = listOf(
            MergedMember("DA", 6.0,  0, null, "B", "L1", 2.0),
            MergedMember("DA", 12.0, 0, null, "C", "L1", 4.0),
            MergedMember("DB", 5.0,  0, null, "X", "L1", 1.0),
        )
        val g = MergedGroup("X", "L1", java.time.LocalDate.parse("2024-01-01"), members)
        // DA's current sum = 18; cap at 9 → scale factor 0.5.
        val capped = g.withMemberCaps(mapOf("DA" to 9.0))
        val daMembers = capped.members.filter { it.demandId == "DA" }
        daMembers shouldHaveSize 2
        daMembers.sumOf { it.qty } shouldBe (9.0 plusOrMinus 1e-9)
        daMembers.first { it.originalLeafPid == "B" }.qty shouldBe (3.0 plusOrMinus 1e-9)
        daMembers.first { it.originalLeafPid == "C" }.qty shouldBe (6.0 plusOrMinus 1e-9)
        // DB untouched.
        capped.members.first { it.demandId == "DB" }.qty shouldBe (5.0 plusOrMinus 1e-9)
    }

    test("runPlanning v2: cascade trim — sub-component WO is also right-sized after iteration") {
        // FG_A → DEEP; FG_B → DEEP; DEEP → RAW. DEEP is make-able (its make consumes RAW),
        // RAW is purchasable. Supply: FG_A=5, DEEP=1. DA's chain stops at FG_A (inventory),
        // so its merged-leaf budget at DEEP goes unused → phase 2 over-produces DEEP, which
        // CASCADES into an over-sized purchase at RAW. Stage 4a alone would trim DEEP but
        // leave RAW at 19; Stage 4b's iteration shrinks the merged-leaf qty in iter 1, which
        // re-runs phase 2 and naturally re-sizes the RAW purchase too.
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_FGA",  "parent_id" to "FG_A", "child_id" to "DEEP", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_FGB",  "parent_id" to "FG_B", "child_id" to "DEEP", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_DEEP", "parent_id" to "DEEP", "child_id" to "RAW",  "rate" to 1.0, "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_FGA",  "product_id" to "FG_A", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_FGB",  "product_id" to "FG_B", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_DEEP", "product_id" to "DEEP", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to listOf(mapOf(
                "product_id" to "RAW", "location_id" to "L1",
                "preference" to -1, "lead_time" to 0.0, "type" to "purchase",
            )),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("FG_A", "L1", 5.0, "S_FGA"),
                supply("DEEP", "L1", 1.0, "S_DEEP"),
            ),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )
        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to true,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        @Suppress("UNCHECKED_CAST")
        val workOrders = result["work_orders"] as List<Map<String, Any?>>

        // Iter 0 sized: DEEP make=19 (20 - 1 supply), RAW purchase=19.
        // Iter 1 sized: DEEP make=14 (15 - 1 supply), RAW purchase=14.
        // Stage 4a alone would trim DEEP→14 but leave RAW=19. Stage 4b cascade ⇒ both at 14.
        val consolidatedAtDeep = workOrders.filter {
            it["product_id"] == "DEEP" && it["location_id"] == "L1" && it["consolidated"] == true
        }.sumOf { (it["quantity"] as Number).toDouble() }
        val consolidatedAtRaw = workOrders.filter {
            it["product_id"] == "RAW" && it["location_id"] == "L1" && it["consolidated"] == true
        }.sumOf { (it["quantity"] as Number).toDouble() }

        consolidatedAtDeep shouldBe (14.0 plusOrMinus 1e-6)
        consolidatedAtRaw  shouldBe (14.0 plusOrMinus 1e-6)
    }

    test("runPlanning v2: iter-0-converged scenarios produce same result as before iteration") {
        // Single-level merge with both demands consuming fully at the merged leaf:
        // iteration runs once and exits on first pass with no over-production.
        val bomRows = listOf(
            mapOf("bom_id" to "BOM_A", "parent_id" to "FG_A", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
            mapOf("bom_id" to "BOM_B", "parent_id" to "FG_B", "child_id" to "RAW", "rate" to 1.0, "alt_group" to null),
        )
        val data = mapOf(
            "bom" to bomRows,
            "method_make" to listOf(
                mapOf("bom_id" to "BOM_A", "product_id" to "FG_A", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
                mapOf("bom_id" to "BOM_B", "product_id" to "FG_B", "location_id" to "L1", "preference" to -1, "lead_time" to 0.0),
            ),
            "method_buy"  to listOf(mapOf(
                "product_id" to "RAW", "location_id" to "L1",
                "preference" to -1, "lead_time" to 0.0, "type" to "purchase",
            )),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply"           to emptyList<Map<String, Any?>>(),
            "productlocation"  to emptyList<Map<String, Any?>>(),
            "overrides"        to emptyList<Map<String, Any?>>(),
        )
        val demands = listOf(
            demand("DA", "FG_A", "L1", 10.0),
            demand("DB", "FG_B", "L1", 10.0),
        )
        val configV2 = mapOf<String, Any?>(
            "purchase_allowed" to true,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 0, "engine" to "v2", "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )
        val result = runPlanning(data + ("demand" to demands), configV2)

        @Suppress("UNCHECKED_CAST")
        val committed = result["committed_demands"] as List<Map<String, Any?>>
        val daCommit = committed
            .filter { it["demand_id"] == "DA" && (it["commit_reason"] as? String) != "no_methods" }
            .sumOf { (it["quantity"] as Number).toDouble() }
        val dbCommit = committed
            .filter { it["demand_id"] == "DB" && (it["commit_reason"] as? String) != "no_methods" }
            .sumOf { (it["quantity"] as Number).toDouble() }
        daCommit shouldBe (10.0 plusOrMinus 1e-6)
        dbCommit shouldBe (10.0 plusOrMinus 1e-6)
    }
})
