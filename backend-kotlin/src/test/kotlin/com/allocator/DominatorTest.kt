package com.allocator

import com.allocator.services.DemandBlueprint
import com.allocator.services.DominatorRef
import com.allocator.services.NodeBlueprint
import com.allocator.services.plan
import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Behavior contract for quantity-dominator / time-dominator pointers — "least quantity
 * dominates, latest time dominates," captured inline at the planner's existing min/max
 * collapse points (services/PlanningEngine.kt) rather than computed by a separate pass.
 */
class DominatorTest : FunSpec({

    @Suppress("UNCHECKED_CAST")
    fun children(node: Map<String, Any?>?): List<Map<String, Any?>> =
        (node?.get("children") as? List<Map<String, Any?>>) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun dominatorEntries(node: Map<String, Any?>?, key: String): List<Map<String, Any?>> =
        (node?.get(key) as? List<Map<String, Any?>>) ?: emptyList()

    fun bomChild(bomId: String, child: String) =
        mapOf<String, Any?>("bom_id" to bomId, "parent_id" to "P", "child_id" to child, "alt_group" to null, "rate" to 1.0)
    fun demandP(qty: Double) = mapOf<String, Any?>(
        "demand_id" to "D1", "product_id" to "P", "location_id" to "L",
        "quantity" to qty, "request_due_time" to "2024-01-01",
    )
    // plan() takes `inventory` as its own live parameter — unlike runPlanning(), it does NOT
    // auto-convert data["supply"] into inventory buckets, so tests calling plan() directly must
    // build both.
    fun supplyRow(pid: String, qty: Double) =
        mapOf<String, Any?>("product_id" to pid, "location_id" to "L", "qty" to qty, "supply_id" to "S_$pid")
    fun inv(vararg rows: Map<String, Any?>): MutableList<MutableMap<String, Any?>> =
        rows.map { it.toMutableMap() }.toMutableList()

    test("AND quantity dominance: the scarcer BOM child is the WO's quantity_dominator") {
        // P = make(C1, C2), rate 1 each (a genuine AND — both required). Demand needs 20;
        // C1 only has 10 on hand (ratio 0.5), C2 has plenty (ratio 1.0) — C1 is the AND-min
        // bottleneck, so the parent WO should be capped to 10 and point at C1.
        val data = mapOf(
            "method_make" to listOf(mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(bomChild("B", "C1"), bomChild("B", "C2")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supplyRow("C1", 10.0), supplyRow("C2", 1000.0)),
        )
        val (_, _, tree) = plan(demandP(20.0), inv(supplyRow("C1", 10.0), supplyRow("C2", 1000.0)), data, requestTimeDt = null, config = mapOf("purchase_allowed" to false))
        val wo = children(tree).firstOrNull { it["type"] == "work_order" }
        wo shouldNotBe null
        val dominators = dominatorEntries(wo, "quantity_dominator")
        dominators.size shouldBe 1
        dominators[0]["product_id"] shouldBe "C1"
    }

    test("quantity_dominator label includes the supply_id — distinguishes same-material lots") {
        // Same fixture as above: C1's single raw-leaf lot ("S_C1") is the dominator. The label
        // must embed the supply_id, not just "product@location" — otherwise multiple distinct
        // physical lots of the same material (e.g. two receipts of C1 at different dates) render
        // as visually-identical "C1@L" entries with no way to tell them apart in the UI.
        val data = mapOf(
            "method_make" to listOf(mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(bomChild("B", "C1"), bomChild("B", "C2")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supplyRow("C1", 10.0), supplyRow("C2", 1000.0)),
        )
        val (_, _, tree) = plan(demandP(20.0), inv(supplyRow("C1", 10.0), supplyRow("C2", 1000.0)), data, requestTimeDt = null, config = mapOf("purchase_allowed" to false))
        val wo = children(tree).firstOrNull { it["type"] == "work_order" }
        val dominators = dominatorEntries(wo, "quantity_dominator")
        dominators.size shouldBe 1
        dominators[0]["supply_id"] shouldBe "S_C1"
        (dominators[0]["label"] as String) shouldBe "C1@L (S_C1)"
    }

    test("no shortage: no quantity_dominator is attached (both children fully supplied)") {
        val data = mapOf(
            "method_make" to listOf(mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(bomChild("B", "C1"), bomChild("B", "C2")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supplyRow("C1", 1000.0), supplyRow("C2", 1000.0)),
        )
        val (_, _, tree) = plan(demandP(20.0), inv(supplyRow("C1", 1000.0), supplyRow("C2", 1000.0)), data, requestTimeDt = null, config = mapOf("purchase_allowed" to false))
        val wo = children(tree).firstOrNull { it["type"] == "work_order" }
        dominatorEntries(wo, "quantity_dominator") shouldBe emptyList()
    }

    test("time dominance: the later-arriving BOM child (a future-dated supply lot) is the WO's time_dominator") {
        // P = make(C1, C2), both fully suppliable from on-hand inventory (no quantity
        // shortage) — but C1's lot isn't available until well after the request date,
        // while C2's is available immediately. The parent WO's start must wait for C1's
        // late-arriving lot, so C1 should be the time_dominator.
        val data = mapOf(
            "method_make" to listOf(mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(bomChild("B", "C1"), bomChild("B", "C2")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supplyRow("C1", 5.0) + ("supply_date" to "2024-02-15"),
                supplyRow("C2", 5.0) + ("supply_date" to "2024-01-01"),
            ),
        )
        val inventory = inv(
            supplyRow("C1", 5.0) + ("supply_date" to "2024-02-15"),
            supplyRow("C2", 5.0) + ("supply_date" to "2024-01-01"),
        )
        val (_, _, tree) = plan(demandP(5.0), inventory, data, requestTimeDt = null, config = mapOf("purchase_allowed" to false))
        val wo = children(tree).firstOrNull { it["type"] == "work_order" }
        val dominators = dominatorEntries(wo, "time_dominator")
        dominators.size shouldBe 1
        dominators[0]["product_id"] shouldBe "C1"
    }

    test("root-level quantity_dominator: a genuine top-level shortfall propagates from the WO up to the demand root") {
        // Same AND-quantity-dominance fixture as above (C1 scarce, C2 plentiful), but driven
        // through runPlanning() so reconcile()'s "demand" branch (Phase 3, the bottom-up
        // COMMITMENT aggregate) actually runs on the tree root — plan() alone never calls
        // reconcile(). Regression for: the demand-branch used to attach NO quantity_dominator at
        // all, so a real shortfall (9,958/10,000 committed) showed no quantity dominator in the
        // UI's new "Dominators" section even though the WO one level down had it correctly.
        // Two demands contend for the same scarce C1 (cross-demand contention): the sketch phase's
        // achievable-qty prediction and the live sequential FCFS commit can disagree (the R4/R8
        // class reconcile() exists to catch — see its own doc comment), which is what actually
        // gives the ROOT demand node itself a genuine post-commit shortfall to report, unlike a
        // single, uncontended demand where the sketch pre-synchronizes AND-siblings perfectly and
        // reconcile() is correctly a no-op.
        val data = mapOf(
            "method_make" to listOf(mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(bomChild("B", "C1"), bomChild("B", "C2")),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supplyRow("C1", 10.0), supplyRow("C2", 2000.0)),
            "demand" to listOf(demandP(5.0) + ("demand_id" to "D1"), demandP(20.0) + ("demand_id" to "D2")),
        )
        // An explicit (dummy) purchasable_materials whitelist that excludes C1 makes criticalPids
        // a real, restricted set — C1's supply node is then NOT proportionally pre-split across
        // competing demands by the sketch phase, so its consumption is genuinely FCFS-ordered
        // (see DemandOrderBuilderTest's "nonCriticalConfig" for the same technique) — this is what
        // lets the live sequential commit actually diverge from the sketch's own prediction.
        val result = runPlanning(data, config = mapOf("purchase_allowed" to false, "purchasable_materials" to listOf("__dummy__")))
        @Suppress("UNCHECKED_CAST")
        val planningPegging = result.output["planning_pegging"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val tree = planningPegging.first { it["demand_id"] == "D2" }["tree"] as Map<String, Any?>
        tree["type"] shouldBe "demand"
        val rootDominators = dominatorEntries(tree, "quantity_dominator")
        rootDominators.isNotEmpty() shouldBe true
        rootDominators.all { it["product_id"] == "C1" } shouldBe true
    }

    test("intra-demand per-branch dominator: constrained AND-siblings each get a bom_child ref naming X's own lot, not one arbitrary sibling") {
        // P = make(C1, C2, C3, C4), rate 1 each — a genuine AND, all four required together.
        // Each Ci = make(X), rate 1 — so each of the 4 siblings independently needs P's own
        // quantity of the SAME shared critical material X. Demand = 40, X supply = 48 (but a
        // single demand's own aggregate per-lot cap for a critical material is the raw demand
        // quantity, not the BOM-rate-amplified total, so D1's own ceiling on X is 40, not 48) —
        // combined ask across the 4 siblings (160) massively exceeds that 40-unit ceiling, so
        // computeAndSiblingCaps's step (c) tags the constrained siblings with a bom_child ref
        // naming X's own physical lot (never a synthesized "shared budget" abstraction — a
        // dominator is always one of the raw supply lots) instead of one arbitrary "worst"
        // sibling the way the pre-fix AND-min logic did. Which other siblings were also drawing
        // on that lot rides along on competingDemandIds purely for a UI tooltip, so step (c)
        // firing is only distinguishable from the plain sketch-phase fallback by that field being
        // populated, not by kind — both are bom_child. The live commit's own tagging site only
        // prefers step (c)'s ref when the SKETCH phase's independent nodeQtyCaps prediction also
        // flags a shortfall at that exact node — a documented, accepted gap (see plan's "Known
        // scope gap" note) since the sketch phase doesn't track cross-branch consumption; for a
        // sibling where the two predictions don't align, the sketch-phase fallback (bom_child,
        // itself already correctly scoped to this demand's own entitled lots by the
        // rawSupplyLotRefs fix) still fires — never nothing.
        val data = mapOf(
            "method_make" to listOf(
                mapOf<String, Any?>("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "B1", "product_id" to "C1", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "B2", "product_id" to "C2", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "B3", "product_id" to "C3", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "B4", "product_id" to "C4", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(
                bomChild("BP", "C1"), bomChild("BP", "C2"), bomChild("BP", "C3"), bomChild("BP", "C4"),
                mapOf<String, Any?>("bom_id" to "B1", "parent_id" to "C1", "child_id" to "X", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "B2", "parent_id" to "C2", "child_id" to "X", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "B3", "parent_id" to "C3", "child_id" to "X", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "B4", "parent_id" to "C4", "child_id" to "X", "alt_group" to null, "rate" to 1.0),
            ),
            "productlocation" to listOf(mapOf<String, Any?>("product_id" to "X", "location_id" to "L", "prod_area" to "raw")),
            "supply" to listOf(supplyRow("X", 48.0)),
            "demand" to listOf(demandP(40.0)),
        )
        val result = runPlanning(data, config = mapOf("purchase_allowed" to false))
        @Suppress("UNCHECKED_CAST")
        val planningPegging = result.output["planning_pegging"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val tree = planningPegging.first()["tree"] as Map<String, Any?>

        // Find every C1-C4 DEMAND node (not its work_order child) — plan()'s own tagging site
        // tags the node IT returns for that call, which represents the demand itself.
        fun findByProduct(node: Map<String, Any?>, pid: String, acc: MutableList<Map<String, Any?>>) {
            if (node["product_id"] == pid && node["type"] == "demand") acc.add(node)
            children(node).forEach { findByProduct(it, pid, acc) }
        }
        val siblingWos = mutableListOf<Map<String, Any?>>()
        for (pid in listOf("C1", "C2", "C3", "C4")) findByProduct(tree, pid, siblingWos)
        siblingWos.size shouldBe 4

        // The core regression: every sibling correctly names X — never an arbitrary AND-min
        // "worst" sibling — regardless of whether step (c)'s override or each sibling's own,
        // already-correct recursive resolution is what actually produced it. Step (c) is a
        // fallback for when a sibling's own view can't see the cross-branch contention; it must
        // NEVER overwrite a sibling that already resolved the right answer on its own (a real,
        // separate bug this fixture doesn't happen to exercise — see the AND-loop's own
        // dominatorOverride, which now defers to cPegging's existing dominator when present).
        for (wo in siblingWos) {
            val dominators = dominatorEntries(wo, "quantity_dominator")
            dominators.size shouldBe 1
            // Always a real lot: kind = "bom_child", naming X@L specifically — never a
            // synthesized aggregate.
            dominators[0]["kind"] shouldBe "bom_child"
            dominators[0]["product_id"] shouldBe "X"
            dominators[0]["location_id"] shouldBe "L"
        }
    }

    test("priority ordering: branchDominator wins over the sketch-phase fallback (ownDominator)") {
        // Direct plan() unit test (not runPlanning) so nodeQtyCaps, demandBlueprint, and
        // branchDominator can all be supplied explicitly and deliberately made to overlap on
        // the same node — regression guard for the tagging site's priority order. There is no
        // cross-demand tier to test against any more: critical materials are fully resolved by
        // the supply-guided pre-processor before plan() runs, so quantity_dominator never needs
        // a "who else needs this" tag — only step (c)'s branchDominator (intra-demand, freshest)
        // and the sketch phase's own ownDominator (generic catch-all) remain.
        val demand = demandP(10.0)
        val data = mapOf(
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy" to listOf(mapOf<String, Any?>("product_id" to "P", "location_id" to "L", "preference" to 1)),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to emptyList<Map<String, Any?>>(),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
        )
        val nodeQtyCaps = mapOf(("P" to "L") to 5.0)  // below the 10 asked — triggers the tagging block
        val demandBlueprint: DemandBlueprint = mapOf(
            ("P" to "L") to NodeBlueprint(
                achievable = 5.0,
                quantityDominator = listOf(DominatorRef(
                    kind = "bom_child", productId = "P", locationId = "L", label = "sketch-phase fallback cause",
                )),
            ),
        )
        val branchDominatorRef = listOf(DominatorRef(
            kind = "bom_child", productId = "P", locationId = "L", label = "intra-demand branch cause",
        ))
        val (_, _, tree) = plan(
            demand, inv(), data, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true),
            nodeQtyCaps = nodeQtyCaps,
            demandBlueprint = demandBlueprint,
            branchDominator = branchDominatorRef,
        )
        val dominators = dominatorEntries(tree, "quantity_dominator")
        dominators.size shouldBe 1
        dominators[0]["label"] shouldBe "intra-demand branch cause"
    }
})
