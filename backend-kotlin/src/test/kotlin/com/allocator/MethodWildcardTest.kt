package com.allocator

import com.allocator.services.DataIndex
import com.allocator.services.PlanData
import com.allocator.services.getMethods
import com.allocator.services.isWildcardLocation
import com.allocator.services.productIdWildcardGroupKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * PRODUCT_ID/PROD_AREA wildcarding on method_make/method_move: a row's PRODUCT_ID cell may be
 * an individual product_id (current behavior), a PROD_AREA value (matches every product whose
 * productlocation.prod_area equals it), or `"*"`/`""` (matches every product) — see
 * productIdWildcardGroupKey's doc in PlanningEngine.kt. Covers both the slow (raw map) path and
 * the indexed (PlanData/DataIndex) fast path used by legacyCommit-driven plan runs.
 */
class MethodWildcardTest : FunSpec({

    fun productLocation(productId: String, locationId: String, prodArea: String) = mapOf<String, Any?>(
        "product_id" to productId, "location_id" to locationId, "prod_area" to prodArea,
    )

    fun mkData(
        methodMake: List<Map<String, Any?>> = emptyList(),
        methodMove: List<Map<String, Any?>> = emptyList(),
        productLocation: List<Map<String, Any?>> = emptyList(),
    ): Map<String, List<Map<String, Any?>>> = mapOf(
        "method_make" to methodMake,
        "method_move" to methodMove,
        "productlocation" to productLocation,
        "method_buy" to emptyList(),
        "bom" to emptyList(),
    )

    // ── classification primitive ────────────────────────────────────────────
    // Second arg is knownProdAreas (the distinct productlocation.prod_area values) — a POSITIVE
    // recognition test. A product_id absent from productlocation entirely (very common: not
    // every product needs a productlocation row) must still classify as individual, or its own
    // make/move rows silently become unreachable — see productIdWildcardGroupKey's own doc.
    test("productIdWildcardGroupKey: individual product_id not matching any known prod_area returns null") {
        productIdWildcardGroupKey("223-0011", setOf("raw")) shouldBe null
    }
    test("productIdWildcardGroupKey: product_id absent from productlocation entirely still returns null") {
        productIdWildcardGroupKey("FG", emptySet()) shouldBe null
    }
    test("productIdWildcardGroupKey: blank or '*' returns global sentinel") {
        productIdWildcardGroupKey("", setOf("raw")) shouldBe "*"
        productIdWildcardGroupKey(null, setOf("raw")) shouldBe "*"
        productIdWildcardGroupKey("*", setOf("raw")) shouldBe "*"
    }
    test("productIdWildcardGroupKey: a known PROD_AREA value is taken as that group's key") {
        productIdWildcardGroupKey("raw", setOf("raw")) shouldBe "raw"
    }

    // ── slow path (no PlanData wrapper) ─────────────────────────────────────
    test("slow path: PROD_AREA wildcard method_make matches every product in that area") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_RAW", "product_id" to "raw", "location_id" to "1000", "preference" to 5, "lead_time" to 2.0),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        val methods = getMethods("223-0011", "1000", data)
        methods shouldHaveSize 1
        methods[0]["type"] shouldBe "make"
        methods[0]["product_id"] shouldBe "223-0011"
        // Wildcard rows always get the derived bom_id (force-overridden — see the "bom_id
        // derivation" section below), not the row's own literal "BOM_RAW".
        methods[0]["bom_id"] shouldBe "BOM_223-0011"
    }

    test("slow path: PROD_AREA wildcard does not match a product in a different area") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_RAW", "product_id" to "raw", "location_id" to "1000", "preference" to 5, "lead_time" to 2.0),
            ),
            productLocation = listOf(productLocation("SUB-1", "1000", "sub_pcba")),
        )
        getMethods("SUB-1", "1000", data) shouldHaveSize 0
    }

    test("slow path: global '*' method_make matches any product regardless of area") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_ANY", "product_id" to "*", "location_id" to "1000", "preference" to 0, "lead_time" to 1.0),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        val methods = getMethods("223-0011", "1000", data)
        methods shouldHaveSize 1
        methods[0]["product_id"] shouldBe "223-0011"
    }

    test("slow path: blank PRODUCT_ID method_move matches any product") {
        val data = mkData(
            methodMove = listOf(
                mapOf("product_id" to "", "from_location_id" to "SUB_PCBA", "to_location_id" to "1000", "transit_time" to 2, "preference" to 0),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        val methods = getMethods("223-0011", "1000", data)
        methods shouldHaveSize 1
        methods[0]["type"] shouldBe "move"
        methods[0]["product_id"] shouldBe "223-0011"
        methods[0]["from_location_id"] shouldBe "SUB_PCBA"
    }

    test("slow path: individual product_id row wins/coexists alongside a PROD_AREA wildcard row (additive)") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_INDIV", "product_id" to "223-0011", "location_id" to "1000", "preference" to 10, "lead_time" to 3.0),
                mapOf("bom_id" to "BOM_RAW", "product_id" to "raw", "location_id" to "1000", "preference" to 5, "lead_time" to 2.0),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        val methods = getMethods("223-0011", "1000", data)
        methods shouldHaveSize 2
        // Individual row's explicit bom_id survives; the wildcard row's is force-derived instead
        // of its own literal "BOM_RAW" (same rule as the "bom_id derivation" section below).
        methods.map { it["bom_id"] } shouldContain "BOM_INDIV"
        methods.map { it["bom_id"] } shouldContain "BOM_223-0011"
    }

    test("slow path: PROD_AREA wildcard method_make composes with wildcard LOCATION_ID") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_RAW_ANY", "product_id" to "raw", "location_id" to "*", "preference" to 0, "lead_time" to 1.0),
            ),
            productLocation = listOf(productLocation("223-0011", "2000", "raw")),
        )
        val methods = getMethods("223-0011", "2000", data)
        methods shouldHaveSize 1
        methods[0]["product_id"] shouldBe "223-0011"
        methods[0]["location_id"] shouldBe "2000"
    }

    // ── bom_id derivation (CaseMethodMakes override rows carry no bom_id — see
    // deriveMakeBomId's own doc in PlanningEngine.kt: a wildcard PRODUCT_ID resolves to MULTIPLE
    // products, each with its own real BOM, so there's no single correct literal to store per
    // row; it's synthesized as "BOM_" + the resolved concrete product_id instead) ─────────────

    test("slow path: missing bom_id on an individual product_id row is derived as BOM_<product_id>") {
        val data = mkData(
            methodMake = listOf(
                mapOf("product_id" to "223-0011", "location_id" to "1000", "preference" to 10),
            ),
        )
        val methods = getMethods("223-0011", "1000", data)
        methods shouldHaveSize 1
        methods[0]["bom_id"] shouldBe "BOM_223-0011"
    }

    test("slow path: missing bom_id on a PROD_AREA wildcard row is derived PER resolved product") {
        val data = mkData(
            methodMake = listOf(
                mapOf("product_id" to "raw", "location_id" to "1000", "preference" to 5),
            ),
            productLocation = listOf(
                productLocation("223-0011", "1000", "raw"),
                productLocation("223-0012", "1000", "raw"),
            ),
        )
        getMethods("223-0011", "1000", data)[0]["bom_id"] shouldBe "BOM_223-0011"
        getMethods("223-0012", "1000", data)[0]["bom_id"] shouldBe "BOM_223-0012"
    }

    test("slow path: missing bom_id on a global '*' wildcard row is derived PER resolved product") {
        val data = mkData(
            methodMake = listOf(
                mapOf("product_id" to "*", "location_id" to "1000", "preference" to 0),
            ),
        )
        getMethods("223-0011", "1000", data)[0]["bom_id"] shouldBe "BOM_223-0011"
        getMethods("999-9999", "1000", data)[0]["bom_id"] shouldBe "BOM_999-9999"
    }

    test("slow path: an EXPLICIT bom_id on an INDIVIDUAL row (e.g. CSV-imported, possibly location-suffixed) is never overwritten") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_223-0011_SUB_PCBA", "product_id" to "223-0011", "location_id" to "1000"),
            ),
        )
        getMethods("223-0011", "1000", data)[0]["bom_id"] shouldBe "BOM_223-0011_SUB_PCBA"
    }

    test("slow path: a WILDCARD row's bom_id is ALWAYS derived, even if the row carries an explicit value") {
        // Wildcarding in method_make.csv is meant for expansion — one row resolving to many
        // concrete products — so a literal BOM_ID on a wildcard row (however it got there: CSV
        // authoring, a stray value) can be correct for at most one of those products. Unlike the
        // individual-row case above, this one is force-overridden, not merely filled-when-blank.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_SOMETHING_WRONG", "product_id" to "raw", "location_id" to "1000"),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        getMethods("223-0011", "1000", data)[0]["bom_id"] shouldBe "BOM_223-0011"
    }

    test("slow path: a global '*' wildcard row's bom_id is ALWAYS derived, even if the row carries an explicit value") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_SOMETHING_WRONG", "product_id" to "*", "location_id" to "1000"),
            ),
        )
        getMethods("223-0011", "1000", data)[0]["bom_id"] shouldBe "BOM_223-0011"
    }

    // ── fast path (PlanData/DataIndex) ──────────────────────────────────────
    fun buildIndex(data: Map<String, List<Map<String, Any?>>>): DataIndex {
        val knownProdAreas = (data["productlocation"] ?: emptyList())
            .mapNotNull { (it["prod_area"] as? String)?.trim() }.filter { it.isNotEmpty() }.toSet()
        val makeIndividual = (data["method_make"] ?: emptyList())
            .filter { productIdWildcardGroupKey(it["product_id"] as? String, knownProdAreas) == null }
        val makeGroup = (data["method_make"] ?: emptyList())
            .mapNotNull { row -> productIdWildcardGroupKey(row["product_id"] as? String, knownProdAreas)?.let { it to row } }
        val moveIndividual = (data["method_move"] ?: emptyList())
            .filter { productIdWildcardGroupKey(it["product_id"] as? String, knownProdAreas) == null }
        val moveGroup = (data["method_move"] ?: emptyList())
            .mapNotNull { row -> productIdWildcardGroupKey(row["product_id"] as? String, knownProdAreas)?.let { it to row } }
        return DataIndex(
            makeByPidLid = makeIndividual.filterNot { isWildcardLocation(it["location_id"] as? String) }
                .groupBy { Pair(it["product_id"]?.toString()?.trim() ?: "", it["location_id"]?.toString()?.trim() ?: "") },
            moveByPidToLid = moveIndividual.filterNot { isWildcardLocation(it["to_location_id"] as? String) }
                .groupBy { Pair(it["product_id"]?.toString()?.trim() ?: "", it["to_location_id"]?.toString()?.trim() ?: "") },
            buyByPidLid = emptyMap(),
            makeWildcardByPid = makeIndividual.filter { isWildcardLocation(it["location_id"] as? String) }
                .groupBy { it["product_id"]?.toString()?.trim() ?: "" },
            moveWildcardByPid = moveIndividual.filter { isWildcardLocation(it["to_location_id"] as? String) }
                .groupBy { it["product_id"]?.toString()?.trim() ?: "" },
            makeByGroupLid = makeGroup.filterNot { (_, row) -> isWildcardLocation(row["location_id"] as? String) }
                .groupBy({ (gk, row) -> Pair(gk, row["location_id"]?.toString()?.trim() ?: "") }, { (_, row) -> row }),
            moveByGroupToLid = moveGroup.filterNot { (_, row) -> isWildcardLocation(row["to_location_id"] as? String) }
                .groupBy({ (gk, row) -> Pair(gk, row["to_location_id"]?.toString()?.trim() ?: "") }, { (_, row) -> row }),
            makeWildcardLocByGroup = makeGroup.filter { (_, row) -> isWildcardLocation(row["location_id"] as? String) }
                .groupBy({ (gk, _) -> gk }, { (_, row) -> row }),
            moveWildcardLocByGroup = moveGroup.filter { (_, row) -> isWildcardLocation(row["to_location_id"] as? String) }
                .groupBy({ (gk, _) -> gk }, { (_, row) -> row }),
            prodAreaByProductId = (data["productlocation"] ?: emptyList()).mapNotNull { row ->
                val pid = (row["product_id"] as? String)?.trim()
                val area = (row["prod_area"] as? String)?.trim()
                if (pid.isNullOrEmpty() || area.isNullOrEmpty()) null else pid to area
            }.toMap(),
            bomByBomId = emptyMap(),
            bomByParentId = emptyMap(),
        )
    }

    test("fast path (PlanData): PROD_AREA wildcard method_make resolves same as slow path") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_RAW", "product_id" to "raw", "location_id" to "1000", "preference" to 5, "lead_time" to 2.0),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        val indexed = PlanData(data, buildIndex(data))
        val methods = getMethods("223-0011", "1000", indexed)
        methods shouldHaveSize 1
        methods[0]["product_id"] shouldBe "223-0011"
        // Wildcard rows always get the derived bom_id (force-overridden), not the row's own
        // literal "BOM_RAW" — see the "bom_id derivation" section's fast-path tests.
        methods[0]["bom_id"] shouldBe "BOM_223-0011"
    }

    test("fast path (PlanData): missing bom_id on a PROD_AREA wildcard row is derived PER resolved product") {
        val data = mkData(
            methodMake = listOf(
                mapOf("product_id" to "raw", "location_id" to "1000", "preference" to 5),
            ),
            productLocation = listOf(
                productLocation("223-0011", "1000", "raw"),
                productLocation("223-0012", "1000", "raw"),
            ),
        )
        val indexed = PlanData(data, buildIndex(data))
        getMethods("223-0011", "1000", indexed)[0]["bom_id"] shouldBe "BOM_223-0011"
        getMethods("223-0012", "1000", indexed)[0]["bom_id"] shouldBe "BOM_223-0012"
    }

    test("fast path (PlanData): a WILDCARD row's bom_id is ALWAYS derived, even if the row carries an explicit value") {
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM_SOMETHING_WRONG", "product_id" to "raw", "location_id" to "1000"),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        val indexed = PlanData(data, buildIndex(data))
        getMethods("223-0011", "1000", indexed)[0]["bom_id"] shouldBe "BOM_223-0011"
    }

    test("fast path (PlanData): individual product absent from productlocation entirely still resolves (regression)") {
        // FG has no productlocation row at all (only R1, an unrelated BOM child, does) — this
        // must NOT make FG's own make row unreachable via the indexed path used by real plan()
        // runs; see productIdWildcardGroupKey's "absent from productlocation" doc.
        val data = mkData(
            methodMake = listOf(
                mapOf("bom_id" to "BOM1", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            ),
            productLocation = listOf(productLocation("R1", "L", "raw")),
        )
        val indexed = PlanData(data, buildIndex(data))
        val methods = getMethods("FG", "L", indexed)
        methods shouldHaveSize 1
        methods[0]["type"] shouldBe "make"
        methods[0]["product_id"] shouldBe "FG"
    }

    test("fast path (PlanData): global wildcard method_move with wildcard from-location resolves") {
        val data = mkData(
            methodMove = listOf(
                mapOf("product_id" to "*", "from_location_id" to "*", "to_location_id" to "*", "transit_time" to 2, "preference" to 0),
            ),
            productLocation = listOf(productLocation("223-0011", "1000", "raw")),
        )
        val dataWithLocations = data + mapOf("location" to listOf(mapOf("location_id" to "1000"), mapOf("location_id" to "2000")))
        val indexed = PlanData(dataWithLocations, buildIndex(dataWithLocations))
        val methods = getMethods("223-0011", "1000", indexed)
        methods shouldHaveSize 1
        methods[0]["product_id"] shouldBe "223-0011"
        methods[0]["to_location_id"] shouldBe "1000"
        methods[0]["from_location_id"] shouldBe "2000"
    }
})
