package com.allocator

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentTimestamp
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

/**
 * Exposed table definitions — 1:1 port of SQLAlchemy models in models.py.
 * All domain tables carry a case_id FK with CASCADE delete (multi-tenancy).
 */

object Cases : Table("cases") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val assessmentCriteria = text("assessment_criteria").nullable()
    // User-pinned active plan run; null => resolver falls back to latest success.
    // ON DELETE SET NULL so deleting the pinned run reverts the case to the default resolver.
    val designatedActivePlanRunId = integer("designated_active_plan_run_id").nullable()
    /** User-controlled active flag used by /resolve. Multiple rows may be active; latest id wins.
     *  When no row is active, /resolve falls back to the latest case by id. */
    val active = bool("active").default(false)
    override val primaryKey = PrimaryKey(id)
}

object Boms : Table("bom") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val bomId = varchar("bom_id", 255)
    val parentId = varchar("parent_id", 255)
    val childId = varchar("child_id", 255)
    val elemIx = integer("elem_ix").nullable()
    val altGroup = varchar("alt_group", 255).nullable()
    val rate = double("rate").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Customers : Table("customer") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val customer = varchar("customer", 255)
    val description = varchar("description", 512).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Locations : Table("location") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val locationId = varchar("location_id", 255)
    val locationDescription = varchar("location_description", 512).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Products : Table("product") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val productId = varchar("product_id", 255)
    val description = varchar("description", 512).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Vendors : Table("vendor") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val vendorId = varchar("vendor_id", 255)
    override val primaryKey = PrimaryKey(id)
}

object Demands : Table("demand") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val demandId = varchar("demand_id", 255)
    val description = varchar("description", 512).nullable()
    val customerId = varchar("customer_id", 255)
    val priority = integer("priority").nullable()
    val requestDueTime = varchar("request_due_time", 64).nullable()
    val productId = varchar("product_id", 255)
    val locationId = varchar("location_id", 255).nullable()
    val quantity = double("quantity")
    override val primaryKey = PrimaryKey(id)
}

object MethodBuys : Table("method_buy") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val productId = varchar("product_id", 255)
    val locationId = varchar("location_id", 255)
    val preference = integer("preference").nullable()
    val leadDaysSupply = integer("lead_days_supply").nullable()
    val cycleDaysSupply = integer("cycle_days_supply").nullable()
    val vendorId = varchar("vendor_id", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}

object MethodMakes : Table("method_make") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val bomId = varchar("bom_id", 255)
    val productId = varchar("product_id", 255)
    val locationId = varchar("location_id", 255)
    val preference = integer("preference").nullable()
    val leadTime = integer("lead_time").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ProductLocations : Table("productlocation") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val productId = varchar("product_id", 255)
    val description = varchar("description", 512).nullable()
    val locationId = varchar("location_id", 255)
    val maxLotSize = double("max_lot_size").nullable()
    val prodArea = varchar("prod_area", 255).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Operations : Table("operation") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val operationId = varchar("operation_id", 255)
    val prodArea = varchar("prod_area", 255)
    val uph = double("uph")
    val yieldFactor = double("yield_factor")
    val borId = varchar("bor_id", 255)
    val processTime = integer("process_time")
    val preProcessTime = integer("pre_process_time")
    val postProcessTime = integer("post_process_time")
    override val primaryKey = PrimaryKey(id)
}

object Bors : Table("bor") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val borId = varchar("bor_id", 255)
    val resourceId = varchar("resource_id", 255)
    val resourceRate = double("resource_rate")
    override val primaryKey = PrimaryKey(id)
}

object Resources : Table("resource") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val resourceId = varchar("resource_id", 255)
    val locationId = varchar("location_id", 255)
    val size = double("size")
    override val primaryKey = PrimaryKey(id)
}

object Supplies : Table("supply") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val supplyId = varchar("supply_id", 255)
    val description = varchar("description", 512).nullable()
    val vendorId = varchar("vendor_id", 255).nullable()
    val locationId = varchar("location_id", 255).nullable()
    val productId = varchar("product_id", 255)
    val supplyDate = varchar("supply_date", 64).nullable()
    val qty = double("qty")
    override val primaryKey = PrimaryKey(id)
}

