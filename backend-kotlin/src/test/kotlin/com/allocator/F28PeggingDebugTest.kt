package com.allocator

import com.allocator.services.runPlanning
import io.kotest.core.spec.style.FunSpec

/**
 * Diagnostic reproducer for the D11 / 502-2588 pegging bug.
 *
 * Mirrors the relevant slice of csv data:
 *   F28__677@VIRTUAL  (alt_groups 500-5391 OR 500-5614)
 *     500-5391@1000  -> 502-2588@1000   (rate 1)
 *     500-5614@1000  -> 502-2638@1000   (rate 1)
 *     502-2588@1000  -> 8 raw children at rate 2 (302-0006/320-0284 use rate 4)
 *
 * Supply: only 502-2588@1000 has 118 inventory; raw materials are scarce so the
 * consolidation pass for 502-2588 across 6 F28 demands produces less than the
 * 44,400-unit need.  This is the regime that the production data exercises, and
 * the regime where the user reports two 502-2588 entries in the D11 pegging
 * tree but only one 502-2588 WO in the ledger pegged to D11.
 */
class F28PeggingDebugTest : FunSpec({

    fun bom(parent: String, child: String, altGroup: String?, rate: Double, bomId: String = "BOM_$parent"): Map<String, Any?> =
        mapOf("bom_id" to bomId, "parent_id" to parent, "child_id" to child, "rate" to rate, "alt_group" to altGroup)

    fun mk(productId: String, locationId: String, preference: Int = -1, leadTime: Double = 3.0, bomId: String = "BOM_$productId"): Map<String, Any?> =
        mapOf("bom_id" to bomId, "product_id" to productId, "location_id" to locationId, "preference" to preference, "lead_time" to leadTime)

    fun supply(productId: String, locationId: String, qty: Double, supplyId: String): Map<String, Any?> = mapOf(
        "supply_id" to supplyId, "product_id" to productId, "location_id" to locationId, "supply_date" to "2024-01-01", "qty" to qty,
    )

    fun demand(id: String, productId: String, locationId: String, qty: Double, due: String, priority: Int): Map<String, Any?> = mapOf(
        "demand_id" to id, "product_id" to productId, "location_id" to locationId,
        "quantity" to qty, "request_due_time" to due, "request_time" to due,
        "priority" to priority, "customer_id" to "CUST", "customer" to "CUST",
    )

    test("dump D11 pegging tree shape — F28 chain with limited 502-2588 supply") {
        val data = mapOf(
            "bom" to listOf(
                bom("F28__677", "500-5391", "500-5391", 1.0, "BOM_F28__677"),
                bom("F28__677", "500-5614", "500-5614", 1.0, "BOM_F28__677"),
                bom("500-5391", "502-2588", "502-2588", 1.0, "BOM_500-5391_502-2588"),
                bom("500-5614", "502-2638", "502-2638", 1.0, "BOM_500-5614_502-2638"),
                bom("502-2588", "RAW_A", null, 2.0, "BOM_502-2588"),
                bom("502-2588", "RAW_B", null, 2.0, "BOM_502-2588"),
                bom("502-2638", "RAW_A", null, 2.0, "BOM_502-2638"),
                bom("502-2638", "RAW_B", null, 2.0, "BOM_502-2638"),
            ),
            "method_make" to listOf(
                mk("F28__677", "VIRTUAL", -1, 3.0, "BOM_F28__677"),
                mk("500-5391", "1000",    -1, 3.0, "BOM_500-5391_502-2588"),
                mk("500-5614", "1000",    -1, 3.0, "BOM_500-5614_502-2638"),
                mk("502-2588", "1000",    10000, 3.0, "BOM_502-2588"),
                mk("502-2638", "1000",    10000, 3.0, "BOM_502-2638"),
            ),
            "method_buy"  to emptyList<Map<String, Any?>>(),
            "method_move" to emptyList<Map<String, Any?>>(),
            "supply" to listOf(
                supply("502-2588", "1000", 118.0, "S_502_2588"),
                // Constrained raw materials: enough to make consolidation kick in
                // (groups>0) but short overall, so synthetic bucket allocations to
                // D7-D12 cannot fully cover D11's 6200-unit share. Goal: D11's main
                // plan recurses past the synthetic supply leaf and emits another
                // 502-2588 WO node.
                supply("RAW_A",    "1000", 20_000.0, "S_RAW_A"),
                supply("RAW_B",    "1000", 20_000.0, "S_RAW_B"),
            ),
            "productlocation" to emptyList<Map<String, Any?>>(),
            "overrides" to emptyList<Map<String, Any?>>(),
        )

        val demands = listOf(
            demand("D7",  "F28__677", "VIRTUAL", 8000.0, "2024-07-31",  30),
            demand("D8",  "F28__677", "VIRTUAL", 8000.0, "2024-08-31", 240),
            demand("D9",  "F28__677", "VIRTUAL", 8000.0, "2024-09-30", 420),
            demand("D10", "F28__677", "VIRTUAL", 8000.0, "2024-10-31", 590),
            demand("D11", "F28__677", "VIRTUAL", 6200.0, "2024-11-30", 750),
            demand("D12", "F28__677", "VIRTUAL", 6200.0, "2024-12-31", 870),
        )
        val withDemands = data + ("demand" to demands)

        val config = mapOf<String, Any?>(
            "purchase_allowed" to false,
            "consolidation"    to mapOf("enabled" to true, "period_days" to 365, "allocation_mode" to "fair"),
            "method_selection" to mapOf("multiple" to false),
            "variant_selection" to mapOf<String, Any?>(),
        )

        val result = runPlanning(withDemands, config)

        @Suppress("UNCHECKED_CAST")
        val planningPegging = result["planning_pegging"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val workOrders = result["work_orders"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val committed = result["committed_demands"] as List<Map<String, Any?>>

        println("=== planning_pegging entries (count=${planningPegging.size}) ===")
        for ((i, e) in planningPegging.withIndex()) {
            val did = e["demand_id"]
            val flags = listOfNotNull(
                if (e["consolidated"] == true) "consolidated" else null,
                if (e["passthrough"] == true)  "passthrough" else null,
            ).joinToString(",")
            @Suppress("UNCHECKED_CAST")
            val tree = e["tree"] as? Map<String, Any?>
            val rootPid = tree?.get("product_id"); val rootQty = tree?.get("quantity")
            val rootCommitted = tree?.get("committed_qty")
            println("[$i] demand_id=$did flags=[$flags] root=${rootPid} qty=$rootQty commit=$rootCommitted")
        }

        println()
        println("=== D11 main planning_pegging tree (full dump) ===")
        val d11Entry = planningPegging.first { (it["demand_id"]?.toString() ?: "").trim() == "D11" && it["consolidated"] != true && it["passthrough"] != true }
        @Suppress("UNCHECKED_CAST")
        printTree(d11Entry["tree"] as Map<String, Any?>, 0)

        println()
        println("=== consolidated entries with 502-2588 in tree ===")
        for ((i, e) in planningPegging.withIndex()) {
            if (e["consolidated"] != true) continue
            @Suppress("UNCHECKED_CAST")
            val tree = e["tree"] as? Map<String, Any?> ?: continue
            if (tree["product_id"] != "502-2588") continue
            val members = e["consolidated_demand_ids"]
            val pda = e["per_demand_allocations"]
            println("[$i] root=${tree["product_id"]}@${tree["location_id"]} qty=${tree["quantity"]} commit=${tree["committed_qty"]} members=$members pda=$pda")
        }

        println()
        println("=== 502-2588 work orders in ledger ===")
        for (wo in workOrders) {
            if (wo["product_id"] != "502-2588") continue
            println("demand_id=${wo["demand_id"]} qty=${wo["quantity"]} method=${wo["method"]} consolidated=${wo["consolidated"]} end=${wo["end_time"]} start=${wo["start_time"]}")
        }

        println()
        println("=== D11 committed_demands rows ===")
        for (c in committed) {
            if (c["demand_id"] != "D11") continue
            println("product=${c["product_id"]}@${c["location_id"]} qty=${c["quantity"]} reason=${c["commit_reason"]} commit_time=${c["commit_time"]}")
        }
    }
})

private fun printTree(node: Map<String, Any?>, depth: Int) {
    val pad = "  ".repeat(depth)
    val type = node["type"]
    val pid = node["product_id"]
    val lid = node["location_id"]
    val qty = node["quantity"]
    val commit = node["committed_qty"]
    val reason = node["commit_reason"]
    val supplyId = node["supply_id"]
    val method = node["method"]
    val rel = node["children_relation"]
    val extras = listOfNotNull(
        commit?.let { "commit=$it" },
        reason?.let { "reason=$it" },
        supplyId?.let { "sid=$it" },
        method?.let { "method=$it" },
        rel?.let { "rel=$it" },
    ).joinToString(" ")
    println("$pad[$type] $pid@$lid qty=$qty $extras")
    @Suppress("UNCHECKED_CAST")
    val children = node["children"] as? List<Map<String, Any?>> ?: emptyList()
    for (c in children) printTree(c, depth + 1)
}
