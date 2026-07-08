package com.allocator

import com.allocator.services.Constraint
import com.allocator.services.parseConstraints
import com.allocator.services.plan
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Behavior contract for customer-specific BOM-alternative constraints
 * (`config.constraints`). A rule {customer, parent, location, child} forces the
 * planner to pick the alternative whose children include `child` when a demand from
 * `customer` resolves `parent` as a make (location blank/"*" = any).
 *
 * Test fixture: parent P (make at L) with two single-child alternatives
 * P→C1 (alt_group A1) and P→C2 (alt_group A2); C1 and C2 are both buyable. The planner
 * waterfalls across alt_groups by preference (ties keep BOM declaration order): an
 * unconstrained demand fully satisfies from the first-declared alternative (C1) alone;
 * a matching constraint narrows the candidate list to the one forced child.
 */
class ConstraintsTest : FunSpec({

    fun makeP() = mapOf<String, Any?>("bom_id" to "B", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0)
    fun bom(child: String, alt: String) = mapOf<String, Any?>("bom_id" to "B", "parent_id" to "P", "child_id" to child, "alt_group" to alt, "rate" to 1.0)
    fun buy(pid: String) = mapOf<String, Any?>("product_id" to pid, "location_id" to "L", "preference" to 1, "lead_time" to 0.0)

    val data = mapOf(
        "method_make" to listOf(makeP()),
        "method_buy"  to listOf(buy("C1"), buy("C2")),
        "method_move" to emptyList(),
        "bom"         to listOf(bom("C1", "A1"), bom("C2", "A2")),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "supply"      to emptyList<Map<String, Any?>>(),
    )

    fun inv() = mutableListOf<MutableMap<String, Any?>>()
    fun demandC(customer: String) = mapOf<String, Any?>(
        "demand_id" to "D", "product_id" to "P", "location_id" to "L",
        "quantity" to 100.0, "request_due_time" to "2024-01-01", "customer_id" to customer,
    )

    fun cfg(constraints: List<Map<String, Any?>>) = mapOf<String, Any?>(
        "purchase_allowed" to true,
        "constraints" to constraints,
    )
    fun rule(customer: String, child: String, location: String = "*") =
        mapOf<String, Any?>("customer" to customer, "parent" to "P", "location" to location, "child" to child)

    // Which child products end up purchased (i.e. which alternative the make resolved to).
    fun purchasedChildren(wos: List<Map<String, Any?>>): Set<String> =
        wos.filter { it["method"] == "purchase" }.mapNotNull { it["product_id"] as? String }.toSet()

    // ── parsing ───────────────────────────────────────────────────────────────
    test("parseConstraints: absent / empty / incomplete ⇒ empty; well-formed + aliases parse") {
        parseConstraints(null) shouldBe emptyList()
        parseConstraints(mapOf("constraints" to emptyList<Any?>())) shouldBe emptyList()
        parseConstraints(mapOf("constraints" to "not-a-list")) shouldBe emptyList()
        parseConstraints(mapOf("constraints" to listOf(mapOf("customer" to "X")))) shouldBe emptyList()  // missing parent/child
        parseConstraints(mapOf("constraints" to listOf(
            mapOf("customer" to " X ", "parent" to "P", "location" to "*", "child" to "C2"),
        ))) shouldBe listOf(Constraint("X", "P", "*", "C2"))
        parseConstraints(mapOf("constraints" to listOf(
            mapOf("customer_id" to "X", "parent_product" to "P", "child_product" to "C2"),  // aliases, no location
        ))) shouldBe listOf(Constraint("X", "P", "", "C2"))
    }

    // ── enforcement ─────────────────────────────────────────────────────────────
    test("customer match forces the constrained child") {
        val (_, wos, _) = plan(demandC("X"), inv(), data, requestTimeDt = null, config = cfg(listOf(rule("X", "C2"))))
        purchasedChildren(wos) shouldBe setOf("C2")
    }

    test("different customer ⇒ default selection (first alternative by declaration order), constraint not applied") {
        val (_, wos, _) = plan(demandC("Y"), inv(), data, requestTimeDt = null, config = cfg(listOf(rule("X", "C2"))))
        purchasedChildren(wos) shouldBe setOf("C1")
    }

    test("location wildcard applies; non-matching explicit location does not") {
        // blank location ⇒ any location ⇒ applies
        val (_, wosBlank, _) = plan(demandC("X"), inv(), data, requestTimeDt = null, config = cfg(listOf(rule("X", "C2", ""))))
        purchasedChildren(wosBlank) shouldBe setOf("C2")
        // explicit non-matching location ⇒ does not apply ⇒ default (first alternative, C1)
        val (_, wosOther, _) = plan(demandC("X"), inv(), data, requestTimeDt = null, config = cfg(listOf(rule("X", "C2", "LX"))))
        purchasedChildren(wosOther) shouldBe setOf("C1")
    }

    test("constrained child not an alternative ⇒ ignored, plan normally") {
        val (_, wos, _) = plan(demandC("X"), inv(), data, requestTimeDt = null, config = cfg(listOf(rule("X", "C9"))))
        purchasedChildren(wos) shouldBe setOf("C1")           // unfiltered ⇒ default (first alternative)
        (wos.any { it["product_id"] == "P" && it["method"] == "make" }) shouldBe true   // demand still made
    }

    // ── Multi-method alternatives (distinct bom_id per child — the common real shape) ──
    // P has two MAKE METHODS (different bom_ids), B1→C1 (preferred) and B2→C2; the planner
    // normally picks B1 by preference. A constraint must force the method that yields C2.
    val multiMethod = mapOf(
        "method_make" to listOf(
            mapOf<String, Any?>("bom_id" to "B1", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            mapOf<String, Any?>("bom_id" to "B2", "product_id" to "P", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
        ),
        "method_buy"  to listOf(buy("C1"), buy("C2")),
        "method_move" to emptyList(),
        "bom"         to listOf(
            mapOf<String, Any?>("bom_id" to "B1", "parent_id" to "P", "child_id" to "C1", "alt_group" to null, "rate" to 1.0),
            mapOf<String, Any?>("bom_id" to "B2", "parent_id" to "P", "child_id" to "C2", "alt_group" to null, "rate" to 1.0),
        ),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "supply" to emptyList<Map<String, Any?>>(),
    )

    test("multi-method: default picks the preferred method (C1)") {
        val (_, wos, _) = plan(demandC("X"), inv(), multiMethod, requestTimeDt = null, config = cfg(emptyList()))
        purchasedChildren(wos) shouldBe setOf("C1")
    }

    test("multi-method: constraint forces the make method that produces the child (C2)") {
        val (_, wos, _) = plan(demandC("X"), inv(), multiMethod, requestTimeDt = null, config = cfg(listOf(rule("X", "C2"))))
        purchasedChildren(wos) shouldBe setOf("C2")
    }

    // ── Sub-component: customer must propagate down the BOM so the constraint applies to a
    //    parent that is itself a child of the finished good. FG → P (sub, two make methods). ──
    val twoLevel = mapOf(
        "method_make" to listOf(
            mapOf<String, Any?>("bom_id" to "BFG", "product_id" to "FG", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            mapOf<String, Any?>("bom_id" to "B1",  "product_id" to "P",  "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            mapOf<String, Any?>("bom_id" to "B2",  "product_id" to "P",  "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
        ),
        "method_buy"  to listOf(buy("C1"), buy("C2")),
        "method_move" to emptyList(),
        "bom"         to listOf(
            mapOf<String, Any?>("bom_id" to "BFG", "parent_id" to "FG", "child_id" to "P",  "alt_group" to null, "rate" to 1.0),
            mapOf<String, Any?>("bom_id" to "B1",  "parent_id" to "P",  "child_id" to "C1", "alt_group" to null, "rate" to 1.0),
            mapOf<String, Any?>("bom_id" to "B2",  "parent_id" to "P",  "child_id" to "C2", "alt_group" to null, "rate" to 1.0),
        ),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "supply" to emptyList<Map<String, Any?>>(),
    )
    fun demandFG(customer: String) = mapOf<String, Any?>(
        "demand_id" to "DFG", "product_id" to "FG", "location_id" to "L",
        "quantity" to 100.0, "request_due_time" to "2024-01-01", "customer_id" to customer,
    )

    test("sub-component: constraint on a child parent applies (customer propagates through the BOM)") {
        val (_, wos, _) = plan(demandFG("X"), inv(), twoLevel, requestTimeDt = null, config = cfg(listOf(rule("X", "C2"))))
        purchasedChildren(wos) shouldBe setOf("C2")
        // without the constraint, the sub-component takes its preferred route (C1)
        val (_, wos2, _) = plan(demandFG("X"), inv(), twoLevel, requestTimeDt = null, config = cfg(emptyList()))
        purchasedChildren(wos2) shouldBe setOf("C1")
    }

    // ── Genuine waterfall spillover: the preferred method commits only part of the demand
    //    (its child C1 has no fallback beyond a capped inventory lot), so the residual falls
    //    through to the next method by preference (C2, uncapped purchase) — proving this is a
    //    real waterfall (best fully, then only the residual to the next), not equal-split.
    val multiMethodCapped = mapOf(
        "method_make" to listOf(
            mapOf<String, Any?>("bom_id" to "B1", "product_id" to "P", "location_id" to "L", "preference" to 1, "lead_time" to 0.0),
            mapOf<String, Any?>("bom_id" to "B2", "product_id" to "P", "location_id" to "L", "preference" to 2, "lead_time" to 0.0),
        ),
        "method_buy"  to listOf(buy("C2")),   // C1 has NO buy method — only its capped inventory lot
        "method_move" to emptyList(),
        "bom"         to listOf(
            mapOf<String, Any?>("bom_id" to "B1", "parent_id" to "P", "child_id" to "C1", "alt_group" to null, "rate" to 1.0),
            mapOf<String, Any?>("bom_id" to "B2", "parent_id" to "P", "child_id" to "C2", "alt_group" to null, "rate" to 1.0),
        ),
        "productlocation" to emptyList<Map<String, Any?>>(),
        "supply" to emptyList<Map<String, Any?>>(),
    )
    fun invWithC1(qty: Double) = mutableListOf<MutableMap<String, Any?>>(
        mutableMapOf("product_id" to "C1", "location_id" to "L", "qty" to qty, "supply_id" to "S1", "supply_date" to "2024-01-01"),
    )

    test("waterfall spillover: capped preferred alternative partially satisfies, residual falls to next by preference") {
        val (_, wos, _) = plan(demandC("Z"), invWithC1(30.0), multiMethodCapped, requestTimeDt = null, config = cfg(emptyList()))
        val makeWos = wos.filter { it["product_id"] == "P" && it["method"] == "make" }
        makeWos.size shouldBe 2
        makeWos.sumOf { (it["quantity"] as? Number)?.toDouble() ?: 0.0 } shouldBe 100.0
        purchasedChildren(wos) shouldBe setOf("C2")
        (wos.first { it["product_id"] == "C2" && it["method"] == "purchase" }["quantity"] as Number).toDouble() shouldBe 70.0
    }
})
