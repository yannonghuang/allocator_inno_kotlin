package com.allocator

import com.allocator.services.PreferenceKb
import com.allocator.services.PreferenceKbEntry
import com.allocator.services.buildPreferenceKb
import com.allocator.services.computeNodeMetrics
import com.allocator.services.plan
import com.allocator.services.reconstructNodeScores
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

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
        val rows = buildPreferenceKb(data, config = null, maxBomDepth = 3, deliveryWeight = 0.5, inventoryWeight = 0.5, criticalMaterialWeight = 0.0)
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
        val rows = buildPreferenceKb(data, config = null, maxBomDepth = 3, deliveryWeight = 0.5, inventoryWeight = 0.5, criticalMaterialWeight = 0.0)
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
        val shallow = computeNodeMetrics("P", "L", data, config = null, maxBomDepth = 1, supplyByNode = mapOf("C4" to "L" to 50.0), cache = shallowCache)
        shallow.bestCoverageUnits shouldBe 0.0

        val deepCache = mutableMapOf<Pair<Pair<String, String>, Int>, com.allocator.services.NodeMetrics>()
        val deep = computeNodeMetrics("P", "L", data, config = null, maxBomDepth = 5, supplyByNode = mapOf("C4" to "L" to 50.0), cache = deepCache)
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

    fun purchasedQty(wos: List<Map<String, Any?>>, pid: String): Double =
        wos.filter { it["method"] == "purchase" && it["product_id"] == pid }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

    test("no Preferences KB (null) at root with max_methods>1 defaults to an equal split, not 100%-to-best") {
        // demandFor's default config has no max_methods -> DEFAULT_MAX_METHODS=2, and this is a
        // root demand -> the root-only equal-split default applies: both of P1's two make
        // methods get an even share (5 of 10) rather than B1 taking all of it with B2 unused.
        val data = twoAltFixture("P1", "C1", "C2")
        val (_, wos, _) = plan(demandFor("P1", "D1"), mutableListOf(), data, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true, "method_selection" to mapOf("root_waterfall" to false)), preferenceKb = null)
        purchasedChildren(wos) shouldBe setOf("C1", "C2")
        purchasedQty(wos, "C1") shouldBe (5.0 plusOrMinus 0.01)
        purchasedQty(wos, "C2") shouldBe (5.0 plusOrMinus 0.01)
    }

    test("a full-coverage Preferences KB no longer skews the root split — it stays a flat equal split even with a lopsided score gap") {
        // P1 gets KB entries for BOTH of its alternatives with a lopsided score (B2 far ahead of
        // B1). The root split used to skew heavily toward C2 in this case; it's now a flat 50/50
        // regardless of the KB score gap — a continuous KB-weighted ratio is a hard-to-explain
        // artifact for users, so root split ignores it entirely and always divides evenly across
        // the top `cap` candidates. P2 has NO KB entries at all -> same flat equal-split result,
        // proving the KB's presence/absence no longer changes root-split behavior at all.
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
        val preferenceKb = PreferenceKb(
            entries = mapOf(
                Triple("P1", "L", "B1_P1:__null__") to PreferenceKbEntry(preference = 20, inventoryScore = 0.0, deliveryScore = 10.0, criticalMaterialScore = 0.0),
                Triple("P1", "L", "B2_P1:__null__") to PreferenceKbEntry(preference = 10, inventoryScore = 100.0, deliveryScore = 0.0, criticalMaterialScore = 0.0),
            ),
            deliveryWeight = 0.5, inventoryWeight = 0.5, criticalMaterialWeight = 0.0,
        )

        val (_, wosP1, _) = plan(demandFor("P1", "D1"), mutableListOf(), combined, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true, "method_selection" to mapOf("root_waterfall" to false)), preferenceKb = preferenceKb)
        purchasedChildren(wosP1) shouldBe setOf("C1", "C2")
        purchasedQty(wosP1, "C1") shouldBe (5.0 plusOrMinus 0.01)
        purchasedQty(wosP1, "C2") shouldBe (5.0 plusOrMinus 0.01)

        val (_, wosP2, _) = plan(demandFor("P2", "D2"), mutableListOf(), combined, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true, "method_selection" to mapOf("root_waterfall" to false)), preferenceKb = preferenceKb)
        purchasedChildren(wosP2) shouldBe setOf("C3", "C4")
        purchasedQty(wosP2, "C3") shouldBe (5.0 plusOrMinus 0.01)
        purchasedQty(wosP2, "C4") shouldBe (5.0 plusOrMinus 0.01)
    }

    test("a single-candidate (max_methods=1 equivalent) root demand is never split") {
        val data = twoAltFixture("P1", "C1", "C2")
        val (_, wos, _) = plan(demandFor("P1", "D1"), mutableListOf(), data, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true, "method_selection" to mapOf("max_methods" to 1)), preferenceKb = null)
        purchasedChildren(wos) shouldBe setOf("C1")
        purchasedQty(wos, "C1") shouldBe (10.0 plusOrMinus 0.01)
    }

    test("reconstructNodeScores: recombines persisted raw axis scores back into a comparable 0..1 score, per the same min-max formula scoreNodeCandidates used to rank them") {
        val data = twoAltFixture("P1", "C1", "C2")
        val (_, wos, _) = plan(demandFor("P1", "D1"), mutableListOf(), data, requestTimeDt = null,
            config = mapOf("purchase_allowed" to true, "method_selection" to mapOf("max_methods" to 1)), preferenceKb = null)
        // (indirectly confirms the fixture plans without error; reconstructNodeScores itself is
        // exercised directly below against a hand-built WaterfallCandidate list)
        wos.isNotEmpty() shouldBe true

        val candidates = listOf(
            com.allocator.services.WaterfallCandidate(mapOf("type" to "make", "bom_id" to "B1", "preference" to 1), null),
            com.allocator.services.WaterfallCandidate(mapOf("type" to "make", "bom_id" to "B2", "preference" to 2), null),
            com.allocator.services.WaterfallCandidate(mapOf("type" to "make", "bom_id" to "B3", "preference" to 3), null),
        )
        val kb = PreferenceKb(
            entries = mapOf(
                Triple("P", "L", "B1:") to PreferenceKbEntry(10, inventoryScore = 100.0, deliveryScore = 0.0, criticalMaterialScore = 0.0),
                Triple("P", "L", "B2:") to PreferenceKbEntry(20, inventoryScore = 50.0, deliveryScore = 5.0, criticalMaterialScore = 0.0),
                Triple("P", "L", "B3:") to PreferenceKbEntry(30, inventoryScore = 0.0, deliveryScore = 10.0, criticalMaterialScore = 0.0),
            ),
            deliveryWeight = 0.5, inventoryWeight = 0.5, criticalMaterialWeight = 0.0,
        )
        val scores = reconstructNodeScores("P", "L", candidates, kb)
        scores shouldNotBe null
        scores!!.size shouldBe 3
        // B1: normCov=1.0, normDelivery=1.0 -> score=1.0; B3: normCov=0.0, normDelivery=0.0 -> score=0.0
        scores[0] shouldBe (1.0 plusOrMinus 0.01)
        scores[2] shouldBe (0.0 plusOrMinus 0.01)
        (scores[0] > scores[1] && scores[1] > scores[2]) shouldBe true

        // Missing coverage for one candidate -> null (defer to caller's own fallback).
        val partialKb = PreferenceKb(
            entries = mapOf(Triple("P", "L", "B1:") to PreferenceKbEntry(10, 100.0, 0.0, 0.0)),
            deliveryWeight = 0.5, inventoryWeight = 0.5, criticalMaterialWeight = 0.0,
        )
        reconstructNodeScores("P", "L", candidates, partialKb) shouldBe null
    }

    test("critical material usage sums across sibling sub-assemblies, not deduplicated by material identity") {
        // R made from two required siblings SA1 and SA2 (both AND-mandatory), each in turn
        // requiring the SAME raw critical material CM. CM has no make/buy methods at all, so
        // isRawCriticalPosition(CM, L, ...) is true unconditionally (criterion 1: no make, no
        // buy) — no config needed. Expected: CM's own edge is counted once per component that
        // uses it (SA1 and SA2 each contribute 1), so R's own usage sums to 2, not deduplicated
        // down to 1 for the shared physical material.
        val data = mapOf(
            "method_make" to listOf(
                mapOf<String, Any?>("bom_id" to "BR", "product_id" to "R", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "BSA1", "product_id" to "SA1", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
                mapOf<String, Any?>("bom_id" to "BSA2", "product_id" to "SA2", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "bom" to listOf(
                mapOf<String, Any?>("bom_id" to "BR", "parent_id" to "R", "child_id" to "SA1", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "BR", "parent_id" to "R", "child_id" to "SA2", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "BSA1", "parent_id" to "SA1", "child_id" to "CM", "alt_group" to null, "rate" to 1.0),
                mapOf<String, Any?>("bom_id" to "BSA2", "parent_id" to "SA2", "child_id" to "CM", "alt_group" to null, "rate" to 1.0),
            ),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "supply" to emptyList<Map<String, Any?>>(),
            "demand" to listOf(
                mapOf<String, Any?>("demand_id" to "D1", "product_id" to "R", "location_id" to "L", "quantity" to 10.0),
            ),
        )
        val cache = mutableMapOf<Pair<Pair<String, String>, Int>, com.allocator.services.NodeMetrics>()
        val cm = computeNodeMetrics("CM", "L", data, config = null, maxBomDepth = 3, supplyByNode = emptyMap(), cache = cache)
        cm.bestCriticalMaterialUsage shouldBe 0.0 // leaf: no methods, no stock

        val sa1 = computeNodeMetrics("SA1", "L", data, config = null, maxBomDepth = 3, supplyByNode = emptyMap(), cache = cache)
        sa1.bestCriticalMaterialUsage shouldBe 1.0 // CM is critical -> 1 edge

        val r = computeNodeMetrics("R", "L", data, config = null, maxBomDepth = 3, supplyByNode = emptyMap(), cache = cache)
        // SA1 and SA2 are each NOT critical (they have their own make method -> elastic), so
        // each contributes only its own recursive usage (1.0) -> summed to 2.0, not deduped.
        r.bestCriticalMaterialUsage shouldBe 2.0

        // Also cover the persisted-row path: R's own PreferenceCandidateRow should carry the
        // same total.
        val rows = buildPreferenceKb(data, config = null, maxBomDepth = 3, deliveryWeight = 0.3, inventoryWeight = 0.3, criticalMaterialWeight = 0.4)
        val rRow = rows.first { it.productId == "R" && it.locationId == "L" }
        rRow.criticalMaterialScore shouldBe 2.0
    }
})
