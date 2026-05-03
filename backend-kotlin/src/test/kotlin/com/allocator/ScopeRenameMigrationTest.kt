package com.allocator

import com.allocator.services.ScopeRenameMigration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Unit tests for the in-memory rewrite helpers used by the engine→scope
 * data migration. The DB-walking entry point `ScopeRenameMigration.run()` is
 * exercised end-to-end at backend startup against the real database; these
 * tests pin the pure transformation rules so future refactors don't drift.
 */
class ScopeRenameMigrationTest : FunSpec({

    val parser = Json { ignoreUnknownKeys = true; isLenient = true }
    fun obj(s: String) = parser.parseToJsonElement(s).jsonObject

    test("rewriteConfig translates engine=supply → scope=all and drops engine key") {
        val before = obj("""{"consolidation": {"enabled": true, "engine": "supply", "period_days": 0}}""")
        val after = ScopeRenameMigration.rewriteConfig(before)!!
        val cs = after["consolidation"]!!.jsonObject
        cs.containsKey("engine") shouldBe false
        cs["scope"]!!.jsonPrimitive.content shouldBe "all"
        cs["enabled"]!!.jsonPrimitive.content shouldBe "true"
        cs["period_days"]!!.jsonPrimitive.content shouldBe "0"
    }

    test("rewriteConfig translates engine=leaf-legacy → scope=leaf-only") {
        val before = obj("""{"consolidation": {"engine": "leaf-legacy"}}""")
        val after = ScopeRenameMigration.rewriteConfig(before)!!
        after["consolidation"]!!.jsonObject["scope"]!!.jsonPrimitive.content shouldBe "leaf-only"
    }

    test("rewriteConfig is idempotent on already-migrated rows") {
        val before = obj("""{"consolidation": {"scope": "all", "enabled": true}}""")
        ScopeRenameMigration.rewriteConfig(before) shouldBe null
    }

    test("rewriteConfig leaves config alone when consolidation block is absent") {
        val before = obj("""{"purchase_allowed": true}""")
        ScopeRenameMigration.rewriteConfig(before) shouldBe null
    }

    test("rewriteConfig leaves config alone when neither engine nor scope present") {
        val before = obj("""{"consolidation": {"enabled": true}}""")
        ScopeRenameMigration.rewriteConfig(before) shouldBe null
    }

    test("rewriteConfig: when both engine and scope present, translated engine wins") {
        // Defensive: a manually-edited row could have both keys. The historical
        // engine field is the source of truth, so its translation overrides.
        val before = obj("""{"consolidation": {"engine": "supply", "scope": "leaf-only"}}""")
        val after = ScopeRenameMigration.rewriteConfig(before)!!
        after["consolidation"]!!.jsonObject["scope"]!!.jsonPrimitive.content shouldBe "all"
        after["consolidation"]!!.jsonObject.containsKey("engine") shouldBe false
    }

    test("rewriteConfig: unknown engine value passes through unchanged (translateValue is permissive)") {
        // Defensive — an engine value we don't recognize shouldn't crash the
        // migration; it just lands as-is under the new `scope` key.
        val before = obj("""{"consolidation": {"engine": "experimental"}}""")
        val after = ScopeRenameMigration.rewriteConfig(before)!!
        after["consolidation"]!!.jsonObject["scope"]!!.jsonPrimitive.content shouldBe "experimental"
    }

    test("rewriteMetadata translates primary_axis 'engine' → 'scope'") {
        val before = obj("""{"bootstrap": true, "primary_axis": "engine", "preset_id": "engine=supply"}""")
        val after = ScopeRenameMigration.rewriteMetadata(before)!!
        after["primary_axis"]!!.jsonPrimitive.content shouldBe "scope"
        // Other fields preserved
        after["bootstrap"]!!.jsonPrimitive.content shouldBe "true"
        after["preset_id"]!!.jsonPrimitive.content shouldBe "engine=supply"
    }

    test("rewriteMetadata is idempotent on already-migrated rows") {
        val before = obj("""{"primary_axis": "scope"}""")
        ScopeRenameMigration.rewriteMetadata(before) shouldBe null
    }

    test("rewriteMetadata is no-op when primary_axis is some other axis") {
        val before = obj("""{"primary_axis": "max_methods"}""")
        ScopeRenameMigration.rewriteMetadata(before) shouldBe null
    }

    test("translatePresetId translates the two known preset ids") {
        ScopeRenameMigration.translatePresetId("engine=supply") shouldBe "scope=all"
        ScopeRenameMigration.translatePresetId("engine=leaf-legacy") shouldBe "scope=leaf-only"
    }

    test("translatePresetId is idempotent + leaves unrelated preset ids alone") {
        ScopeRenameMigration.translatePresetId("scope=all") shouldBe "scope=all"
        ScopeRenameMigration.translatePresetId("max=4") shouldBe "max=4"
        ScopeRenameMigration.translatePresetId(null) shouldBe null
    }

    test("translatePrimaryAxis translates 'engine' and is idempotent") {
        ScopeRenameMigration.translatePrimaryAxis("engine") shouldBe "scope"
        ScopeRenameMigration.translatePrimaryAxis("scope") shouldBe "scope"
        ScopeRenameMigration.translatePrimaryAxis("mode") shouldBe "mode"
        ScopeRenameMigration.translatePrimaryAxis(null) shouldBe null
    }

    test("translateSignature rewrites eng=leaf-legacy / eng=supply in place; preserves distinctness") {
        val sigA = "m=preference|max=2|d=1|dopt=false|w=0.4,0.35,0.25|eng=leaf-legacy|alloc=fair|cons=true|p=0|purch=false"
        val sigB = "m=preference|max=2|d=1|dopt=false|w=0.4,0.35,0.25|eng=supply|alloc=fair|cons=true|p=0|purch=false"
        val outA = ScopeRenameMigration.translateSignature(sigA)!!
        val outB = ScopeRenameMigration.translateSignature(sigB)!!
        outA shouldBe "m=preference|max=2|d=1|dopt=false|w=0.4,0.35,0.25|scope=leaf-only|alloc=fair|cons=true|p=0|purch=false"
        outB shouldBe "m=preference|max=2|d=1|dopt=false|w=0.4,0.35,0.25|scope=all|alloc=fair|cons=true|p=0|purch=false"
        // Critical: pre-existing dedup distinctness is preserved.
        (outA == outB) shouldBe false
    }

    test("translateSignature returns null on already-migrated signatures (idempotent)") {
        val migrated = "m=preference|max=2|d=1|dopt=false|w=0.4,0.35,0.25|scope=all|alloc=fair|cons=true|p=0|purch=false"
        ScopeRenameMigration.translateSignature(migrated) shouldBe null
    }

    test("translateSignature preserves float-formatting differences (latent dedup behavior)") {
        // Two pre-existing signatures differ only by weight formatting (0.0 vs 0).
        // Recomputing would collapse them; literal translation must not.
        val sigA = "m=elaborate|max=1|d=1|dopt=false|w=0.0,0.0,1.0|eng=leaf-legacy|alloc=fair|cons=true|p=0|purch=false"
        val sigB = "m=elaborate|max=1|d=1|dopt=false|w=0,0,1|eng=leaf-legacy|alloc=fair|cons=true|p=0|purch=false"
        val outA = ScopeRenameMigration.translateSignature(sigA)!!
        val outB = ScopeRenameMigration.translateSignature(sigB)!!
        (outA == outB) shouldBe false
    }
})
