package com.allocator

import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Side-by-side comparison harness — runs scope=leaf-only and scope=all
 * on the same fixture and asserts equivalence/improvement properties.
 *
 * This is the migration safety net (Phase H of supply-level consolidation).
 * Each scenario compares:
 *
 *   - **Total committed qty**: supply ≥ leaf  (no demand fulfillment regression)
 *   - **Supply consumption**: both scopes respect supply caps  (no over-allocation)
 *   - **Output shape**: both produce a `committed_demands` list,
 *     `work_orders`, `planning_pegging`, `supply_allocations`
 *
 * Some scenarios additionally assert structural improvements scope=all
 * is expected to deliver (fewer WOs via better consolidation, exact equality
 * of committed qty under abundance, etc.).
 *
 * These tests deliberately use small synthetic fixtures so the assertion
 * targets are calculable by hand. For real-data validation against case 162,
 * see the manual verification steps in docs/supply-level-consolidation.md §9.
 */
class SupplyVsLeafEquivalenceTest : FunSpec({

    // ── Fixture helpers ───────────────────────────────────────────────────────

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

    fun consConfig(scope: String, mode: String = "fair"): Map<String, Any?> = mapOf(
        "consolidation" to mapOf(
            "enabled" to true,
            "scope" to scope,
            "allocation_mode" to mode,
        ),
    )

    // ── Result accessors ─────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    fun committedQty(result: Map<String, Any>): Double =
        (result["committed_demands"] as List<Map<String, Any?>>)
            .filterNot {
                val reason = it["commit_reason"] as? String
                reason in setOf("no_methods", "no_preferred_method", "depth_limit", "cycle_stopped")
                    || (reason?.startsWith("child_failed:") ?: false)
            }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

    @Suppress("UNCHECKED_CAST")
    fun woCount(result: Map<String, Any>): Int =
        (result["work_orders"] as List<Map<String, Any?>>).size

    @Suppress("UNCHECKED_CAST")
    fun supplyConsumed(result: Map<String, Any>, supplyId: String): Double =
        (result["supply_allocations"] as List<Map<String, Any?>>)
            .filter { it["supply_id"] == supplyId }
            .sumOf { (it["qty_consumed"] as? Number)?.toDouble() ?: 0.0 }

    // ── Scenario 1: simple single demand, abundant supply ────────────────────

    test("simple FG → RM, single demand: both scopes fully commit") {
        val data = mapOf(
            "demand" to listOf(demand("D1", "FG", "L1", 10.0)),
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val leaf = runPlanning(data, consConfig("leaf-only"))
        val supply = runPlanning(data, consConfig("all"))

        // Both fully commit the 10-unit demand.
        committedQty(leaf) shouldBe (10.0 plusOrMinus 1e-6)
        committedQty(supply) shouldBe (10.0 plusOrMinus 1e-6)

        // Both consume the same RM qty.
        supplyConsumed(leaf, "S_RM") shouldBe (10.0 plusOrMinus 1e-6)
        supplyConsumed(supply, "S_RM") shouldBe (10.0 plusOrMinus 1e-6)
    }

    // ── Scenario 2: two demands sharing deep RM (the case-162 pattern) ───────

    test("two demands sharing deep RM under abundance: both scopes fully commit") {
        val data = mapOf(
            "demand" to listOf(
                demand("D1", "FG_A", "L1", 10.0),
                demand("D2", "FG_B", "L1", 20.0),
            ),
            "bom" to listOf(
                bom("FG_A", "RM", rate = 1.0),
                bom("FG_B", "RM", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG_A", "L1"), mk("FG_B", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),  // abundant
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val leaf = runPlanning(data, consConfig("leaf-only"))
        val supply = runPlanning(data, consConfig("all"))

        // Both fully satisfy 10 + 20 = 30 of FG.
        committedQty(leaf) shouldBe (30.0 plusOrMinus 1e-6)
        committedQty(supply) shouldBe (30.0 plusOrMinus 1e-6)

        // Both consume 30 of RM.
        supplyConsumed(leaf, "S_RM") shouldBe (30.0 plusOrMinus 1e-6)
        supplyConsumed(supply, "S_RM") shouldBe (30.0 plusOrMinus 1e-6)

        // scope=all shouldn't emit MORE WOs than leaf (consolidation no worse).
        (woCount(supply) <= woCount(leaf) + 0).shouldBe(true)
    }

    test("two demands sharing deep RM under shortage: both scopes respect supply cap") {
        val data = mapOf(
            "demand" to listOf(
                demand("D1", "FG_A", "L1", 10.0),
                demand("D2", "FG_B", "L1", 10.0),
            ),
            "bom" to listOf(
                bom("FG_A", "RM", rate = 1.0),
                bom("FG_B", "RM", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG_A", "L1"), mk("FG_B", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 12.0, "S_RM")),  // shortage: 12 of 20 needed
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val leaf = runPlanning(data, consConfig("leaf-only", mode = "fair"))
        val supply = runPlanning(data, consConfig("all", mode = "fair"))

        // Both scopes fully commit 12 of FG total (supply-limited, BOM rate 1:1).
        committedQty(leaf) shouldBe (12.0 plusOrMinus 1e-6)
        committedQty(supply) shouldBe (12.0 plusOrMinus 1e-6)

        // Both scopes now correctly book 12 of S_RM as consumed. The earlier
        // bookkeeping gap (per-demand pegging trees missing supply leaves
        // when budgetCap fires mid-make-recursion) was traced to plan()'s
        // first-pass exploratory call decrementing budget in-place, leaving
        // the second pass with no headroom. Fixed by snapshotting budget
        // alongside inventory before the first pass and restoring before
        // the second.
        supplyConsumed(leaf, "S_RM") shouldBe (12.0 plusOrMinus 1e-6)
        supplyConsumed(supply, "S_RM") shouldBe (12.0 plusOrMinus 1e-6)
    }

    // ── Scenario 3: alt-branch divergence ─────────────────────────────────────

    test("alt branches with shared raw material under abundance: scope=all ≥ leaf for committed qty") {
        // FG → (B OR Bp) → RM. Both alts share the same downstream RM.
        // Single demand can pick either alt at commit; both scopes should
        // fully commit since RM is abundant.
        val data = mapOf(
            "demand" to listOf(demand("D1", "FG", "L1", 10.0)),
            "bom" to listOf(
                bom("FG", "B", rate = 1.0, altGroup = "or1"),
                bom("FG", "Bp", rate = 1.0, altGroup = "or1"),
                bom("B", "RM", rate = 1.0),
                bom("Bp", "RM", rate = 1.0),
            ),
            "method_make" to listOf(mk("FG", "L1"), mk("B", "L1"), mk("Bp", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val leaf = runPlanning(data, consConfig("leaf-only"))
        val supply = runPlanning(data, consConfig("all"))

        // scope=all must not regress below scope=leaf-only's commitment.
        (committedQty(supply) >= committedQty(leaf) - 1e-6).shouldBe(true)
        // Both should fully commit on this abundant fixture.
        committedQty(supply) shouldBe (10.0 plusOrMinus 1e-6)
    }

    // ── Scenario 4: priority-first under shortage ────────────────────────────

    test("priority_first under shortage: high-priority demand fully served") {
        val data = mapOf(
            "demand" to listOf(
                demand("D_low", "FG", "L1", 10.0, priority = 5),  // priority 5 (lower importance)
                demand("D_hi", "FG", "L1", 10.0, priority = 1),   // priority 1 (higher importance)
            ),
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 12.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val supply = runPlanning(data, consConfig("all", mode = "priority_first"))

        // priority_first: D_hi gets first dibs (full 10), D_low gets the rest (2).
        @Suppress("UNCHECKED_CAST")
        val committed = supply["committed_demands"] as List<Map<String, Any?>>
        val hiQty = committed.filter { it["demand_id"] == "D_hi" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }
        val lowQty = committed.filter { it["demand_id"] == "D_low" }
            .sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 }

        hiQty shouldBe (10.0 plusOrMinus 1e-6)
        lowQty shouldBe (2.0 plusOrMinus 1e-6)
    }

    // ── Scenario 5: no consolidation (scope flag irrelevant) ────────────────

    test("consolidation disabled: scope flag has no effect (both scopes untouched)") {
        // When consolidation.enabled = false, runPlanning skips both scopes and
        // calls legacyCommit directly. The scope flag should be ignored.
        val data = mapOf(
            "demand" to listOf(demand("D1", "FG", "L1", 10.0)),
            "bom" to listOf(bom("FG", "RM", rate = 1.0)),
            "method_make" to listOf(mk("FG", "L1")),
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(supply("RM", "L1", 100.0, "S_RM")),
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val noEngine = runPlanning(data, mapOf("consolidation" to mapOf("enabled" to false)))
        val withSupplyFlag = runPlanning(
            data,
            mapOf("consolidation" to mapOf("enabled" to false, "scope" to "all")),
        )

        committedQty(noEngine) shouldBe committedQty(withSupplyFlag)
        woCount(noEngine) shouldBe woCount(withSupplyFlag)
    }

    // ── Scenario 6: supply caps respected under both scopes ─────────────────

    test("supply consumption never exceeds supply qty under either scope") {
        // Fan-out scenario: 5 demands sharing one limited RM.
        val supplies = listOf(supply("RM", "L1", 25.0, "S_RM"))
        val data = mapOf(
            "demand" to (1..5).map { demand("D$it", "FG_$it", "L1", 10.0) },
            "bom" to (1..5).map { bom("FG_$it", "RM", rate = 1.0) },
            "method_make" to (1..5).map { mk("FG_$it", "L1") },
            "method_buy" to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to supplies,
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val leaf = runPlanning(data, consConfig("leaf-only", mode = "fair"))
        val supply = runPlanning(data, consConfig("all", mode = "fair"))

        // Both scopes respect the 25-unit RM cap.
        (supplyConsumed(leaf, "S_RM") <= 25.0 + 1e-6).shouldBe(true)
        (supplyConsumed(supply, "S_RM") <= 25.0 + 1e-6).shouldBe(true)
    }
})
