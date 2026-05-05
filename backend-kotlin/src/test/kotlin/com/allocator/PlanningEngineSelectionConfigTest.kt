package com.allocator

import com.allocator.services.resolveMethodSelection
import com.allocator.services.resolveVariantSelection
import com.allocator.services.shouldElaborateAtDepth
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Parsing contract for method_selection / variant_selection config.
 *
 * Method side: { mode?, depth, multiple }. Legacy shape
 * `method_selection: { elaborate: true }` maps to `mode: "elaborate"`.
 * `depth` clamps to int ≥ 1 (default 1).
 *
 * Variant side: { multiple, score_weights, top_n }. No mode / depth —
 * BOM-level variety is now modeled as distinct make methods, so there is
 * no variant-level scoring scope to rectify.
 */
class PlanningEngineSelectionConfigTest : FunSpec({

    // ── method_selection ────────────────────────────────────────────────────

    test("method_selection defaults to preference mode, depth 1, multiple false") {
        val cfg = resolveMethodSelection(null)
        cfg.mode shouldBe "preference"
        cfg.elaborate shouldBe false
        cfg.depth shouldBe 1
        cfg.multiple shouldBe false
    }

    test("method_selection reads new mode=elaborate shape") {
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("mode" to "elaborate", "depth" to 3)
        ))
        cfg.mode shouldBe "elaborate"
        cfg.elaborate shouldBe true
        cfg.depth shouldBe 3
    }

    test("method_selection backwards-compat: elaborate=true maps to mode=elaborate") {
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("elaborate" to true)
        ))
        cfg.mode shouldBe "elaborate"
        cfg.elaborate shouldBe true
        cfg.depth shouldBe 1
    }

    test("method_selection backwards-compat: elaborate=false stays preference") {
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("elaborate" to false)
        ))
        cfg.mode shouldBe "preference"
        cfg.elaborate shouldBe false
    }

    test("method_selection mode wins when both mode and elaborate are present") {
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("mode" to "preference", "elaborate" to true)
        ))
        cfg.mode shouldBe "preference"
        cfg.elaborate shouldBe false
    }

    test("method_selection invalid mode falls back to preference") {
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("mode" to "banana")
        ))
        cfg.mode shouldBe "preference"
    }

    test("method_selection mode is case-insensitive") {
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("mode" to "ELABORATE")
        ))
        cfg.mode shouldBe "elaborate"
    }

    test("method_selection depth clamps to 1 when zero or negative") {
        resolveMethodSelection(mapOf("method_selection" to mapOf("depth" to 0))).depth shouldBe 1
        resolveMethodSelection(mapOf("method_selection" to mapOf("depth" to -5))).depth shouldBe 1
    }

    test("method_selection depth clamps to 1 when non-numeric") {
        resolveMethodSelection(mapOf("method_selection" to mapOf("depth" to "deep"))).depth shouldBe 1
    }

    test("method_selection depth accepts numeric strings via Number path only") {
        // Deliberately strict: only Number subclasses are accepted; strings clamp.
        resolveMethodSelection(mapOf("method_selection" to mapOf("depth" to 7L))).depth shouldBe 7
        resolveMethodSelection(mapOf("method_selection" to mapOf("depth" to 2.0))).depth shouldBe 2
    }

    test("method_selection multiple is read") {
        resolveMethodSelection(mapOf("method_selection" to mapOf("multiple" to true))).multiple shouldBe true
        resolveMethodSelection(mapOf("method_selection" to mapOf("multiple" to false))).multiple shouldBe false
    }

    // ── method_selection.max_methods + split_mechanism (NEW) ─────────────────

    test("max_methods defaults to 2 when neither max_methods nor multiple is set") {
        resolveMethodSelection(null).maxMethods shouldBe 2
        resolveMethodSelection(emptyMap()).maxMethods shouldBe 2
        resolveMethodSelection(mapOf("method_selection" to emptyMap<String, Any?>())).maxMethods shouldBe 2
    }

    test("max_methods explicit value wins over legacy multiple") {
        // Explicit 3 even though multiple=false would otherwise give 1.
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("multiple" to false, "max_methods" to 3)
        )).maxMethods shouldBe 3
        // Explicit 1 even though multiple=true would otherwise give 2.
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("multiple" to true, "max_methods" to 1)
        )).maxMethods shouldBe 1
    }

    test("max_methods legacy resolution: multiple=false → 1, multiple=true → 2") {
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("multiple" to false)
        )).maxMethods shouldBe 1
        resolveMethodSelection(mapOf(
            "method_selection" to mapOf("multiple" to true)
        )).maxMethods shouldBe 2
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
        cfg.mode shouldBe "preference"
    }

    // ── method_selection.score_weights (drives elaborate scoring) ────────────

    test("method_selection.score_weights is passed through") {
        val w = mapOf("commit_time" to 1, "inventory_consumed" to 2, "purchase" to 3)
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("mode" to "elaborate", "score_weights" to w)
        ))
        cfg.scoreWeights shouldBe w
    }

    test("method_selection.score_weights falls back to variant_selection.score_weights") {
        val w = mapOf("commit_time" to 1, "inventory_consumed" to 0, "purchase" to 0)
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("mode" to "elaborate"),
            "variant_selection" to mapOf("score_weights" to w),
        ))
        cfg.scoreWeights shouldBe w
    }

    test("method_selection.score_weights wins over variant_selection.score_weights") {
        val methodW = mapOf("commit_time" to 5, "inventory_consumed" to 5, "purchase" to 5)
        val variantW = mapOf("commit_time" to 1, "inventory_consumed" to 0, "purchase" to 0)
        val cfg = resolveMethodSelection(mapOf(
            "method_selection" to mapOf("score_weights" to methodW),
            "variant_selection" to mapOf("score_weights" to variantW),
        ))
        cfg.scoreWeights shouldBe methodW
    }

    test("method_selection.score_weights is null when absent from both groups") {
        resolveMethodSelection(null).scoreWeights shouldBe null
        resolveMethodSelection(mapOf("method_selection" to mapOf("mode" to "elaborate"))).scoreWeights shouldBe null
    }

    // ── method_selection.max_bom_depth (make-fallback admission cap) ────────

    test("method_selection.max_bom_depth defaults to 3 when absent") {
        resolveMethodSelection(null).maxBomDepth shouldBe 3
        resolveMethodSelection(mapOf("method_selection" to mapOf("mode" to "elaborate"))).maxBomDepth shouldBe 3
    }

    test("method_selection.max_bom_depth integer is parsed and clamped to [1, 10]") {
        resolveMethodSelection(mapOf("method_selection" to mapOf("max_bom_depth" to 5))).maxBomDepth shouldBe 5
        resolveMethodSelection(mapOf("method_selection" to mapOf("max_bom_depth" to 0))).maxBomDepth shouldBe 1
        resolveMethodSelection(mapOf("method_selection" to mapOf("max_bom_depth" to 99))).maxBomDepth shouldBe 10
    }

    test("method_selection.max_bom_depth non-numeric falls back to default") {
        resolveMethodSelection(mapOf("method_selection" to mapOf("max_bom_depth" to "deep"))).maxBomDepth shouldBe 3
    }

    // ── variant_selection ────────────────────────────────────────────────────

    test("variant_selection defaults: multiple null, no weights/topN") {
        val cfg = resolveVariantSelection(null)
        cfg.multiple shouldBe null
        cfg.scoreWeights shouldBe null
        cfg.topN shouldBe null
    }

    test("variant_selection multiple=false is preserved (single best)") {
        resolveVariantSelection(mapOf("variant_selection" to mapOf("multiple" to false))).multiple shouldBe false
    }

    test("variant_selection multiple=true is preserved (equal split)") {
        resolveVariantSelection(mapOf("variant_selection" to mapOf("multiple" to true))).multiple shouldBe true
    }

    test("variant_selection score_weights and top_n are passed through") {
        val weights = mapOf("commit_time" to 1, "inventory_consumed" to 0, "purchase" to 0)
        val cfg = resolveVariantSelection(mapOf(
            "variant_selection" to mapOf(
                "multiple" to false,
                "score_weights" to weights,
                "top_n" to 3,
            )
        ))
        cfg.scoreWeights shouldBe weights
        cfg.topN shouldBe 3
    }

    test("variant_selection top_n clamps to 1 when below 1") {
        resolveVariantSelection(mapOf("variant_selection" to mapOf("top_n" to 0))).topN shouldBe 1
        resolveVariantSelection(mapOf("variant_selection" to mapOf("top_n" to -2))).topN shouldBe 1
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    test("missing method_selection sub-map is handled like absent config") {
        val cfg = resolveMethodSelection(mapOf("other_key" to "x"))
        cfg.mode shouldBe "preference"
        cfg.depth shouldBe 1
    }

    test("wrong-typed method_selection value is ignored") {
        val cfg = resolveMethodSelection(mapOf("method_selection" to "not a map"))
        cfg.mode shouldBe "preference"
        cfg.depth shouldBe 1
    }

    // ── shouldElaborateAtDepth (runtime gate) ───────────────────────────────
    //
    // Engine depth counts down from MAX_PLAN_DEPTH (500) at root. Level k has
    // depth == 500 - k. For N levels we want to cover levels 0..N-1, i.e.
    // depth > 500 - N.

    test("shouldElaborateAtDepth: levels=1 (default) covers root only") {
        shouldElaborateAtDepth(depth = 500, levels = 1) shouldBe true
        shouldElaborateAtDepth(depth = 499, levels = 1) shouldBe false
        shouldElaborateAtDepth(depth = 0,   levels = 1) shouldBe false
    }

    test("shouldElaborateAtDepth: levels=2 covers root + one level down") {
        shouldElaborateAtDepth(depth = 500, levels = 2) shouldBe true
        shouldElaborateAtDepth(depth = 499, levels = 2) shouldBe true
        shouldElaborateAtDepth(depth = 498, levels = 2) shouldBe false
    }

    test("shouldElaborateAtDepth: levels=5 covers levels 0..4") {
        shouldElaborateAtDepth(depth = 500, levels = 5) shouldBe true
        shouldElaborateAtDepth(depth = 496, levels = 5) shouldBe true
        shouldElaborateAtDepth(depth = 495, levels = 5) shouldBe false
    }

    test("shouldElaborateAtDepth: levels<=0 is clamped to 1 (root-only)") {
        shouldElaborateAtDepth(depth = 500, levels = 0)  shouldBe true
        shouldElaborateAtDepth(depth = 499, levels = 0)  shouldBe false
        shouldElaborateAtDepth(depth = 500, levels = -5) shouldBe true
        shouldElaborateAtDepth(depth = 499, levels = -5) shouldBe false
    }
})
