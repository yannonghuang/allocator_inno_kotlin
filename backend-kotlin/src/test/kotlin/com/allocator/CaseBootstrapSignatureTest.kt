package com.allocator

import com.allocator.services.CaseBootstrap
import com.allocator.services.KbFingerprint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `signatureFor`'s fingerprint segments (casealloc/pref/ord/purchmat/constr) — the KB-signature
 * fix for silently colliding runs that differ only in critical-raw-allocation/supply-preferences/
 * demand-ordering/purchasable-materials/constraints state (see KbFingerprint.kt's own doc for
 * the full "why").
 *
 * These are pure-function tests over hand-built config JsonObjects — no DB. `signatureFor`
 * itself stays a pure JsonObject->String reader, so it's fully testable this way. Note
 * `CaseBootstrap.signatureForBootstrapCandidate` is NOT covered here — unlike when this file was
 * first written, it now unavoidably calls `KbFingerprint.buildFingerprint` (which queries
 * CasePurchasableMaterialConfigs/CaseConstraintConfigs live, even on the bootstrap "na" path,
 * since purchmat/constr are always real) — unlike casealloc/pref/ord it can no longer be
 * DB-free. That behavior is instead covered by the live case-173 bootstrap verification (see
 * this branch's own plan notes).
 */
class CaseBootstrapSignatureTest : FunSpec({

    fun baseConfig(): JsonObject = buildJsonObject {
        putJsonObject("method_selection") {
            put("mode", "preference")
            put("max_methods", 2)
            put("depth", 1)
            put("max_bom_depth", 3)
        }
        putJsonObject("consolidation") { put("enabled", true); put("period_days", 0) }
        put("purchase_allowed", false)
    }

    fun withFingerprint(
        casealloc: String, pref: String, ord: String,
        purchmat: String = "none", constr: String = "none",
    ): JsonObject = buildJsonObject {
        baseConfig().entries.forEach { (k, v) -> put(k, v) }
        putJsonObject("_kb_fingerprint") {
            put("casealloc", casealloc)
            put("pref", pref)
            put("ord", ord)
            put("purchmat", purchmat)
            put("constr", constr)
        }
    }

    test("config with no _kb_fingerprint key at all gets the 'legacy' sentinel on all five segments") {
        val sig = CaseBootstrap.signatureFor(baseConfig())
        sig shouldBe (CaseBootstrap.signatureFor(baseConfig()))  // deterministic, sanity check
        sig.contains("casealloc=legacy") shouldBe true
        sig.contains("pref=legacy") shouldBe true
        sig.contains("ord=legacy") shouldBe true
        sig.contains("purchmat=legacy") shouldBe true
        sig.contains("constr=legacy") shouldBe true
    }

    test("two otherwise-identical configs with different casealloc hashes get different signatures") {
        val a = withFingerprint("hash_aaa", "none", "none")
        val b = withFingerprint("hash_bbb", "none", "none")
        CaseBootstrap.signatureFor(a) shouldNotBe CaseBootstrap.signatureFor(b)
    }

    test("two otherwise-identical configs with different purchmat or constr hashes get different signatures") {
        val basePurchmat = withFingerprint("none", "none", "none", purchmat = "purch_aaa", constr = "none")
        val diffPurchmat = withFingerprint("none", "none", "none", purchmat = "purch_bbb", constr = "none")
        CaseBootstrap.signatureFor(basePurchmat) shouldNotBe CaseBootstrap.signatureFor(diffPurchmat)

        val baseConstr = withFingerprint("none", "none", "none", purchmat = "none", constr = "constr_aaa")
        val diffConstr = withFingerprint("none", "none", "none", purchmat = "none", constr = "constr_bbb")
        CaseBootstrap.signatureFor(baseConstr) shouldNotBe CaseBootstrap.signatureFor(diffConstr)
    }

    test("two otherwise-identical configs with the same fingerprint segments get the same signature") {
        val a = withFingerprint("hash_x", "3,0.3,0.3,0.4,hash_y", "hash_z", "hash_p", "hash_c")
        val b = withFingerprint("hash_x", "3,0.3,0.3,0.4,hash_y", "hash_z", "hash_p", "hash_c")
        CaseBootstrap.signatureFor(a) shouldBe CaseBootstrap.signatureFor(b)
    }

    test("'none' (genuinely empty table, but consulted) is distinct from 'na' (not consulted) and 'legacy' (predates the fingerprint scheme)") {
        val none = withFingerprint("none", "none", "none", "none", "none")
        val na = withFingerprint("na", "na", "na", "na", "na")
        val legacy = baseConfig()  // no _kb_fingerprint key at all
        val sigNone = CaseBootstrap.signatureFor(none)
        val sigNa = CaseBootstrap.signatureFor(na)
        val sigLegacy = CaseBootstrap.signatureFor(legacy)
        sigNone shouldNotBe sigNa
        sigNone shouldNotBe sigLegacy
        sigNa shouldNotBe sigLegacy
    }

    test("KbFingerprint.hashRows: empty row set hashes to null (distinguishes 'no rows' from 'rows exist')") {
        KbFingerprint.hashRows(emptyList()) shouldBe null
    }

    test("KbFingerprint.hashRows: insertion order doesn't affect the hash") {
        val forward = KbFingerprint.hashRows(listOf("a|1", "b|2", "c|3"))
        val shuffled = KbFingerprint.hashRows(listOf("c|3", "a|1", "b|2"))
        forward shouldNotBe null
        forward shouldBe shuffled
    }

    test("KbFingerprint.hashRows: different content produces a different hash") {
        val a = KbFingerprint.hashRows(listOf("a|1", "b|2"))
        val b = KbFingerprint.hashRows(listOf("a|1", "b|3"))
        a shouldNotBe b
    }
})
