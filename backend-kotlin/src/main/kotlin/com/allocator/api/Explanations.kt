package com.allocator.api

import com.allocator.*
import com.allocator.services.CaseLoader
import com.allocator.services.TimeUtils
import com.allocator.services.roundQty
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

private val jEx = Json { ignoreUnknownKeys = true }

private fun parseStrList(t: String?): List<String> {
    if (t.isNullOrBlank()) return emptyList()
    return try { jEx.parseToJsonElement(t).jsonArray.map { it.jsonPrimitive.content } } catch (_: Exception) { emptyList() }
}

private fun compKeyToAt(ck: String): String =
    if ("|" in ck) ck.split("|", limit = 2).let { "${it[0]}@${it[1]}" } else ck

/**
 * Explainability routes — port of api/explanations.py.
 */
fun Routing.explanationRoutes() {

    route("/cases/{case_id}/runs/{run_id}") {

        // ── GET /explanations?supply_id=... ────────────────────────────────────
        get("/explanations") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid run_id")
            val supplyIdParam = call.request.queryParameters["supply_id"]
                ?: throw IllegalArgumentException("supply_id required")

            val compKey = transaction {
                AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")

                if ("|" in supplyIdParam) supplyIdParam
                else {
                    val s = Supplies.selectAll().where {
                        (Supplies.caseId eq caseId) and (Supplies.supplyId eq supplyIdParam)
                    }.singleOrNull() ?: throw NoSuchElementException("Supply not found")
                    "${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}"
                }
            }

            val demandQty = mutableMapOf<String, Double>()
            transaction {
                val actions = AllocationActions.selectAll().where { AllocationActions.runId eq runId }.toList()
                for (a in actions) {
                    val reqKeys = parseStrList(a[AllocationActions.reqComponentIds])
                    if (compKey !in reqKeys) continue
                    val pid = a[AllocationActions.targetProductId] ?: continue
                    val demands = Demands.selectAll().where {
                        (Demands.caseId eq caseId) and (Demands.productId eq pid)
                    }.orderBy(Demands.priority, SortOrder.ASC).toList()
                    var qty = a[AllocationActions.qty]
                    for (d in demands) {
                        if (qty <= 0) break
                        val give = minOf(qty, d[Demands.quantity])
                        if (give > 0) {
                            demandQty[d[Demands.demandId]] = (demandQty[d[Demands.demandId]] ?: 0.0) + give
                            qty -= give
                        }
                    }
                }
            }

            val items = buildJsonArray {
                for ((did, qty) in demandQty) add(buildJsonObject {
                    put("demand_id", did)
                    put("quantity", roundQty(qty))
                    put("reason", "Proportional allocation by scarcity order; this supply was consumed by variant(s) serving this demand.")
                })
            }
            call.respond(buildJsonObject {
                put("supply_id", supplyIdParam)
                put("component_key", compKey)
                put("split", items)
            })
        }

        // ── GET /allocation-explanation?component_key=...&to_variant_key=... ──
        get("/allocation-explanation") {
            val caseId = call.parameters["case_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid case_id")
            val runId = call.parameters["run_id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid run_id")
            val componentKey = call.request.queryParameters["component_key"]
                ?: throw IllegalArgumentException("component_key required")
            val toVariantKey = call.request.queryParameters["to_variant_key"]

            val (runRow, data) = transaction {
                val r = AllocationRuns.selectAll().where {
                    (AllocationRuns.id eq runId) and (AllocationRuns.caseId eq caseId)
                }.singleOrNull() ?: throw NoSuchElementException("Run not found")
                r to CaseLoader.load(caseId)
            }

            val methodMakeList = data["method_make"] ?: emptyList()
            val methodMoveList = data["method_move"] ?: emptyList()
            val bomList = data["bom"] ?: emptyList()

            // BOM by parent
            val bomByParent = mutableMapOf<String, MutableList<Map<String, Any?>>>()
            for (b in bomList) {
                val pid = b["parent_id"] as? String ?: continue
                bomByParent.getOrPut(pid) { mutableListOf() }.add(b)
            }

            val compPid = if ("|" in componentKey) componentKey.split("|", limit = 2)[0] else componentKey
            val compLoc = if ("|" in componentKey) componentKey.split("|", limit = 2)[1] else ""

            // Candidate targets from make methods
            val candidateTargets = mutableListOf<MutableMap<String, Any?>>()
            for (m in methodMakeList) {
                val pid = m["product_id"] as? String ?: continue
                val loc = m["location_id"] as? String ?: ""
                val children = bomByParent[pid] ?: emptyList()
                val req = children.map { c -> (c["child_id"] as? String ?: "") to loc }
                if ((compPid to compLoc) in req) {
                    candidateTargets.add(mutableMapOf(
                        "variant_key" to "$pid|$loc",
                        "variant_display" to compKeyToAt("$pid|$loc"),
                        "edge_type" to "make"
                    ))
                }
            }

            // Build variantsReq
            data class VKey(val pid: String, val loc: String)
            val variantsReq = mutableMapOf<VKey, List<Pair<String, String>>>()
            for (m in methodMakeList) {
                val pid = m["product_id"] as? String ?: continue
                val loc = m["location_id"] as? String ?: ""
                val children = bomByParent[pid] ?: emptyList()
                val req = children.map { c -> (c["child_id"] as? String ?: "") to loc }
                if (req.isNotEmpty()) variantsReq[VKey(pid, loc)] = req
            }
            for (mv in methodMoveList) {
                val pid = mv["product_id"] as? String ?: continue
                val fromLoc = (mv["from_location_id"] as? String ?: "")
                val toLoc = (mv["to_location_id"] as? String ?: "")
                if (fromLoc == toLoc) continue
                val keyFrom = "$pid|$fromLoc"
                if (keyFrom == componentKey) {
                    candidateTargets.add(mutableMapOf(
                        "variant_key" to "$pid|$toLoc",
                        "variant_display" to compKeyToAt("$pid|$toLoc"),
                        "edge_type" to "move"
                    ))
                }
                if (!variantsReq.containsKey(VKey(pid, toLoc))) {
                    variantsReq[VKey(pid, toLoc)] = listOf(pid to fromLoc)
                }
            }

            // Deduplicate candidate targets
            val seenTarget = mutableSetOf<Pair<String, String>>()
            val uniqueTargets = candidateTargets.filter { ct ->
                val k = (ct["variant_key"] as? String ?: "") to (ct["edge_type"] as? String ?: "")
                seenTarget.add(k)
            }
            // rebuild deduplicated list
            val dedupTargets = mutableListOf<MutableMap<String, Any?>>()
            val seenTarget2 = mutableSetOf<Pair<String, String>>()
            for (ct in candidateTargets) {
                val k = (ct["variant_key"] as? String ?: "") to (ct["edge_type"] as? String ?: "")
                if (seenTarget2.add(k)) dedupTargets.add(ct)
            }

            val allTargets = variantsReq.keys.toSet()
            val demandProductToLocation = mutableMapOf<String, String>()
            for (m in methodMakeList) {
                val pid = m["product_id"] as? String ?: continue
                val loc = m["location_id"] as? String ?: ""
                demandProductToLocation.putIfAbsent(pid, loc)
            }

            // Demand weights
            val supplyList = data["supply"] ?: emptyList()
            val demandListRaw = data["demand"] ?: emptyList()
            val (dateToPeriod, _) = TimeUtils.buildPeriodIndex(
                supplyList.map { mapOf("supply_date" to it["supply_date"]) },
                demandListRaw.map { mapOf("request_due_time" to it["request_due_time"]) }
            )

            val demandAdj = emptyMap<String, Double>()

            // unmet per (variant, customer)
            data class VCKey(val pid: String, val loc: String, val cust: String)
            val unmet = mutableMapOf<VCKey, Double>()
            for (d in demandListRaw) {
                val pid = d["product_id"] as? String ?: continue
                val cust = d["customer_id"] as? String ?: ""
                var qty = (d["quantity"] as? Number)?.toDouble() ?: 0.0
                val loc = (d["location_id"] as? String) ?: demandProductToLocation[pid] ?: continue
                val did = d["demand_id"] as? String ?: ""
                val adj = demandAdj["demand|$did"] ?: 0.0
                qty = maxOf(0.0, qty + adj)
                val k = VCKey(pid, loc, cust)
                unmet[k] = (unmet[k] ?: 0.0) + qty
            }

            // Customer weights from run config
            val configText = runRow[AllocationRuns.config] ?: "{}"
            val configJson = try { jEx.parseToJsonElement(configText).jsonObject } catch (_: Exception) { buildJsonObject {} }
            val customerWeights = mutableMapOf<String, Double>()
            for ((k, v) in configJson) { customerWeights[k] = v.jsonPrimitive.doubleOrNull ?: 1.0 }
            for (d in demandListRaw) {
                val cid = d["customer_id"] as? String ?: ""
                customerWeights.putIfAbsent(cid, 1.0)
            }

            // target_weight per variant
            val targetWeight = mutableMapOf<VKey, Double>()
            for ((vck, q) in unmet) {
                if (q > 0) {
                    val v = VKey(vck.pid, vck.loc)
                    targetWeight[v] = (targetWeight[v] ?: 0.0) + q * (customerWeights[vck.cust] ?: 1.0)
                }
            }

            // Downstream propagation
            val downstream = mutableMapOf<VKey, MutableSet<VKey>>()
            for ((v, req) in variantsReq) {
                for ((cpid, cloc) in req) {
                    val cv = VKey(cpid, cloc)
                    if (cv in allTargets) downstream.getOrPut(cv) { mutableSetOf() }.add(v)
                }
            }

            // Topological order
            val order = mutableListOf<VKey>()
            val seen = mutableSetOf<VKey>()
            fun visit(t: VKey) {
                if (t in seen) return
                seen.add(t)
                for (t2 in downstream[t] ?: emptySet()) visit(t2)
                order.add(t)
            }
            for (t in allTargets) visit(t)
            order.reverse()
            for (t in order) {
                if (t in targetWeight) continue
                targetWeight[t] = (downstream[t] ?: emptySet()).sumOf { targetWeight[it] ?: 0.0 }
            }

            // Attach target_weight to each candidate
            val demandedVariants = unmet.filter { it.value > 0 }.keys.map { VKey(it.pid, it.loc) }.toSet()
            for (ct in dedupTargets) {
                val vk = ct["variant_key"] as? String ?: ""
                val vPid = if ("|" in vk) vk.split("|", limit = 2)[0] else vk
                val vLoc = if ("|" in vk) vk.split("|", limit = 2)[1] else ""
                val v = VKey(vPid, vLoc)
                val w = Math.round((targetWeight[v] ?: 0.0) * 10000).toDouble() / 10000.0
                ct["target_weight"] = w
                ct["weight_calculation"] = if (v in demandedVariants)
                    "Demanded variant: Σ(unmet quantity × customer weight) over customers with demand for this product."
                else
                    "Non-demanded: downstream_value = Σ target_weight of demanded variants reachable from this variant (via BOM/move edges)."
            }
            val totalW = dedupTargets.sumOf { (it["target_weight"] as? Double) ?: 0.0 }

            val weightFormula = "Target weight (demanded variant) = Σ unmet(customer) × customer_weight. " +
                "Target weight (non-demanded variant) = downstream_value = Σ target_weight of demanded targets reachable from it. " +
                "Split of component = (target_weight / total_target_weight) × available."

            // Simulate available and collect steps
            val (available, actions, totalAvailable, availableDuringRun, steps) = transaction {
                val supplies = Supplies.selectAll().where { Supplies.caseId eq caseId }.toList()
                val avail = mutableMapOf<String, Double>()
                for (s in supplies) {
                    val k = "${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}"
                    avail[k] = (avail[k] ?: 0.0) + s[Supplies.qty]
                }
                val acts = AllocationActions.selectAll().where { AllocationActions.runId eq runId }.toList()
                val sortedActs = acts.sortedWith(compareBy(
                    { it[AllocationActions.scarcityRank] ?: 999999 },
                    { it[AllocationActions.id] }
                ))
                val totalAvail = supplies.filter { s ->
                    "${s[Supplies.productId]}|${s[Supplies.locationId] ?: ""}" == componentKey
                }.sumOf { s -> s[Supplies.qty] }

                val availDuringRun = totalAvail + sortedActs.sumOf { a ->
                    if (a[AllocationActions.variantKey] == componentKey) a[AllocationActions.qty] else 0.0
                }

                val stepsList = mutableListOf<Map<String, Any?>>()
                for (a in sortedActs) {
                    val reqKeys = parseStrList(a[AllocationActions.reqComponentIds])
                    if (componentKey !in reqKeys) {
                        val vk = a[AllocationActions.variantKey] ?: ""
                        if (vk.isNotEmpty()) avail[vk] = (avail[vk] ?: 0.0) + a[AllocationActions.qty]
                        continue
                    }
                    val availBefore = roundQty(avail[componentKey] ?: 0.0)
                    val qty = roundQty(a[AllocationActions.qty])
                    val toKey = a[AllocationActions.variantKey] ?: ""
                    if (toVariantKey != null && toKey != toVariantKey) {
                        for (ck in reqKeys) avail[ck] = (avail[ck] ?: 0.0) - qty
                        if (toKey.isNotEmpty()) avail[toKey] = (avail[toKey] ?: 0.0) + qty
                        continue
                    }
                    stepsList.add(mapOf(
                        "to_variant_key" to toKey,
                        "to_variant_display" to compKeyToAt(toKey),
                        "qty" to qty,
                        "edge_type" to (a[AllocationActions.edgeType] ?: "make"),
                        "available_before" to availBefore,
                    ))
                    for (ck in reqKeys) avail[ck] = (avail[ck] ?: 0.0) - qty
                    if (toKey.isNotEmpty()) avail[toKey] = (avail[toKey] ?: 0.0) + qty
                }
                data class R5(val a: MutableMap<String, Double>, val b: List<*>, val c: Double, val d: Double, val e: List<Map<String, Any?>>)
                R5(avail, sortedActs, totalAvail, availDuringRun, stepsList)
            }

            val totalAvailRounded = roundQty(totalAvailable)
            val availDuringRounded = roundQty(availableDuringRun)

            val reason = "Allocation is proportional to target weight (demand pressure). " +
                "Components are processed in scarcity order (total quantity; scarcest = smallest). " +
                "The critical (limiting) component had the smallest available quantity among requirements for this edge."

            val supplyNote: String? = if (totalAvailRounded == 0.0 && steps.any { ((it["qty"] as? Double) ?: 0.0) > 0 })
                "Initial supply for this component is 0. Allocated quantities come from production of this component " +
                    "earlier in the run (make/move that outputs this product@location), then consumed by the steps below."
            else null

            call.respond(buildJsonObject {
                put("component_key", componentKey)
                put("component_display", compKeyToAt(componentKey))
                put("weight_formula", weightFormula)
                put("candidate_targets", buildJsonArray {
                    for (ct in dedupTargets) add(buildJsonObject {
                        put("variant_key", ct["variant_key"] as? String ?: "")
                        put("variant_display", ct["variant_display"] as? String ?: "")
                        put("edge_type", ct["edge_type"] as? String ?: "")
                        put("target_weight", ct["target_weight"] as? Double ?: 0.0)
                        put("weight_calculation", ct["weight_calculation"] as? String ?: "")
                    })
                })
                put("total_candidate_weight", Math.round(totalW * 10000).toDouble() / 10000.0)
                put("steps", buildJsonArray {
                    for (s in steps) add(buildJsonObject {
                        put("to_variant_key", s["to_variant_key"] as? String ?: "")
                        put("to_variant_display", s["to_variant_display"] as? String ?: "")
                        put("qty", s["qty"] as? Double ?: 0.0)
                        put("edge_type", s["edge_type"] as? String ?: "make")
                        put("available_before", s["available_before"] as? Double ?: 0.0)
                    })
                })
                put("total_supply_for_component", totalAvailRounded)
                put("available_during_run", availDuringRounded)
                put("reason", reason)
                if (supplyNote != null) put("supply_note", supplyNote) else put("supply_note", JsonNull)
            })
        }
    }
}