object MethodMoves : Table("method_move") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val productId = varchar("product_id", 255)
    val fromLocationId = varchar("from_location_id", 255)
    val toLocationId = varchar("to_location_id", 255)
    val transitTime = double("transit_time").nullable()
    val transitTimeUom = varchar("transit_time_uom", 32).nullable()
    val preference = integer("preference").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AllocationRuns : Table("allocation_run") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val status = varchar("status", 64)      // "running" | "success" | "failed"
    val config = text("config").nullable()  // JSON blob (kotlinx-serialization)
    override val primaryKey = PrimaryKey(id)
}

object AllocationActions : Table("allocation_action") {
    val id = integer("id").autoIncrement()
    val runId = integer("run_id").references(AllocationRuns.id, onDelete = ReferenceOption.CASCADE)
    val variantKey = varchar("variant_key", 512)
    val reqComponentIds = text("req_component_ids")  // JSON array
    val reqRates = text("req_rates").nullable()       // JSON array
    val qty = double("qty")
    val demandId = varchar("demand_id", 255).nullable()
    val targetProductId = varchar("target_product_id", 255).nullable()
    val targetLocationId = varchar("target_location_id", 255).nullable()
    val outputPeriod = integer("output_period").nullable()
    val edgeType = varchar("edge_type", 32).nullable()  // "make" | "move"
    val scarcityRank = integer("scarcity_rank").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ManualOverrides : Table("manual_override") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val entityType = varchar("entity_type", 64)   // "supply" | "demand" | "component_split" | "method_selection" | "variant_selection"
    val entityKey = varchar("entity_key", 512)
    val payload = text("payload")                 // JSON blob
    override val primaryKey = PrimaryKey(id)
}

