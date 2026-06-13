package com.allocator

import com.allocator.services.WoConsolidation
import com.allocator.services.consolidateWorkOrdersByTiming
import com.allocator.services.readjustConsolidatedWoTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pass 2 — work-order timing/scheduling consolidation. Batches same-
 * (product, location, method, source) WOs that start within the same window into
 * fewer, larger orders, preserving total quantity.
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
        consolidateWorkOrdersByTiming(wos, data, windowDays = 7).consolidated.size shouldBe 2
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

    test("singletons and failed WOs pass through unchanged") {
        val wos = listOf(
            wo("RAW1", "L", "purchase", 100.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "make", 50.0, "2024-05-01", "2024-05-10", "D2", failed = true),
        )
        val out = consolidateWorkOrdersByTiming(wos, data, windowDays = 30).consolidated
        out.size shouldBe 2
        out.none { it["consolidated"] == true } shouldBe true
    }

    // ── Helpers for readjustConsolidatedWoTiming tests ──────────────────────────────────────────

    val D = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    fun daysBetween(start: String, end: String): Long =
        LocalDate.parse(end, D).toEpochDay() - LocalDate.parse(start, D).toEpochDay()

    // Pegging tree WO node
    fun woNode(
        woGid: String, start: String, end: String,
        children: List<Map<String, Any?>> = emptyList(),
        failed: Boolean = false,
    ): Map<String, Any?> = buildMap {
        put("type", "work_order"); put("wo_group_id", woGid)
        put("start_time", start); put("end_time", end)
        if (children.isNotEmpty()) put("children", children)
        if (failed) put("failed", true)
    }

    // Pegging tree entry
    fun peg(demandId: String, tree: Map<String, Any?>): Map<String, Any?> =
        mapOf("demand_id" to demandId, "tree" to tree)

    // Consolidated WO record
    fun cWo(cgid: String, start: String, end: String): Map<String, Any?> =
        mapOf("consolidated_group_id" to cgid, "start_time" to start, "end_time" to end)

    // Native WO record
    fun nWo(woGid: String, cgid: String, start: String, end: String): Map<String, Any?> =
        mapOf("wo_group_id" to woGid, "consolidated_group_id" to cgid, "start_time" to start, "end_time" to end)

    @Suppress("UNCHECKED_CAST")
    fun treeOf(entry: Map<String, Any?>) = entry["tree"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun childAt(node: Map<String, Any?>, idx: Int = 0) =
        (node["children"] as List<Map<String, Any?>>)[idx]

    // ── readjustConsolidatedWoTiming tests ──────────────────────────────────────────────────────

    test("readjust — leaf WO start pushed to latest constituent; lead_time preserved") {
        // D1.start=May01, D2.start=May03 — batch can't begin until May03; lead=7d
        // consolidated initial: start=May01, end=May08
        // expected after: start=May03, end=May10; both demand pegging WOs updated
        val trees = listOf(
            peg("D1", woNode("W1", "2024-05-01", "2024-05-05")),
            peg("D2", woNode("W2", "2024-05-03", "2024-05-08")),
        )
        val consolidation = WoConsolidation(
            consolidated = listOf(cWo("CG1", "2024-05-01", "2024-05-08")),
            native = listOf(
                nWo("W1", "CG1", "2024-05-01", "2024-05-05"),
                nWo("W2", "CG1", "2024-05-03", "2024-05-08"),
            ),
        )
        val (adjTrees, adjConsolidated) = readjustConsolidatedWoTiming(trees, consolidation)

        adjConsolidated[0]["start_time"] shouldBe "2024-05-03"
        adjConsolidated[0]["end_time"] shouldBe "2024-05-10"

        treeOf(adjTrees[0])["start_time"] shouldBe "2024-05-03"
        treeOf(adjTrees[0])["end_time"] shouldBe "2024-05-10"
        treeOf(adjTrees[1])["start_time"] shouldBe "2024-05-03"
        treeOf(adjTrees[1])["end_time"] shouldBe "2024-05-10"

        // lead_time preserved
        daysBetween(adjConsolidated[0]["start_time"] as String, adjConsolidated[0]["end_time"] as String) shouldBe 7L
    }

    test("readjust — no-op when all constituents share the same start; returns same tree references") {
        val node1 = woNode("W1", "2024-05-01", "2024-05-08")
        val node2 = woNode("W2", "2024-05-01", "2024-05-08")
        val entry1 = peg("D1", node1)
        val entry2 = peg("D2", node2)
        val trees = listOf(entry1, entry2)
        val consolidation = WoConsolidation(
            consolidated = listOf(cWo("CG1", "2024-05-01", "2024-05-08")),
            native = listOf(
                nWo("W1", "CG1", "2024-05-01", "2024-05-08"),
                nWo("W2", "CG1", "2024-05-01", "2024-05-08"),
            ),
        )
        val (adjTrees, _) = readjustConsolidatedWoTiming(trees, consolidation)
        // Structural sharing: unchanged entries are the exact same reference
        adjTrees[0] shouldBe entry1
        adjTrees[1] shouldBe entry2
    }

    test("readjust — singleton group (1-demand) never needs a push") {
        val entry = peg("D1", woNode("W1", "2024-05-01", "2024-05-08"))
        val consolidation = WoConsolidation(
            consolidated = listOf(cWo("CG1", "2024-05-01", "2024-05-08")),
            native = listOf(nWo("W1", "CG1", "2024-05-01", "2024-05-08")),
        )
        val (adjTrees, adjCons) = readjustConsolidatedWoTiming(listOf(entry), consolidation)
        adjCons[0]["start_time"] shouldBe "2024-05-01"
        adjCons[0]["end_time"] shouldBe "2024-05-08"
        adjTrees[0] shouldBe entry
    }

    test("readjust — cascade: delayed component pushes parent WO in same BOM pass") {
        // D1: FG1(May06–May15, lead=9d) depends on RAW1(May01–May05, lead=4d) [CG_RAW]
        // D2: FG2(May09–May18, lead=9d) depends on RAW2(May03–May08, lead=5d) [CG_RAW]
        // CG_RAW start=May01 must push to May03 → parent WO effective_start = May10
        // CG_FG  start=May06 must push to May10
        val raw1 = woNode("W_RAW_D1", "2024-05-01", "2024-05-05")
        val raw2 = woNode("W_RAW_D2", "2024-05-03", "2024-05-08")
        val fg1  = woNode("W_FG_D1",  "2024-05-06", "2024-05-15", children = listOf(raw1))
        val fg2  = woNode("W_FG_D2",  "2024-05-09", "2024-05-18", children = listOf(raw2))
        val trees = listOf(peg("D1", fg1), peg("D2", fg2))
        val consolidation = WoConsolidation(
            consolidated = listOf(
                cWo("CG_RAW", "2024-05-01", "2024-05-08"),   // lead=7d
                cWo("CG_FG",  "2024-05-06", "2024-05-18"),   // lead=12d
            ),
            native = listOf(
                nWo("W_RAW_D1", "CG_RAW", "2024-05-01", "2024-05-05"),
                nWo("W_RAW_D2", "CG_RAW", "2024-05-03", "2024-05-08"),
                nWo("W_FG_D1",  "CG_FG",  "2024-05-06", "2024-05-15"),
                nWo("W_FG_D2",  "CG_FG",  "2024-05-09", "2024-05-18"),
            ),
        )
        val (adjTrees, adjCons) = readjustConsolidatedWoTiming(trees, consolidation)

        val cRaw = adjCons.first { it["consolidated_group_id"] == "CG_RAW" }
        val cFg  = adjCons.first { it["consolidated_group_id"] == "CG_FG" }

        // RAW batch pushed: D2.start=May03 > CG_RAW.start=May01
        cRaw["start_time"] shouldBe "2024-05-03"
        cRaw["end_time"]   shouldBe "2024-05-10"   // May03 + 7d lead
        daysBetween(cRaw["start_time"] as String, cRaw["end_time"] as String) shouldBe 7L

        // FG batch pushed: effective_start=RAW.end=May10 > CG_FG.start=May06
        cFg["start_time"] shouldBe "2024-05-10"
        cFg["end_time"]   shouldBe "2024-05-22"    // May10 + 12d lead
        daysBetween(cFg["start_time"] as String, cFg["end_time"] as String) shouldBe 12L

        // D1 pegging: both levels updated
        val d1Fg  = treeOf(adjTrees[0])
        val d1Raw = childAt(d1Fg)
        d1Raw["start_time"] shouldBe "2024-05-03"; d1Raw["end_time"] shouldBe "2024-05-10"
        d1Fg["start_time"]  shouldBe "2024-05-10"; d1Fg["end_time"]  shouldBe "2024-05-22"

        // D2 pegging: both levels updated
        val d2Fg  = treeOf(adjTrees[1])
        val d2Raw = childAt(d2Fg)
        d2Raw["start_time"] shouldBe "2024-05-03"; d2Raw["end_time"] shouldBe "2024-05-10"
        d2Fg["start_time"]  shouldBe "2024-05-10"; d2Fg["end_time"]  shouldBe "2024-05-22"
    }

    test("readjust — failed WO nodes are skipped and passed through unchanged") {
        val failedNode = woNode("W_FAIL", "2024-05-01", "2024-05-05", failed = true)
        val trees = listOf(peg("D1", failedNode))
        val consolidation = WoConsolidation(
            consolidated = listOf(cWo("CG1", "2024-05-01", "2024-05-08")),
            native = listOf(nWo("W_FAIL", "CG1", "2024-05-01", "2024-05-05")),
        )
        val (adjTrees, _) = readjustConsolidatedWoTiming(trees, consolidation)
        // Failed node must not be mutated
        treeOf(adjTrees[0])["end_time"] shouldBe "2024-05-05"
        treeOf(adjTrees[0])["failed"]   shouldBe true
    }

    test("readjust — WO nodes without wo_group_id are unchanged (no consolidated match)") {
        // A demand pegging may have WO nodes whose wo_group_id is absent
        val orphan = mapOf("type" to "work_order", "start_time" to "2024-05-01", "end_time" to "2024-05-05")
        val trees = listOf(peg("D1", orphan))
        val consolidation = WoConsolidation(consolidated = emptyList(), native = emptyList())
        val (adjTrees, _) = readjustConsolidatedWoTiming(trees, consolidation)
        treeOf(adjTrees[0])["end_time"] shouldBe "2024-05-05"
    }

    test("readjust — stable: second call on already-converged output is identity") {
        val trees = listOf(
            peg("D1", woNode("W1", "2024-05-01", "2024-05-05")),
            peg("D2", woNode("W2", "2024-05-03", "2024-05-08")),
        )
        val consolidation = WoConsolidation(
            consolidated = listOf(cWo("CG1", "2024-05-01", "2024-05-08")),
            native = listOf(
                nWo("W1", "CG1", "2024-05-01", "2024-05-05"),
                nWo("W2", "CG1", "2024-05-03", "2024-05-08"),
            ),
        )
        val (adjTrees1, adjCons1) = readjustConsolidatedWoTiming(trees, consolidation)
        // Build a new WoConsolidation from the adjusted output and call again
        val consolidation2 = WoConsolidation(
            consolidated = adjCons1,
            native = listOf(
                nWo("W1", "CG1", adjCons1[0]["start_time"] as String, adjCons1[0]["end_time"] as String),
                nWo("W2", "CG1", adjCons1[0]["start_time"] as String, adjCons1[0]["end_time"] as String),
            ),
        )
        val (adjTrees2, adjCons2) = readjustConsolidatedWoTiming(adjTrees1, consolidation2)
        // Second pass must produce identical output (no further changes)
        adjCons2[0]["start_time"] shouldBe adjCons1[0]["start_time"]
        adjCons2[0]["end_time"]   shouldBe adjCons1[0]["end_time"]
        adjTrees2[0] shouldBe adjTrees1[0]
        adjTrees2[1] shouldBe adjTrees1[1]
    }

    test("PARTITION invariant — every native belongs to EXACTLY ONE consolidated WO; qty conserved") {
        // A mix: a 2-WO merge group, a lone singleton (different window), and a failed pass-through.
        val wos = listOf(
            wo("RAW1", "L", "purchase", 200.0, "2024-05-01", "2024-06-20", "D1"),
            wo("RAW1", "L", "purchase", 300.0, "2024-05-03", "2024-06-22", "D2"),
            wo("RAW1", "L", "purchase", 70.0, "2024-09-01", "2024-10-21", "D3"),  // far window → singleton
            wo("RAW1", "L", "make", 50.0, "2024-05-01", "2024-05-10", "D4", failed = true),  // pass-through
        )
        val res = consolidateWorkOrdersByTiming(wos, data, windowDays = 7)

        // 1. Coverage: every native is present, and each carries a consolidated_group_id.
        res.native.size shouldBe wos.size
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

        // 4. Global conservation: total qty is preserved across the partition.
        res.consolidated.sumOf { qtyOf(it) } shouldBe (wos.sumOf { qtyOf(it) } plusOrMinus 1e-6)
    }
})
