package com.allocator

import com.allocator.api.MakeRow
import com.allocator.api.MoveRow
import com.allocator.api.buildBomGraphPure
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class BomGraphTest : FunSpec({

    // ── helpers ───────────────────────────────────────────────────────────────

    fun demand(p: String, l: String) = Pair(p, l)
    fun buy(p: String, l: String) = Pair(p, l)

    fun bom(
        bomId: String,
        parentId: String,
        childId: String,
        altGroup: String? = null,
        rate: Double = 1.0,
    ) = Triple(Pair(bomId, parentId), altGroup ?: "__null__$childId", Pair(childId, rate))

    fun makeBomMap(vararg entries: Triple<Pair<String, String>, String, Pair<String, Double>>)
        : Map<Pair<String, String>, Map<String, List<Pair<String, Double>>>> {
        val m = mutableMapOf<Pair<String, String>, MutableMap<String, MutableList<Pair<String, Double>>>>()
        for ((key, altKey, child) in entries) {
            m.getOrPut(key) { mutableMapOf() }
                .getOrPut(altKey) { mutableListOf() }
                .add(child)
        }
        return m
    }

    // ── scenario 1: demand + buy only ─────────────────────────────────────────

    test("demand node with buy method — 1 node, 0 edges, establishedBy=[buy]") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("P", "L")),
            moveRows     = emptyList(),
            makeRows     = emptyList(),
            bomByMakeKey = emptyMap(),
        )
        result.nodes shouldHaveSize 1
        result.edges shouldHaveSize 0
        val node = result.nodes.single()
        node.productId shouldBe "P"
        node.locationId shouldBe "L"
        node.isDemand shouldBe true
        node.establishedBy shouldContainExactlyInAnyOrder listOf("buy")
    }

    // ── scenario 2: move chain ─────────────────────────────────────────────────

    test("demand at B, move from A to B, buy at A — 2 nodes, 1 move edge") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "B")),
            buySet       = setOf(buy("P", "A")),
            moveRows     = listOf(MoveRow("P", "A", "B", 2.0, null)),
            makeRows     = emptyList(),
            bomByMakeKey = emptyMap(),
        )
        result.nodes shouldHaveSize 2
        result.edges shouldHaveSize 1
        val edge = result.edges.single()
        edge.edgeType shouldBe "move"
        edge.source shouldBe "P|A"
        edge.target shouldBe "P|B"
        edge.leadDays shouldBe 2.0
        result.nodes.single { it.locationId == "A" }.establishedBy shouldContainExactlyInAnyOrder listOf("buy")
        result.nodes.single { it.locationId == "B" }.establishedBy shouldContainExactlyInAnyOrder listOf("move")
    }

    // ── scenario 3: simple make + buy child ──────────────────────────────────

    test("demand P@L, make via BOM1 with child C@L (null altGroup), buy C@L — 2 nodes, 1 make edge") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("C", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(MakeRow("BOM1", "P", "L", null, 5)),
            bomByMakeKey = makeBomMap(bom("BOM1", "P", "C")),
        )
        result.nodes shouldHaveSize 2
        result.edges shouldHaveSize 1
        val edge = result.edges.single()
        edge.edgeType shouldBe "make"
        edge.source shouldBe "C|L"
        edge.target shouldBe "P|L"
        edge.altGroup shouldBe null
        edge.leadDays shouldBe 5.0
        result.nodes.single { it.productId == "C" }.establishedBy shouldContainExactlyInAnyOrder listOf("buy")
        result.nodes.single { it.productId == "P" }.establishedBy shouldContainExactlyInAnyOrder listOf("make")
    }

    // ── scenario 4: AND group (same altGroup, 2 children) ─────────────────────

    test("AND group: BOM with C1 and C2 in altGroup G1 — both children emitted") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("C1", "L"), buy("C2", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(MakeRow("BOM1", "P", "L", null, null)),
            bomByMakeKey = makeBomMap(
                bom("BOM1", "P", "C1", altGroup = "G1"),
                bom("BOM1", "P", "C2", altGroup = "G1"),
            ),
        )
        result.nodes shouldHaveSize 3  // P, C1, C2
        result.edges shouldHaveSize 2
        val edgeSources = result.edges.map { it.source }.toSet()
        edgeSources shouldContainExactlyInAnyOrder listOf("C1|L", "C2|L")
        result.edges.all { it.altGroup == "G1" } shouldBe true
    }

    // ── scenario 5: OR groups (different altGroups) ───────────────────────────

    test("OR groups: C1 in altGroup G1, C2 in altGroup G2 — both children emitted") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("C1", "L"), buy("C2", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(MakeRow("BOM1", "P", "L", null, null)),
            bomByMakeKey = makeBomMap(
                bom("BOM1", "P", "C1", altGroup = "G1"),
                bom("BOM1", "P", "C2", altGroup = "G2"),
            ),
        )
        result.nodes shouldHaveSize 3
        result.edges shouldHaveSize 2
        result.edges.map { it.altGroup }.toSet() shouldContainExactlyInAnyOrder listOf("G1", "G2")
    }

    // ── scenario 6: multi-hop make chain ──────────────────────────────────────

    test("multi-hop: demand(Finished) → make(Semi) → buy(Raw) — 3 nodes, 2 make edges") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("Finished", "L")),
            buySet       = setOf(buy("Raw", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(
                MakeRow("BOM_F", "Finished", "L", null, null),
                MakeRow("BOM_S", "Semi",     "L", null, null),
            ),
            bomByMakeKey = makeBomMap(
                bom("BOM_F", "Finished", "Semi"),
                bom("BOM_S", "Semi",     "Raw"),
            ),
        )
        result.nodes shouldHaveSize 3
        result.edges shouldHaveSize 2
        result.nodes.map { it.productId }.toSet() shouldContainExactlyInAnyOrder listOf("Finished", "Semi", "Raw")
    }

    // ── scenario 7: demand with no methods ───────────────────────────────────

    test("demand with no methods — 1 node, no edges, empty establishedBy") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = emptySet(),
            moveRows     = emptyList(),
            makeRows     = emptyList(),
            bomByMakeKey = emptyMap(),
        )
        result.nodes shouldHaveSize 1
        result.edges shouldHaveSize 0
        result.nodes.single().establishedBy.shouldBeEmpty()
        result.nodes.single().isDemand shouldBe true
    }

    // ── scenario 8: shared component ─────────────────────────────────────────

    test("shared component: P1 and P2 both require C — C is 1 node, 2 make edges") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P1", "L"), demand("P2", "L")),
            buySet       = setOf(buy("C", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(
                MakeRow("BOM1", "P1", "L", null, null),
                MakeRow("BOM2", "P2", "L", null, null),
            ),
            bomByMakeKey = makeBomMap(
                bom("BOM1", "P1", "C"),
                bom("BOM2", "P2", "C"),
            ),
        )
        result.nodes shouldHaveSize 3  // P1, P2, C (shared — single node)
        result.edges shouldHaveSize 2
        result.nodes.count { it.productId == "C" } shouldBe 1
        result.edges.filter { it.source == "C|L" } shouldHaveSize 2
    }

    // ── scenario 9: cycle guard ───────────────────────────────────────────────

    test("cycle A→B→A: both nodes visited exactly once, no infinite loop") {
        // A is made from B, B is made from A (cycle)
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("A", "L")),
            buySet       = emptySet(),
            moveRows     = emptyList(),
            makeRows     = listOf(
                MakeRow("BOM_A", "A", "L", null, null),
                MakeRow("BOM_B", "B", "L", null, null),
            ),
            bomByMakeKey = makeBomMap(
                bom("BOM_A", "A", "B"),
                bom("BOM_B", "B", "A"),
            ),
        )
        result.nodes shouldHaveSize 2
        result.nodes.map { it.productId }.toSet() shouldContainExactlyInAnyOrder listOf("A", "B")
        // Edges: A←B and B←A, both emitted since both nodes are visited
        result.edges shouldHaveSize 2
    }

    // ── scenario 10: isDemand flag ────────────────────────────────────────────

    test("isDemand is set only on demand seed nodes") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("C", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(MakeRow("BOM1", "P", "L", null, null)),
            bomByMakeKey = makeBomMap(bom("BOM1", "P", "C")),
        )
        result.nodes.single { it.productId == "P" }.isDemand shouldBe true
        result.nodes.single { it.productId == "C" }.isDemand shouldBe false
    }

    // ── scenario 11: BOM rate propagated to edge ──────────────────────────────

    test("BOM rate is carried on make edge") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("C", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(MakeRow("BOM1", "P", "L", null, null)),
            bomByMakeKey = makeBomMap(bom("BOM1", "P", "C", rate = 3.5)),
        )
        result.edges.single().rate shouldBe 3.5
    }

    // ── scenario 12: node not reachable from any demand is excluded ───────────

    test("unreachable node not included in graph") {
        // BOM2/Q is defined but no demand seeds Q
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("C", "L"), buy("Q", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(MakeRow("BOM1", "P", "L", null, null)),
            bomByMakeKey = makeBomMap(bom("BOM1", "P", "C")),
        )
        result.nodes.none { it.productId == "Q" } shouldBe true
    }

    // ── scenario 13: node id format ──────────────────────────────────────────

    test("node ids are productId|locationId") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("ABC", "WH1")),
            buySet       = setOf(buy("ABC", "WH1")),
            moveRows     = emptyList(),
            makeRows     = emptyList(),
            bomByMakeKey = emptyMap(),
        )
        result.nodes.single().id shouldBe "ABC|WH1"
    }

    // ── scenario 14: descriptions passed through ─────────────────────────────

    test("product and location descriptions are attached to nodes") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("P", "L")),
            moveRows     = emptyList(),
            makeRows     = emptyList(),
            bomByMakeKey = emptyMap(),
            productDesc  = mapOf("P" to "My Product"),
            locationDesc = mapOf("L" to "Main Warehouse"),
        )
        val node = result.nodes.single()
        node.productDescription shouldBe "My Product"
        node.locationDescription shouldBe "Main Warehouse"
    }

    // ── scenario 15: all edge source/target ids appear in nodes ──────────────

    test("every edge source and target is a valid node id") {
        val result = buildBomGraphPure(
            demandRows   = setOf(demand("P", "L")),
            buySet       = setOf(buy("C1", "L"), buy("C2", "L")),
            moveRows     = emptyList(),
            makeRows     = listOf(MakeRow("BOM1", "P", "L", null, null)),
            bomByMakeKey = makeBomMap(
                bom("BOM1", "P", "C1", altGroup = "G1"),
                bom("BOM1", "P", "C2", altGroup = "G1"),
            ),
        )
        val nodeIds = result.nodes.map { it.id }.toSet()
        for (edge in result.edges) {
            nodeIds.contains(edge.source) shouldBe true
            nodeIds.contains(edge.target) shouldBe true
        }
    }
})
