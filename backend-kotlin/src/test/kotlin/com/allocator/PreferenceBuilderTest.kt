package com.allocator

import com.allocator.services.buildPreferenceKb
import com.allocator.services.computeNodeMetrics
import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Behavior contract for the Preferences KB build engine (services/PreferenceBuilder.kt) and
 * its integration into planning's method/alt_group ranking.
 */
class PreferenceBuilderTest : FunSpec({

    test("stock-backed, fast alternative ranks first (preference 10); slow/no-stock alternative ranks second (20)") {
        val data = mapOf(
            "method_make" to listOf(
                mapOf<String, Any?>("bom_id" to "B1", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "B2", "product_id" to "P", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
            ),
            "method_buy" to listOf(
                mapOf<String, Any?>("product_id" to "C2", "location_id" to "L", "preference" to 1, "lead_days_supply" to 30),
            ),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(
                mapOf<String, Any?>("bom_id" to "B1", "parent_id" to "P", "child_id" to "C1", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "B2", "parent_id" to "P", "child_id" to "C2", "alt_group" to null, "rate" to 1.0),
            ),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                mapOf<String, Any?>("product_id" to "C1", "location_id" to "L", "qty" to 100.0),
            ),
            "demand" to listOf(
                mapOf<String, Any?>("demand_id" to "D1", "product_id" to "P", "location_id" to "L", "quantity" to 10.0),
            ),
        )
        val rows = buildPreferenceKb(data, maxBomDepth = 3, deliveryWeight = 0.5, inventoryWeight = 0.5)
        val pRows = rows.filter { it.productId == "P" && it.locationId == "L" }.sortedBy { it.preference }
        pRows.size shouldBe 2
        pRows[0].methodKey.startsWith("B1") shouldBe true
        pRows[0].preference shouldBe 10
        pRows[1].methodKey.startsWith("B2") shouldBe true
        pRows[1].preference shouldBe 20
    }

    test("a cyclic move alternative always ranks behind a genuinely feasible one") {
        val data = mapOf(
            "method_make" to emptyList<Map<String, Any?>>(),
            "method_buy" to listOf(
                mapOf<String, Any?>("product_id" to "X", "location_id" to "L1", "preference" to 1, "lead_days_supply" to 5),
            ),
            "method_move" to listOf(
                mapOf<String, Any?>("product_id" to "X", "from_location_id" to "L2", "to_location_id" to "L1", "preference" to 1, "transit_time" to 1.0),
                mapOf<String, Any?>("product_id" to "X", "from_location_id" to "L1", "to_location_id" to "L2", "preference" to 1, "transit_time" to 1.0),
            ),
            "bom" to emptyList<Map<String, Any?>>(),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
            "demand" to listOf(
                mapOf<String, Any?>("demand_id" to "D1", "product_id" to "X", "location_id" to "L1", "quantity" to 10.0),
            ),
        )
        val rows = buildPreferenceKb(data, maxBomDepth = 3, deliveryWeight = 0.5, inventoryWeight = 0.5)
        val xRows = rows.filter { it.productId == "X" && it.locationId == "L1" }.sortedBy { it.preference }
        xRows.size shouldBe 2
        xRows[0].methodType shouldBe "purchase"
        xRows[0].preference shouldBe 10
        xRows[1].methodType shouldBe "move"
        xRows[1].preference shouldBe 20
    }

    test("max_bom_depth caps how deep supply is visible") {
        // Linear chain P -> C1 -> C2 -> C3 -> C4, only C4 has on-hand supply.
        val data = mapOf(
            "method_make" to listOf(
                mapOf<String, Any?>("bom_id" to "BP", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "BC1", "product_id" to "C1", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "BC2", "product_id" to "C2", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "BC3", "product_id" to "C3", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(
                mapOf<String, Any?>("bom_id" to "BP", "parent_id" to "P", "child_id" to "C1", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "BC1", "parent_id" to "C1", "child_id" to "C2", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "BC2", "parent_id" to "C2", "child_id" to "C3", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "BC3", "parent_id" to "C3", "child_id" to "C4", "alt_group" to null, "rate" to 1.0),
            ),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                mapOf<String, Any?>("product_id" to "C4", "location_id" to "L", "qty" to 50.0),
            ),
        )
        val shallowCache = mutableMapOf<Pair<Pair<String, String>, Int>, com.allocator.services.NodeMetrics>()
        val shallow = computeNodeMetrics("P", "L", data, maxBomDepth = 1, supplyByNode = mapOf("C4" to "L" to 50.0), cache = shallowCache)
        shallow.bestCoverageUnits shouldBe 0.0

        val deepCache = mutableMapOf<Pair<Pair<String, String>, Int>, com.allocator.services.NodeMetrics>()
        val deep = computeNodeMetrics("P", "L", data, maxBomDepth = 5, supplyByNode = mapOf("C4" to "L" to 50.0), cache = deepCache)
        deep.bestCoverageUnits shouldBe 50.0
    }

    // ── Planning integration: KB override + per-alternative fallback ───────────────────

    fun purchasedChildren(wos: List<Map<String, Any?>>): Set<String> =
        wos.filter { it["method"] == "purchase" }.mapNotNull { it["product_id"] as? String }.toSet()

    fun twoAltFixture(parent: String, c1: String, c2: String) = mapOf(
        "method_make" to listOf(
            mapOf<String, Any?>("bom_id" to "B1_$parent", "product_id" to parent, "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            mapOf<String, Any?>("bom_id" to "B2_$parent", "product_id" to parent, "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
        ),
        "method_buy" to listOf(
            mapOf<String, Any?>("product_id" to c1, "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            mapOf<String, Any?>("product_id" to c2, "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
        ),
        "method_move" to emptyList<Map<String, Any?>>(),
        "bom" to listOf(
            mapOf<String, Any?>("bom_id" to "B1_$parent", "parent_id" to parent, "child_id" to c1, "alt_group" to null, "rate" to 1.0),
            mapOf<String, Any?>("bom_id" to "B2_$parent", "parent_id" to parent, "child_id" to c2, "alt_group" to null, "rate" to 1.0),
        ),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "supply" to emptyList<Map<String, Any?>>(),
    )

    fun demandFor(parent: String, id: String) = mapOf<String, Any?>(
        "demand_id" to id, "product_id" to parent, "location_id" to "L",
        "quantity" to 10.0, "request_due_time" to "2024-01-01",
    )

    test("no Preferences KB (null) preserves raw CSV preference — B1's child is chosen") {
        val data = twoAltFixture("P1", "C1", "C2")
        val (_, wos, _) = plan(demandFor("P1", "D1"), mutableListOf(), data, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true), preferenceKb = null)
        purchasedChildren(wos) shouldBe setOf("C1")
    }

    test("a Preferences KB override wins for its own node; a sibling node with no KB coverage still falls back to raw preference") {
        // P1 gets a KB override flipping it to B2's child (C2); P2 has NO KB entries at all and
        // must fall back to raw CSV preference (B1's child, C1-equivalent) — proving the
        // fallback is per-alternative/per-node, not all-or-nothing once any KB row exists.
        val dataP1 = twoAltFixture("P1", "C1", "C2")
        val dataP2 = twoAltFixture("P2", "C3", "C4")
        val combined = mapOf(
            "method_make" to (dataP1["method_make"]!! + dataP2["method_make"]!!),
            "method_buy" to (dataP1["method_buy"]!! + dataP2["method_buy"]!!),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to (dataP1["bom"]!! + dataP2["bom"]!!),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
        )
        val preferenceKb = mapOf(Triple("P1", "L", "B2_P1:__null__") to 0)

        val (_, wosP1, _) = plan(demandFor("P1", "D1"), mutableListOf(), combined, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true), preferenceKb = preferenceKb)
        purchasedChildren(wosP1) shouldBe setOf("C2")

        val (_, wosP2, _) = plan(demandFor("P2", "D2"), mutableListOf(), combined, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true), preferenceKb = preferenceKb)
        purchasedChildren(wosP2) shouldBe setOf("C3")
    }
})