/** Persisted material supply update events for manual impact analysis. */
object MaterialEvents : Table("material_event") {
    val id          = integer("id").autoIncrement()
    val caseId      = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val supplyId    = varchar("supply_id", 255)
    val delayDays      = integer("delay_days").default(0)
    val qtyDecreasePct = double("qty_decrease_pct").default(0.0)
    val qtyDecreaseAbs = double("qty_decrease_abs").nullable()   // absolute qty decrease; takes precedence over pct when set
    val note           = text("note").nullable()
    val createdAt   = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** Persisted work-order schedule-change events for manual impact analysis.
 *  Selectors (bucket + optional filters) are stored as a JSON array; one event
 *  carries one schedule change ({@link delayDays} OR {@link delayToDate}). */
object WoScheduleEvents : Table("wo_schedule_event") {
    val id          = integer("id").autoIncrement()
    val caseId      = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val selectorsJson = text("selectors_json")             // JSON array of WoScheduleSelector
    val delayDays      = integer("delay_days").nullable()
    val delayToDate    = varchar("delay_to_date", 16).nullable()  // ISO yyyy-MM-dd
    val note           = text("note").nullable()
    val createdAt   = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/** Persisted planning run: config + override snapshot + result JSON so state is restorable. */
object PlanRuns : Table("plan_run") {
    val id = integer("id").autoIncrement()
    val caseId = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val jobId = varchar("job_id", 64).nullable()
    val status = varchar("status", 16).default("running")       // "running" | "success" | "failed" | "contingent"
    val config = text("config").nullable()                       // JSON: PlanningConfig sent with request
    val overrideSnapshot = text("override_snapshot").nullable()  // JSON: overrides active at run time
    val result = text("result").nullable()                       // JSON: enriched plan result
    val error = text("error").nullable()
    val metadata = text("metadata").nullable()                   // JSON: {"type":"contingent","supplyId":"...","deliveryDelayDays":N,"quantityDecreasePct":N,"baselinePlanRunId":M}
    val name = varchar("name", 255).nullable()
    val notes = text("notes").nullable()
    val negotiationRound       = integer("negotiation_round").nullable()          // 0 = initial contingent; 1..N = counter-proposal rounds
    val parentPlanRunId        = integer("parent_plan_run_id").nullable()         // self-ref: previous round's contingent in a negotiation chain
    val supersededByPlanRunId  = integer("superseded_by_plan_run_id").nullable()  // self-ref: next round's contingent (NULL = chain tail, promotable)
    /** Tags wo_schedule_contingent runs back to the WoScheduleEvent that drove the analysis,
     *  enabling per-event run-history listing. ON DELETE SET NULL keeps the contingent runs
     *  visible in plan-run history even after the user deletes the event. */
    val woScheduleEventId      = integer("wo_schedule_event_id").references(WoScheduleEvents.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val finishedAt = timestamp("finished_at").nullable()
    val chosenDepth = integer("chosen_depth").nullable()  // depth picked by optimal-depth search; null when search not run
    val attempts = text("attempts").nullable()           // JSON array: [{"depth":1,"duration_ms":1200}, ...] for optimal-depth search; null for fixed-depth runs
    /** Soundness check state: "unchecked" | "checking" | "sound" | "unsound" | "error". See SoundnessChecker. */
    val soundnessStatus = varchar("soundness_status", 16).default("unchecked")
    /** JSON-serialized SoundnessReport (null when unchecked or in flight). */
    val soundnessReport = text("soundness_report").nullable()
    /** When the most recent soundness check finished; null when never run. */
    val soundnessCheckedAt = timestamp("soundness_checked_at").nullable()
    /** User-controlled active flag used by /resolve. Multiple rows may be active; latest id wins.
     *  When no row is active for a case, /resolve falls back to the latest run by id within that case. */
    val active = bool("active").default(false)
    override val primaryKey = PrimaryKey(id)
}

/** Supply lots consumed by a saved planning run — written on plan save, read by supply view. */
object PlanSupplyAllocations : Table("plan_supply_allocation") {
    val id          = integer("id").autoIncrement()
    val caseId      = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val planRunId   = integer("plan_run_id").references(PlanRuns.id, onDelete = ReferenceOption.CASCADE)
    val supplyId    = varchar("supply_id", 255)
    val demandId    = varchar("demand_id", 255).nullable()
    val qtyConsumed = double("qty_consumed")
    override val primaryKey = PrimaryKey(id)
}

/** Persisted material impact assessment results (rating + LLM explanation per supply change). */
object MaterialImpactAssessments : Table("material_impact_assessment") {
    val id                  = integer("id").autoIncrement()
    val caseId              = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val planRunId           = integer("plan_run_id").references(PlanRuns.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val supplyId            = varchar("supply_id", 255)
    val deliveryDelayDays   = integer("delivery_delay_days").default(0)
    val quantityDecreasePct = double("quantity_decrease_pct").default(0.0)
    val quantityDecreaseAbs = double("quantity_decrease_abs").nullable()  // absolute qty decrease; takes precedence over pct when set
    val criteria            = text("criteria")
    val rating              = varchar("rating", 10)   // LOW | MEDIUM | HIGH
    val explanation         = text("explanation")
    // Snapshot of impactedDemandCount at the time the assessment was rated. Cache hits
    // compare this to the freshly-computed count and invalidate the cache row when they
    // disagree — prevents serving stale "13 demands" text alongside a fresh "27 demands"
    // impacts[] payload.
    val impactedDemandCount = integer("impacted_demand_count").default(0)
    val createdAt           = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/**
 * In-flight negotiation waits — one row per (case, sessionKey) while the
 * material agent is paused at Step 1.6 awaiting a planner reply. Rows are
 * inserted by the agent and marked resolved by the reply handler.
 */
object NegotiationWaits : Table("negotiation_wait") {
    val id                    = integer("id").autoIncrement()
    val caseId                = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val sessionKey            = varchar("session_key", 255)
    val round                 = integer("round")
    val rating                = varchar("rating", 10)        // LOW | MEDIUM | HIGH
    val explanation           = text("explanation").nullable()
    val currentDelayDays      = integer("current_delay_days")
    val currentQtyPct         = double("current_qty_pct")
    val baselinePlanRunId     = integer("baseline_plan_run_id")
    val contingentPlanRunId   = integer("contingent_plan_run_id").nullable()
    val supplyId              = varchar("supply_id", 255)
    val impactedDemandCount   = integer("impacted_demand_count").default(0)
    val createdAt             = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val resolvedAt            = timestamp("resolved_at").nullable()
    val resolvedAction        = varchar("resolved_action", 16).nullable()  // keep | abandon | counter | superseded (historical rows may contain "accept")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Per-case key-value memory for the planning agent. The agent reads this at
 * the start of every conversation and writes via its `write_memory` tool when
 * it learns durable facts (user preferences, recurring goals, recent decisions
 * worth remembering across sessions). Value is a JSON-encoded string —
 * arbitrary shape, opaque to the backend; the agent owns the schema.
 *
 * `scope` is reserved for future expansion (per-user / global) — Phase 1
 * always writes "case" and the unique index keys on (case_id, scope, key).
 */
object AgentMemory : Table("agent_memory") {
    val id          = long("id").autoIncrement()
    val caseId      = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val scope       = varchar("scope", 32).default("case")
    val key         = varchar("key", 128)
    val value       = text("value")
    val updatedAt   = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
    init { uniqueIndex("uq_agent_memory_case_scope_key", caseId, scope, key) }
}

/**
 * Append-only lifecycle log for a plan run. Each mutation emits a row.
 * Event kinds: "created" | "saved" | "renamed" | "promoted" | "designated_active" | "undesignated".
 * payload is a kind-specific JSON blob (e.g., {"old_name": "...", "new_name": "..."}).
 * Cascades with the run — deleting a run discards its history.
 */
object PlanRunEvents : Table("plan_run_event") {
    val id          = integer("id").autoIncrement()
    val caseId      = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    val planRunId   = integer("plan_run_id").references(PlanRuns.id, onDelete = ReferenceOption.CASCADE)
    val kind        = varchar("kind", 32)
    val payload     = text("payload").nullable()
    val createdAt   = timestamp("created_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)
}

/**
 * Knowledge-base record. Independent persistence of a (config + KPI snapshot)
 * pair, dissociated from the plan_run that produced it. KB records survive
 * plan-run deletion — `sourcePlanRunDeleted` flips to true instead of cascading.
 *
 * Built from plan_runs at completion time (see KbStore.upsertFromPlanRun) but
 * read independently by the Expand-KB dialog and the planning agent. The
 * (caseId, signature) uniqueness reflects the library's "one row per
 * configuration shape" semantics — re-running the same config replaces the
 * snapshot rather than accumulating.
 */
object KbRecords : Table("kb_record") {
    val id                    = integer("id").autoIncrement()
    val caseId                = integer("case_id").references(Cases.id, onDelete = ReferenceOption.CASCADE)
    /** Canonical config signature; see CaseBootstrap.signatureFor. */
    val signature             = varchar("signature", 512)
    /** Library preset id when this row was seeded by bootstrap; null for ad-hoc / user-driven runs. */
    val presetId              = varchar("preset_id", 64).nullable()
    val presetLabel           = varchar("preset_label", 128).nullable()
    val primaryAxis           = varchar("primary_axis", 32).nullable()
    /** Full PlanningConfig JSON. */
    val config                = text("config")
    /** Headline KPI snapshot JSON: { fill_rate_pct, gini, p10_fill_ratio, ... }. */
    val kpisSnapshot          = text("kpis_snapshot")
    /** Most recent plan_run that wrote this record. NULL when the record was retained
     *  after the source run was deleted (paired with sourcePlanRunDeleted=true). */
    val sourcePlanRunId       = integer("source_plan_run_id").nullable()
    val sourcePlanRunDeleted  = bool("source_plan_run_deleted").default(false)
    val soundnessStatus       = varchar("soundness_status", 16).default("unchecked")
    val createdAt             = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val updatedAt             = timestamp("updated_at").defaultExpression(CurrentTimestamp)
    override val primaryKey = PrimaryKey(id)

    init { uniqueIndex("uq_kb_record_case_signature", caseId, signature) }
}
