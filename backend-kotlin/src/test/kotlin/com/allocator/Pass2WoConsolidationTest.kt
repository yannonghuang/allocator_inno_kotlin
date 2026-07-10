package com.allocator

import com.allocator.services.consolidateByWaves
import com.allocator.services.consolidateWorkOrdersByTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Pass 2 — work-order timing/scheduling consolidation. Batches same-
 * (product, location, method, source) WOs that start within the same window into
 * fewer, larger orders, preserving total quantity. `consolidateByWaves` does this
 * bottom-up in topological waves over the global WO dependency graph, so a parent
 * is never bucketed/timed until every live dependency has been finalized.
 */
class Pass2WoConsolidationTest : FunSpec({

    fun wo(
        pid: String, lid: String, method: String, qty: Double,
        start: String, end: String, demand: String?, source: String? = null,
        failed: Boolean = false,
    ): Map<String, Any?> = buildMap {
        put("product_id", pid); put("location_id", lid); put("method", method)
        put("quantity", qty); put("start_time", start); put("end_time", end)
        put("demand_id", demand); put("location_source", source)
        if (failed) put("failed", true)
    }

    val data: Map<String, List<Map<String, Any?>>> = mapOf(
        "productlocation" to listOf(
            mapOf("product_id" to "RAW1", "location_id" to "L", "max_lot_size" to 1000.0),
        ),
    )

    fun qtyOf(w: Map<String, Any?>) = (w["quantity"] as Number).toDouble()

    test("same product/location/method in same window merge; total qty preserved") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 200.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 300.0, "2024-05-03", "2024-06-22", "D2"),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30).consolidated
        out.size shouldBe 1
        qtyOf(out[0]) shouldBe (500.0 plusOrMinus 1e-6)
        out[0]["consolidated"] shouldBe true
        out[0]["demand_id"] shouldBe null
        @Suppress("UNCHECKED_CAST")
        (out[0]["wo_competing_demands"] as List<String>) shouldContainExactlyInAnyOrder listOf("D1", "D2")
    }

    test("different products are NOT merged") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW2", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D2"),
        )
        consolidateWorkOrdersByTiming(wos, data, windowDays = 30).consolidated.size shouldBe 2
    }

    test("moves consolidate by (source,target,window) — DIFFERENT components share one shipment") {
        // A physical move from S→T in a window is one shipment that can carry mixed cargo, so
        // moves are keyed WITHOUT product. The merged WO has product_id=null + a move_components
        // manifest. (Per-product move nodes stay in each demand's pegging, untouched.)
        val wos = listOf(
            wo("RAW1", "T", "move", 200.0, "2024-05-01", "2024-05-03", "D1", source = "S"),
            wo("RAW2", "T", "move", 300.0, "2024-05-02", "2024-05-04", "D2", source = "S"),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30).consolidated
        out.size shouldBe 1
        val m = out[0]
        m["method"] shouldBe "move"
        m["product_id"] shouldBe null
        m["location_id"] shouldBe "T"
        m["location_source"] shouldBe "S"
        m["consolidated"] shouldBe true
        qtyOf(m) shouldBe (500.0 plusOrMinus 1e-6)
        @Suppress("UNCHECKED_CAST")
        val comps = m["move_components"] as List<Map<String, Any?>>
        comps.map { it["product_id"] as String } shouldContainExactlyInAnyOrder listOf("RAW1", "RAW2")
    }

    test("moves with DIFFERENT (source,target) are NOT merged") {
        val wos = listOf(
            wo("RAW1", "T", "move", 100.0, "2024-05-01", "2024-05-03", "D1", source = "S1"),
            wo("RAW1", "T", "move", 100.0, "2024-05-01", "2024-05-03", "D2", source = "S2"),
        )
        consolidateWorkOrdersByTiming(wos, data, windowDays = 30).consolidated.size shouldBe 2
    }

    test("same product in DIFFERENT windows are NOT merged") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 100.0, "2024-09-01", "2024-10-21", "D2"),
        )
        // Different time windows → two separate consolidated WOs; each singleton gets
        // lot_count recomputed from qty/lotSize (not carried as native lot_count=1).
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 7).consolidated
        out.size shouldBe 2
        out.forEach { wo ->
            // 100 / max_lot_size(1000) = ceil(0.1) = 1 lot each
            wo["lot_count"] shouldBe 1
        }
    }

    test("merged group collapses to ONE batch WO carrying total qty + lot_count") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 1500.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 1000.0, "2024-05-02", "2024-06-21", "D2"),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30).consolidated
        out.size shouldBe 1                                   // two lot-WOs → one batch order
        qtyOf(out[0]) shouldBe (2500.0 plusOrMinus 1e-6)
        out[0]["lot_count"] shouldBe 3                        // ceil(2500 / max_lot_size 1000)
    }

    test("singletons pass through unchanged; failed WOs are excluded") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "make", 50.0, "2024-05-01", "2024-05-10", "D2", failed = true),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30).consolidated
        // Failed WOs are excluded (flattenPeggingToWorkOrders already skips them in production;
        // the one-pass design aligns consolidation with the same filter).
        out.size shouldBe 1
        out[0]["product_id"] shouldBe "RAW1"
        out.none { it["consolidated"] == true } shouldBe true
        // A singleton row must carry the same wo_competing_demands / consolidation_split_details
        // shape a merged row does — otherwise a later cross-wave merge with other demands' rows
        // would silently drop this demand's quantity from the merged split while still counting
        // it in the merged total (the accordion "missing count" bug: demand shown, no qty next to it).
        @Suppress("UNCHECKED_CAST")
        (out[0]["wo_competing_demands"] as List<String>) shouldContainExactlyInAnyOrder listOf("D1")
        @Suppress("UNCHECKED_CAST")
        val splitDetails = out[0]["consolidation_split_details"] as List<Map<String, Any?>>
        splitDetails.size shouldBe 1
        splitDetails[0]["demand_id"] shouldBe "D1"
        (splitDetails[0]["allocated_qty"] as Number).toDouble() shouldBe (100.0 plusOrMinus 1e-6)
    }

    test("PARTITION invariant — every native belongs to EXACTLY ONE consolidated WO; qty conserved") {
        // A mix: a 2-WO merge group, a lone singleton (different window), and a failed WO (excluded).
        val wos = listOf(
            wo("RAW1", "L", "purchase", 200.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 300.0, "2024-05-03", "2024-06-22", "D2"),
            wo("RAW1", "L", "purchase", 70.0, "2024-09-01", "2024-10-21", "D3"),  // far window → singleton
            wo("RAW1", "L", "make", 50.0, "2024-05-01", "2024-05-10", "D4", failed = true),  // excluded
        )
        val res = consolidateWorkOrdersByTiming(wos, data, windowDays = 7)
        val validWos = wos.filterNot { it["failed"] == true }

        // 1. Coverage: every non-failed native is present, each carries a consolidated_group_id.
        res.native.size shouldBe validWos.size
        res.native.all { it["consolidated_group_id"] != null } shouldBe true

        // 2. Bijection: the group ids seen on natives are exactly those on the consolidated WOs,
        //    and each consolidated WO has a unique id (so each native → EXACTLY ONE consolidated).
        val nativeGids = res.native.map { it["consolidated_group_id"] }.toSet()
        val consGids = res.consolidated.map { it["consolidated_group_id"] }
        consGids.size shouldBe consGids.toSet().size            // ids unique among consolidated WOs
        consGids.toSet() shouldBe nativeGids                    // same set on both sides

        // 3. Per-group conservation: each consolidated WO's qty == sum of its constituent natives.
        val nativeByGid = res.native.groupBy { it["consolidated_group_id"] }
        for (c in res.consolidated) {
            val members = nativeByGid[c["consolidated_group_id"]].orEmpty()
            qtyOf(c) shouldBe (members.sumOf { qtyOf(it) } plusOrMinus 1e-6)
        }

        // 4. Global conservation: total qty across non-failed WOs is preserved.
        res.consolidated.sumOf { qtyOf(it) } shouldBe (validWos.sumOf { qtyOf(it) } plusOrMinus 1e-6)
    }

    // ── consolidateByWaves — bottom-up wave propagation over real pegging trees ─────────────────

    // Pegging tree WO node carrying full product/method identity (unlike the flat `wo()` helper,
    // demand_id is NOT set here — it's threaded down from the `peg()` wrapper, matching how
    // consolidateByWaves's walk() actually attributes demandId to every WO node in a tree).
    fun woNode(
        gid: String, pid: String, lid: String, method: String, qty: Double,
        start: String, end: String,
        source: String? = null,
        children: List<Map<String, Any?>> = emptyList(),
        failed: Boolean = false,
    ): Map<String, Any?> = buildMap {
        put("type", "work_order"); put("wo_group_id", gid)
        put("product_id", pid); put("location_id", lid); put("method", method)
        put("quantity", qty); put("start_time", start); put("end_time", end)
        put("location_source", source)
        if (children.isNotEmpty()) put("children", children)
        if (failed) put("failed", true)
    }

    fun peg(demandId: String, tree: Map<String, Any?>): Map<String, Any?> =
        mapOf("demand_id" to demandId, "tree" to tree)

    @Suppress("UNCHECKED_CAST")
    fun treeOf(entry: Map<String, Any?>) = entry["tree"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun childAt(node: Map<String, Any?>, idx: Int = 0) =
        (node["children"] as List<Map<String, Any?>>)[idx]

    test("multi-wave cascade: a delayed RAW batch pushes the FG batch's start in the same pass") {
        // D1: FG(May05–?) depends on RAW_D1(May01–May08, 7d lead via native-duration fallback)
        // D2: FG(May06–?) depends on RAW_D2(May03–May10, 7d lead via native-duration fallback)
        // RAW_D1/RAW_D2 share (product,location,method) and merge in wave 0. A merged bucket's start
        // must be >= EVERY member's own pendingStart (each member's own correctly-computed earliest
        // legal start) or the later member's predecessor constraint would be silently violated — so
        // the merge takes max(May01,May03)=May03, end=May03+7d=May10 (no method_buy fixture, so lead
        // falls back to native duration).
        // Both FG batches depend on RAW and must wait for the merged RAW group's end (May10) — later
        // than either FG's own native start — before being bucketed in wave 1.
        val rawD1 = woNode("WG_RAW_D1", "RAW1", "L", "purchase", 200.0, "2024-05-01", "2024-05-08")
        val rawD2 = woNode("WG_RAW_D2", "RAW1", "L", "purchase", 300.0, "2024-05-03", "2024-05-10")
        val fgD1 = woNode("WG_FG_D1", "FG1", "L", "make", 80.0, "2024-05-05", "2024-05-09", children = listOf(rawD1))
        val fgD2 = woNode("WG_FG_D2", "FG1", "L", "make", 80.0, "2024-05-06", "2024-05-10", children = listOf(rawD2))
        val trees = listOf(peg("D1", fgD1), peg("D2", fgD2))

        val res = consolidateByWaves(trees, data, windowDays = 30)

        val cRaw = res.consolidation.consolidated.first { it["method"] == "purchase" }
        val cFg = res.consolidation.consolidated.first { it["method"] == "make" }

        cRaw["start_time"] shouldBe "2024-05-03"
        cRaw["end_time"] shouldBe "2024-05-10"

        // FG must wait for RAW's end (May10), not start at either of its own native starts.
        cFg["start_time"] shouldBe "2024-05-10"

        // The rewritten pegging trees reflect the same final timing at both levels.
        val d1Fg = treeOf(res.peggingTrees[0])
        val d1Raw = childAt(d1Fg)
        d1Raw["start_time"] shouldBe "2024-05-03"; d1Raw["end_time"] shouldBe "2024-05-10"
        d1Fg["start_time"] shouldBe "2024-05-10"

        val d2Fg = treeOf(res.peggingTrees[1])
        val d2Raw = childAt(d2Fg)
        d2Raw["start_time"] shouldBe "2024-05-03"; d2Raw["end_time"] shouldBe "2024-05-10"
        d2Fg["start_time"] shouldBe "2024-05-10"
    }

    test("wave-peer time_dominator resolves through to the raw supply beneath the winning peer") {
        // Same shape as the "multi-wave cascade" test above, but WG_RAW_D2 (the later-arriving
        // peer whose merged end pushes both FG batches to 2024-05-10) has its own genuine raw
        // supply leaf beneath it. FG_D2's wave-assigned time_dominator must resolve THROUGH the
        // WG_RAW_D2 peer down to that supply leaf — a bare pointer at WG_RAW_D2 itself would be
        // "Delayed by: purchase RAW1@L", an intermediate WO that is itself a consequence, not a
        // root cause (the exact bug reported against a real case: "Delayed by: move X@VIRTUAL").
        val rawSupplyLeaf = mapOf<String, Any?>(
            "type" to "supply", "product_id" to "RAW1", "location_id" to "L",
            "quantity" to 300.0, "supply_id" to "S_RAW1_LATE",
        )
        val rawD1 = woNode("WG_RAW_D1", "RAW1", "L", "purchase", 200.0, "2024-05-01", "2024-05-08")
        val rawD2 = woNode("WG_RAW_D2", "RAW1", "L", "purchase", 300.0, "2024-05-03", "2024-05-10", children = listOf(rawSupplyLeaf))
        val fgD1 = woNode("WG_FG_D1", "FG1", "L", "make", 80.0, "2024-05-05", "2024-05-09", children = listOf(rawD1))
        val fgD2 = woNode("WG_FG_D2", "FG1", "L", "make", 80.0, "2024-05-06", "2024-05-10", children = listOf(rawD2))
        val trees = listOf(peg("D1", fgD1), peg("D2", fgD2))

        val res = consolidateByWaves(trees, data, windowDays = 30)

        val d2Fg = treeOf(res.peggingTrees[1])
        d2Fg["start_time"] shouldBe "2024-05-10"  // confirms the push actually happened (pre-existing behavior)

        @Suppress("UNCHECKED_CAST")
        val d2TimeDominators = (d2Fg["time_dominator"] as? List<Map<String, Any?>>) ?: emptyList()
        d2TimeDominators.isNotEmpty() shouldBe true
        d2TimeDominators.all { it["supply_id"] == "S_RAW1_LATE" } shouldBe true
        d2TimeDominators.none { it["wo_group_id"] == "WG_RAW_D2" } shouldBe true
    }

    test("cross-tree shared wo_group_id (F30__888-style): occurrences aggregate before bucketing") {
        // Two SEPARATE pegging trees (e.g. a VIRTUAL consolidation demand and a real demand)
        // referencing the SAME physical WO group, each carrying a partial slice of its quantity.
        val sliceD1 = woNode("WG_SHARED", "RAW1", "L", "purchase", 150.0, "2024-05-01", "2024-05-08")
        val sliceD2 = woNode("WG_SHARED", "RAW1", "L", "purchase", 350.0, "2024-05-01", "2024-05-08")
        val trees = listOf(peg("D1", sliceD1), peg("D2", sliceD2))

        val res = consolidateByWaves(trees, data, windowDays = 30)

        // Aggregated into exactly ONE consolidated WO carrying the combined quantity.
        res.consolidation.consolidated.size shouldBe 1
        qtyOf(res.consolidation.consolidated[0]) shouldBe (500.0 plusOrMinus 1e-6)
        @Suppress("UNCHECKED_CAST")
        (res.consolidation.consolidated[0]["wo_competing_demands"] as List<String>) shouldContainExactlyInAnyOrder
            listOf("D1", "D2")

        // Both native occurrences roll into the SAME consolidated group.
        res.consolidation.native.size shouldBe 2
        val cgid = res.consolidation.consolidated[0]["consolidated_group_id"]
        cgid.shouldNotBeNull()
        res.consolidation.native.all { it["consolidated_group_id"] == cgid } shouldBe true
    }

    test("lot_count recomputes across a lot-size boundary; duration stays a single calendar wave") {
        // FG1@L: max_lot_size=100, lead_time=3d/wave, no operation/bor fixture so parallelismCap
        // falls back to 0 → treated as UNCONSTRAINED parallel (matching buildWorkOrders' documented
        // fallback: no BOR data models no resource constraint, so lots don't stack sequentially).
        // Two 80-unit make WOs merge to 160 units → lot_count=ceil(160/100)=2, but with cap=∞ both
        // lots still fit in a single wave. consolidateByWaves emits span = calendar lead_time only
        // (3d) — resource-capacity wave sequencing (waveCount × lead_time) is exclusively
        // ResourceScheduler's job, run after consolidation, so it plays no part here.
        val fgData: Map<String, List<Map<String, Any?>>> = mapOf(
            "productlocation" to listOf(
                mapOf("product_id" to "FG1", "location_id" to "L", "max_lot_size" to 100.0),
            ),
            "method_make" to listOf(
                mapOf("product_id" to "FG1", "location_id" to "L", "lead_time" to 3),
            ),
        )
        val w1 = woNode("WG_FG_A", "FG1", "L", "make", 80.0, "2024-05-01", "2024-05-03")
        val w2 = woNode("WG_FG_B", "FG1", "L", "make", 80.0, "2024-05-01", "2024-05-03")
        val trees = listOf(peg("D1", w1), peg("D2", w2))

        val res = consolidateByWaves(trees, fgData, windowDays = 30)

        res.consolidation.consolidated.size shouldBe 1
        val c = res.consolidation.consolidated[0]
        qtyOf(c) shouldBe (160.0 plusOrMinus 1e-6)
        c["lot_count"] shouldBe 2
        c["start_time"] shouldBe "2024-05-01"
        c["end_time"] shouldBe "2024-05-04"   // May01 + 1 wave * 3d lead_time
    }

    test("failed=true subtrees are excluded uniformly — AND (BOM) child and OR (alt-method) sibling") {
        // AND case: FG depends on two BOM components, one of which is failed=true.
        val rawOk = woNode("WG_RAW_OK", "RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-05-08")
        val rawFailed = woNode("WG_RAW_FAIL", "RAW2", "L", "purchase", 999.0, "2024-05-01", "2024-05-08", failed = true)
        val fg = woNode("WG_FG", "FG1", "L", "make", 80.0, "2024-05-09", "2024-05-12", children = listOf(rawOk, rawFailed))

        // OR case: two top-level sibling alternatives for the same demand, one is the non-chosen
        // (failed=true) alternative method.
        val altA = woNode("WG_ALT_A", "RAW3", "L", "purchase", 50.0, "2024-05-01", "2024-05-05", failed = true)
        val altB = woNode("WG_ALT_B", "RAW3", "L", "purchase", 50.0, "2024-05-01", "2024-05-05")
        val orRoot = mapOf("type" to "demand", "children" to listOf(altA, altB))

        val res = consolidateByWaves(listOf(peg("D1", fg), peg("D2", orRoot)), data, windowDays = 30)

        val gids = res.consolidation.consolidated.mapNotNull { it["wo_group_id"] as? String } +
            res.consolidation.native.mapNotNull { it["wo_group_id"] as? String }
        gids.shouldNotContainFailedGids("WG_RAW_FAIL", "WG_ALT_A")
        res.consolidation.consolidated.size shouldBe 3   // WG_RAW_OK, WG_FG, WG_ALT_B — failed gids excluded

        // The failed AND-child carries no dependency edge, so FG's wave resolution is driven
        // entirely by WG_RAW_OK's end_time (May08) — not blocked or altered by the excluded sibling.
        val cFg = res.consolidation.consolidated.first { it["wo_group_id"] == "WG_FG" }
        cFg["start_time"] shouldBe "2024-05-09"

        // Failed nodes are left unmutated in the rewritten trees (passed through, not dropped —
        // pegging trees keep them for diagnostics even though the WO lists exclude them).
        val fgTree = treeOf(res.peggingTrees[0])
        val failedChild = childAt(fgTree, 1)
        failedChild["failed"] shouldBe true
        failedChild["end_time"] shouldBe "2024-05-08"
    }
})

private fun List<String>.shouldNotContainFailedGids(vararg failedGids: String) {
    for (g in failedGids) {
        if (this.contains(g)) throw AssertionError("expected failed gid $g to be excluded from WO output, found in $this")
    }
}
