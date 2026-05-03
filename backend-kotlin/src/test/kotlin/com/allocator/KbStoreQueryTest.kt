package com.allocator

import com.allocator.services.KbStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the planning-agent KB tools' core logic.
 *
 * `applyFilterAndSort` and `paretoFrontierPure` are pure functions over a
 * `List<KbRecord>` — no DB. These tests exercise filter combinations, sort
 * directions, the dominance-check correctness, the KPI-allowlist gate, and
 * the small but easy-to-miss edge cases (missing snapshots, empty inputs).
 *
 * The DB-facing wrappers `KbStore.query` / `KbStore.paretoFrontier` are
 * one-liners around these primitives + `listForCase`, so end-to-end DB
 * coverage lives in the integration manual checklist.
 */
class KbStoreQueryTest : FunSpec({

    fun rec(
        id: Int,
        fill: Double? = null,
        gini: Double? = null,
        p10: Double? = null,
        starvation: Double? = null,
        soundness: String = "sound",
        primaryAxis: String? = null,
        presetId: String? = null,
    ): KbStore.KbRecord {
        val parts = mutableListOf<String>()
        if (fill != null) parts += "\"fill_rate_pct\":$fill"
        if (gini != null) parts += "\"gini\":$gini"
        if (p10 != null) parts += "\"p10_fill_ratio\":$p10"
        if (starvation != null) parts += "\"starvation_pct\":$starvation"
        val kpis = "{${parts.joinToString(",")}}"
        return KbStore.KbRecord(
            id = id,
            caseId = 1,
            signature = "sig-$id",
            presetId = presetId,
            presetLabel = null,
            primaryAxis = primaryAxis,
            configJson = "{}",
            kpisSnapshotJson = kpis,
            sourcePlanRunId = 1000 + id,
            sourcePlanRunDeleted = false,
            soundnessStatus = soundness,
        )
    }

    test("filter min_fill_rate keeps only rows with fill ≥ threshold") {
        val data = listOf(
            rec(1, fill = 25.0),
            rec(2, fill = 10.0),
            rec(3, fill = 22.0),
        )
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(minFillRate = 20.0),
            KbStore.KbSortBy.FILL_RATE_DESC,
            limit = 100,
        )
        out.map { it.id } shouldContainExactly listOf(1, 3)
    }

    test("filter max_gini keeps only rows with gini ≤ threshold") {
        val data = listOf(
            rec(1, gini = 0.18),
            rec(2, gini = 0.40),
            rec(3, gini = 0.20),
        )
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(maxGini = 0.20),
            KbStore.KbSortBy.GINI_ASC,
            limit = 100,
        )
        out.map { it.id } shouldContainExactly listOf(1, 3)
    }

    test("filter primary_axis matches exactly, drops mismatches and nulls") {
        val data = listOf(
            rec(1, primaryAxis = "consolidation.engine"),
            rec(2, primaryAxis = "method_selection.mode"),
            rec(3, primaryAxis = null),
            rec(4, primaryAxis = "consolidation.engine"),
        )
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(primaryAxis = "consolidation.engine"),
            KbStore.KbSortBy.NEWEST_FIRST,
            limit = 100,
        )
        out.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 4)
    }

    test("filter soundness_status drops unsound and unchecked when set to sound") {
        val data = listOf(
            rec(1, soundness = "sound"),
            rec(2, soundness = "unsound"),
            rec(3, soundness = "unchecked"),
        )
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(soundnessStatus = "sound"),
            KbStore.KbSortBy.NEWEST_FIRST,
            limit = 100,
        )
        out.map { it.id } shouldContainExactly listOf(1)
    }

    test("sort fill_rate_desc orders descending; missing fill goes last") {
        val data = listOf(
            rec(1, fill = 18.0),
            rec(2, fill = null),
            rec(3, fill = 25.0),
            rec(4, fill = 22.0),
        )
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(),
            KbStore.KbSortBy.FILL_RATE_DESC,
            limit = 100,
        )
        out.map { it.id } shouldContainExactly listOf(3, 4, 1, 2)
    }

    test("sort gini_asc orders ascending; missing gini goes last") {
        val data = listOf(
            rec(1, gini = 0.40),
            rec(2, gini = 0.20),
            rec(3, gini = null),
            rec(4, gini = 0.30),
        )
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(),
            KbStore.KbSortBy.GINI_ASC,
            limit = 100,
        )
        out.map { it.id } shouldContainExactly listOf(2, 4, 1, 3)
    }

    test("limit truncates after sort") {
        val data = (1..10).map { rec(it, fill = it.toDouble()) }
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(),
            KbStore.KbSortBy.FILL_RATE_DESC,
            limit = 3,
        )
        out.map { it.id } shouldContainExactly listOf(10, 9, 8)
    }

    test("filter + sort + limit compose correctly") {
        val data = listOf(
            rec(1, fill = 25.0, gini = 0.40),
            rec(2, fill = 10.0, gini = 0.18),
            rec(3, fill = 22.0, gini = 0.20),
            rec(4, fill = 24.0, gini = 0.30),
            rec(5, fill = 26.0, gini = 0.45),
        )
        val out = KbStore.applyFilterAndSort(
            data,
            KbStore.KbQueryFilter(maxGini = 0.30),
            KbStore.KbSortBy.FILL_RATE_DESC,
            limit = 2,
        )
        out.map { it.id } shouldContainExactly listOf(4, 3)
    }

    test("empty input returns empty") {
        val out = KbStore.applyFilterAndSort(
            emptyList(),
            KbStore.KbQueryFilter(),
            KbStore.KbSortBy.FILL_RATE_DESC,
            limit = 10,
        )
        out shouldBe emptyList()
    }

    test("paretoFrontierPure: 5-row hand-built dataset, frontier {A,C,E}") {
        // Maximize fill, minimize gini.
        // A(25, 0.40), B(20, 0.40), C(22, 0.30), D(20, 0.35), E(15, 0.20)
        // Dominance check:
        //   B is dominated by A (same gini, lower fill).
        //   D is dominated by C (lower fill, higher gini).
        //   A, C, E are non-dominated.
        val data = listOf(
            rec(1, fill = 25.0, gini = 0.40),  // A
            rec(2, fill = 20.0, gini = 0.40),  // B
            rec(3, fill = 22.0, gini = 0.30),  // C
            rec(4, fill = 20.0, gini = 0.35),  // D
            rec(5, fill = 15.0, gini = 0.20),  // E
        )
        val frontier = KbStore.paretoFrontierPure(
            data,
            maximize = listOf("fill_rate_pct"),
            minimize = listOf("gini"),
        )
        frontier.map { it.id } shouldContainExactly listOf(1, 3, 5) // sorted by fill desc
    }

    test("paretoFrontierPure: single-objective maximize reduces to argmax") {
        val data = listOf(
            rec(1, fill = 25.0),
            rec(2, fill = 30.0),
            rec(3, fill = 22.0),
        )
        val frontier = KbStore.paretoFrontierPure(
            data,
            maximize = listOf("fill_rate_pct"),
            minimize = emptyList(),
        )
        frontier.map { it.id } shouldContainExactly listOf(2)
    }

    test("paretoFrontierPure: all-dominated dataset returns the dominator") {
        // Row 1 dominates rows 2 and 3 on both axes.
        val data = listOf(
            rec(1, fill = 30.0, gini = 0.10),
            rec(2, fill = 20.0, gini = 0.30),
            rec(3, fill = 15.0, gini = 0.40),
        )
        val frontier = KbStore.paretoFrontierPure(
            data,
            maximize = listOf("fill_rate_pct"),
            minimize = listOf("gini"),
        )
        frontier.map { it.id } shouldContainExactly listOf(1)
    }

    test("paretoFrontierPure: rows missing required KPI are dropped") {
        // Row 2 has no gini → can't be evaluated → dropped.
        // Row 1 (high fill, low gini) and Row 3 (low fill, even lower gini) → both on frontier.
        val data = listOf(
            rec(1, fill = 25.0, gini = 0.30),
            rec(2, fill = 20.0, gini = null),
            rec(3, fill = 15.0, gini = 0.10),
        )
        val frontier = KbStore.paretoFrontierPure(
            data,
            maximize = listOf("fill_rate_pct"),
            minimize = listOf("gini"),
        )
        frontier.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 3)
    }

    test("paretoFrontierPure: empty input returns empty") {
        val frontier = KbStore.paretoFrontierPure(
            emptyList(),
            maximize = listOf("fill_rate_pct"),
            minimize = listOf("gini"),
        )
        frontier shouldBe emptyList()
    }

    test("paretoFrontierPure: ties on both axes are mutually non-dominated") {
        // Two rows with identical KPIs — neither strictly dominates the other.
        val data = listOf(
            rec(1, fill = 25.0, gini = 0.30),
            rec(2, fill = 25.0, gini = 0.30),
        )
        val frontier = KbStore.paretoFrontierPure(
            data,
            maximize = listOf("fill_rate_pct"),
            minimize = listOf("gini"),
        )
        frontier.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
    }

    test("paretoFrontier (DB-facing) validates KPI names and returns errors") {
        // We can't call the real DB-backed function here — but the validation
        // is independent of the DB and runs first. Confirm the allowlist
        // gate fires before any DB access.
        val result = KbStore.paretoFrontier(
            caseId = 999_999,  // unused — error path returns before DB access
            maximize = listOf("not_a_real_kpi"),
            minimize = listOf("gini"),
            prefilter = KbStore.KbQueryFilter(),
        )
        result.errors.size shouldBe 1
        result.errors[0].contains("not_a_real_kpi") shouldBe true
        result.frontier shouldBe emptyList()
    }

    test("paretoFrontier (DB-facing) requires at least one objective") {
        val result = KbStore.paretoFrontier(
            caseId = 999_999,
            maximize = emptyList(),
            minimize = emptyList(),
            prefilter = KbStore.KbQueryFilter(),
        )
        result.errors.size shouldBe 1
        result.errors[0].contains("at least one") shouldBe true
    }

    test("parseKpiSnapshot: missing fields produce missing keys, not zeros") {
        val rec = rec(1, fill = 25.0)  // only fill set
        val k = KbStore.parseKpiSnapshot(rec.kpisSnapshotJson)
        k["fill_rate_pct"] shouldBe 25.0
        k.containsKey("gini") shouldBe false
    }

    test("parseKpiSnapshot: blank or unparseable returns empty map") {
        KbStore.parseKpiSnapshot("") shouldBe emptyMap()
        KbStore.parseKpiSnapshot("{not valid json}") shouldBe emptyMap()
    }
})
