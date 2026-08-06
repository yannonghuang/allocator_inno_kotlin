package com.allocator

import com.allocator.services.parseDate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class ParseDateTest : FunSpec({

    test("parseDate: ISO format parses") {
        parseDate("2026-08-01") shouldBe LocalDate.of(2026, 8, 1)
    }

    test("parseDate: M/d/yyyy fallback format parses") {
        parseDate("8/1/2026") shouldBe LocalDate.of(2026, 8, 1)
    }

    test("parseDate: null and blank are silently missing") {
        parseDate(null) shouldBe null
        parseDate("") shouldBe null
        parseDate("   ") shouldBe null
    }

    test("parseDate: the literal text NULL (any case) is silently missing, not a warn-worthy unparseable date") {
        parseDate("NULL") shouldBe null
        parseDate("null") shouldBe null
        parseDate("Null") shouldBe null
        parseDate("  NULL  ") shouldBe null
    }

    test("parseDate: a genuinely malformed date still returns null (falls through every format)") {
        parseDate("not-a-date") shouldBe null
        parseDate("13/45/2026") shouldBe null
    }
})
