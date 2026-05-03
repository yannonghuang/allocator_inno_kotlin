package com.allocator

import com.allocator.api.jsonElementToNative
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

/**
 * Regression test for the JSON-string-to-native bug that caused
 * R7d_purchase_lid_missing soundness violations on valid plan runs.
 *
 * Background: `jsonElementToNative` is the bridge from kotlinx-serialization's
 * JsonElement to plain Kotlin types (Map / List / Long / Double / Boolean /
 * String). It feeds the soundness checker, planner re-loads, and several
 * agent tools. The bug was that `JsonPrimitive.longOrNull` parses the
 * primitive's *content* regardless of the JSON-level isString flag — so a
 * properly-quoted string like `"1000"` (a valid location_id) got returned
 * as `Long(1000L)`, and any downstream `as? String` cast (the soundness
 * checker's `walkPurchase` does this for `location_id`) silently failed,
 * producing spurious R7d violations on every chat-driven `purchase=on`
 * plan run for case 171.
 *
 * Fix: respect `isString` first — quoted strings stay strings, only
 * unquoted primitives get the boolean/long/double parse chain.
 */
class JsonElementToNativeTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true }

    test("quoted numeric strings stay strings (the R7d bug)") {
        val parsed = json.parseToJsonElement("""{"location_id": "1000", "product_id": "300-0150"}""")
        val native = jsonElementToNative(parsed) as Map<*, *>
        // CRITICAL: location_id was quoted in JSON → must remain a String.
        // Pre-fix this returned Long(1000L) because longOrNull parsed the content.
        native["location_id"].shouldBeInstanceOf<String>()
        native["location_id"] shouldBe "1000"
        native["product_id"] shouldBe "300-0150"
    }

    test("unquoted numbers become Long / Double") {
        val parsed = json.parseToJsonElement("""{"qty": 134, "rate": 1.5, "count": 22.0}""")
        val native = jsonElementToNative(parsed) as Map<*, *>
        native["qty"].shouldBeInstanceOf<Long>()
        native["qty"] shouldBe 134L
        // Note: 22.0 has no fractional part but is written as a JSON number;
        // the parser distinguishes longOrNull (fails on "22.0") from doubleOrNull.
        native["count"].shouldBeInstanceOf<Double>()
        native["count"] shouldBe 22.0
        native["rate"].shouldBeInstanceOf<Double>()
        native["rate"] shouldBe 1.5
    }

    test("unquoted booleans become Boolean") {
        val parsed = json.parseToJsonElement("""{"enabled": true, "disabled": false}""")
        val native = jsonElementToNative(parsed) as Map<*, *>
        native["enabled"].shouldBeInstanceOf<Boolean>()
        native["enabled"] shouldBe true
        native["disabled"] shouldBe false
    }

    test("quoted boolean-looking strings stay strings") {
        // A string "true" is a string, not a Boolean.
        val parsed = json.parseToJsonElement("""{"flag": "true"}""")
        val native = jsonElementToNative(parsed) as Map<*, *>
        native["flag"].shouldBeInstanceOf<String>()
        native["flag"] shouldBe "true"
    }

    test("nested objects + arrays preserve string typing throughout") {
        // Mirror the actual purchase-leaf shape that triggered the bug.
        val parsed = json.parseToJsonElement(
            """
            {
              "type": "purchase",
              "children": [],
              "quantity": 22.0,
              "product_id": "300-0150",
              "location_id": "1000"
            }
            """.trimIndent()
        )
        val native = jsonElementToNative(parsed) as Map<*, *>
        native["type"] shouldBe "purchase"
        native["product_id"].shouldBeInstanceOf<String>()
        native["location_id"].shouldBeInstanceOf<String>()
        native["location_id"] shouldBe "1000"
        // The downstream check that was failing pre-fix:
        val lid = native["location_id"] as? String
        lid shouldBe "1000"  // <- pre-fix this was null because location_id was Long
    }

    test("null becomes null") {
        val parsed = json.parseToJsonElement("""{"missing": null}""")
        val native = jsonElementToNative(parsed) as Map<*, *>
        native["missing"] shouldBe null
    }

    test("nested array of objects preserves typing") {
        val parsed = json.parseToJsonElement(
            """{"items": [{"id": "100"}, {"id": "200"}]}"""
        )
        val native = jsonElementToNative(parsed) as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val items = native["items"] as List<Map<String, Any?>>
        items.size shouldBe 2
        items[0]["id"] shouldBe "100"
        (items[0]["id"] as? String) shouldBe "100"
    }
})
