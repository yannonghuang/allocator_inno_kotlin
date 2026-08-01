package com.allocator

import com.allocator.services.resolveMethodSelection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Parsing contract for method_selection config.
 *
 * `mode`/`depth`/`elaborate`/`score_weights`/`max_bom_depth` and the whole `variant_selection`
 * key were retired (PR #59, "retire dead config parameters") — none had a live consumer; real
 * waterfall scoring comes from the case-level Preferences KB instead. What's left:
 * `max_methods` (waterfall invocation cap), `root_waterfall`, `raw_material_sourcing`.
 */
class PlanningEngineSelectionConfigTest : FunSpec({

    // ── max_methods + split_mechanism ────────────────────────────────────────

    test("max_methods defaults to 2 when absent") {
        resolveMethodSelection(null).maxMethods shouldBe 2
        resolveMethodSelection(emptyMap()).maxMethods shouldBe 2
        resolveMethodSelection(mapOf("method_selection" to emptyMap<String, Any?>())).maxMethods shouldBe 2
    }

    test("max_methods explicit value is read") {
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("max_methods" to 3)
        )).maxMethods shouldBe 3
    }

    test("max_methods clamps non-numeric / out-of-range values to default 2") {
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("max_methods" to "abc")
        )).maxMethods shouldBe 2
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("max_methods" to 0)
        )).maxMethods shouldBe 2
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("max_methods" to -1)
        )).maxMethods shouldBe 2
    }

    test("max_methods accepts Number subclasses") {
        resolveMethodSelection(mapOf("method_selection" to mapOf("max_methods" to 5L))).maxMethods shouldBe 5
        resolveMethodSelection(mapOf("method_selection" to mapOf("max_methods" to 1.7))).maxMethods shouldBe 1
    }

    test("split_mechanism is silently ignored on input (legacy field)") {
        // Old plan_run configs may still carry split_mechanism; backend must not trip on it.
        val cfg = resolveMethodSelection(mapOf("method_selection" to mapOf("split_mechanism" to "score")))
        cfg.maxMethods shouldBe 2  // unaffected
    }

    // ── root_waterfall ────────────────────────────────────────────────────────

    test("root_waterfall defaults to true") {
        resolveMethodSelection(null).rootWaterfall shouldBe true
        resolveMethodSelection(mapOf("method_selection" to emptyMap<String, Any?>())).rootWaterfall shouldBe true
    }

    test("root_waterfall: only an explicit false opts back into legacy root-split") {
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("root_waterfall" to false)
        )).rootWaterfall shouldBe false
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("root_waterfall" to true)
        )).rootWaterfall shouldBe true
    }

    // ── raw_material_sourcing → equalSplitRawMaterials ───────────────────────

    test("raw_material_sourcing defaults to waterfall (equalSplitRawMaterials false)") {
        resolveMethodSelection(null).equalSplitRawMaterials shouldBe false
        resolveMethodSelection(mapOf("method_selection" to emptyMap<String, Any?>())).equalSplitRawMaterials shouldBe false
    }

    test("raw_material_sourcing=equal_split enables equalSplitRawMaterials") {
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("raw_material_sourcing" to "equal_split")
        )).equalSplitRawMaterials shouldBe true
    }

    test("raw_material_sourcing invalid value falls back to waterfall") {
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("raw_material_sourcing" to "banana")
        )).equalSplitRawMaterials shouldBe false
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    test("missing method_selection sub-map is handled like absent config") {
        resolveMethodSelection(mapOf("other_key" to "x")).maxMethods shouldBe 2
    }

    test("wrong-typed method_selection value is ignored") {
        resolveMethodSelection(mapOf("method_selection" to "not a map")).maxMethods shouldBe 2
    }
})
