package com.allocator

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.DriverManager

private val log = LoggerFactory.getLogger("com.allocator.Database")

/**
 * Converts a postgresql:// URL (Python/SQLAlchemy style) to a JDBC URL.
 * e.g. postgresql://user:pass@host:5432/dbname → jdbc:postgresql://host:5432/dbname
 */
fun toJdbcUrl(url: String): Triple<String, String, String> {
    // Strip scheme
    val noScheme = url.removePrefix("postgresql://").removePrefix("postgres://")
    val atIdx = noScheme.lastIndexOf('@')
    val credentials = noScheme.substring(0, atIdx)
    val hostAndDb = noScheme.substring(atIdx + 1)
    val (user, password) = if (credentials.contains(':')) {
        credentials.substringBefore(':') to credentials.substringAfter(':')
    } else {
        credentials to ""
    }
    return Triple("jdbc:postgresql://$hostAndDb", user, password)
}

fun buildDataSource(databaseUrl: String): HikariDataSource {
    val (jdbcUrl, user, password) = toJdbcUrl(databaseUrl)
    val hc = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        this.username = user
        this.password = password
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        connectionTimeout = 30_000
        validationTimeout = 5_000
        connectionTestQuery = "SELECT 1"
    }
    return HikariDataSource(hc)
}

/**
 * Wait for PostgreSQL to be ready, create DB if missing, connect, run schema setup.
 * Mirrors the Python startup() hook in main.py.
 */
fun initDatabase() {
    val (jdbcUrl, user, password) = toJdbcUrl(config.databaseUrl)
    val dbName = jdbcUrl.substringAfterLast('/')

    // Wait up to 30s for Postgres
    log.info("Waiting for PostgreSQL at $jdbcUrl ...")
    for (attempt in 0..29) {   
        try {
            DriverManager.getConnection(jdbcUrl, user, password).use { it.createStatement().execute("SELECT 1") }
            break
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if ("does not exist" in msg || "database \"$dbName\" does not exist" in msg) {
                log.info("Database '$dbName' does not exist — creating it...")
                createDatabase(jdbcUrl, user, password, dbName)
                break
            }
            if (attempt == 29) throw e
            log.warn("DB not ready (attempt ${attempt + 1}/30): ${e.message}")
            Thread.sleep(1_000)
        }
    }

    val dataSource = buildDataSource(config.databaseUrl)
    Database.connect(dataSource)
    log.info("Connected to database.")

    transaction {
        createTables()
        migrateSchema()
    }
    log.info("Schema ready.")

    // Data migrations (run after DDL). Each is idempotent — a no-op once
    // the touched rows are already in the target form. Order matters:
    // ScopeRenameMigration converges engine→scope first; then
    // ConfigRetirementMigration retires scope + depth_optimal entirely and
    // recomputes signatures so dedup survives.
    com.allocator.services.ScopeRenameMigration.run()
    com.allocator.services.ConfigRetirementMigration.run()
}

/** Create the application database via a connection to the 'postgres' maintenance DB. */
private fun createDatabase(jdbcUrl: String, user: String, password: String, dbName: String) {
    val postgresUrl = jdbcUrl.substringBeforeLast('/') + "/postgres"
    DriverManager.getConnection(postgresUrl, user, password).use { conn ->
        conn.autoCommit = true
        conn.createStatement().execute("""CREATE DATABASE "$dbName"""")
    }
    log.info("Database '$dbName' created.")
}

private fun createTables() {
    SchemaUtils.createMissingTablesAndColumns(
        Cases, Boms, Customers, Locations, Products, Vendors,
        Demands, MethodBuys, MethodMakes, ProductLocations,
        Operations, Bors, Resources,
        Supplies, MethodMoves, AllocationRuns, AllocationActions,
        PlanRuns, MaterialEvents, WoScheduleEvents, MaterialImpactAssessments,
        PlanPegging, PlanSupplyAllocations, CaseAllocations, NegotiationWaits, PlanRunEvents, AgentMemory,
        KbRecords, CasePreferences, CasePreferenceConfigs
    )
}

/**
 * Idempotent schema migrations for columns added after initial release.
 * Mirrors the _migrate_schema() function in Python's main.py.
 */
/** Returns ALTER TABLE statements to drop and recreate FK constraints with ON DELETE CASCADE. */
private fun cascadeFkMigrations(): Array<String> {
    // table -> (fk_constraint_name, fk_column, referenced_table)
    val caseFks = listOf(
        Triple("bom",            "fk_bom_case_id__id",            "cases"),
        Triple("customer",       "fk_customer_case_id__id",       "cases"),
        Triple("location",       "fk_location_case_id__id",       "cases"),
        Triple("product",        "fk_product_case_id__id",        "cases"),
        Triple("vendor",         "fk_vendor_case_id__id",         "cases"),
        Triple("demand",         "fk_demand_case_id__id",         "cases"),
        Triple("method_buy",     "fk_method_buy_case_id__id",     "cases"),
        Triple("method_make",    "fk_method_make_case_id__id",    "cases"),
        Triple("productlocation","fk_productlocation_case_id__id","cases"),
        Triple("supply",         "fk_supply_case_id__id",         "cases"),
        Triple("method_move",    "fk_method_move_case_id__id",    "cases"),
        Triple("allocation_run", "fk_allocation_run_case_id__id", "cases"),
    )
    val actionFk = Triple("allocation_action", "fk_allocation_action_run_id__id", "allocation_run")

    return (caseFks + actionFk).flatMap { (table, constraint, refTable) ->
        val fkCol = if (refTable == "cases") "case_id" else "run_id"
        listOf(
            """
            DO ${'$'}${'$'}
            BEGIN
              IF EXISTS (
                SELECT 1 FROM information_schema.table_constraints
                WHERE constraint_name = '$constraint' AND table_name = '$table'
              ) THEN
                ALTER TABLE $table DROP CONSTRAINT $constraint;
              END IF;
              ALTER TABLE $table ADD CONSTRAINT $constraint
                FOREIGN KEY ($fkCol) REFERENCES $refTable(id) ON DELETE CASCADE;
            END
            ${'$'}${'$'};
            """.trimIndent()
        )
    }.toTypedArray()
}

private fun migrateSchema() {
    val stmts = listOf(
        "ALTER TABLE method_make ADD COLUMN IF NOT EXISTS lead_time INTEGER",
        "ALTER TABLE allocation_action ADD COLUMN IF NOT EXISTS output_period INTEGER",
        "ALTER TABLE allocation_action ADD COLUMN IF NOT EXISTS edge_type VARCHAR(32)",
        "ALTER TABLE allocation_action ADD COLUMN IF NOT EXISTS scarcity_rank INTEGER",
        "ALTER TABLE allocation_action ADD COLUMN IF NOT EXISTS req_rates JSON",
        // Rename old table if it exists
        """
        DO ${'$'}${'$'}
        BEGIN
          IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = 'public' AND table_name = 'transportation'
          ) THEN
            ALTER TABLE transportation RENAME TO method_move;
          END IF;
        END
        ${'$'}${'$'};
        """.trimIndent(),
        // plan_run FK with CASCADE
        """
        DO ${'$'}${'$'}
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_plan_run_case_id__id' AND table_name = 'plan_run'
          ) THEN
            ALTER TABLE plan_run ADD CONSTRAINT fk_plan_run_case_id__id
              FOREIGN KEY (case_id) REFERENCES cases(id) ON DELETE CASCADE;
          END IF;
        END
        ${'$'}${'$'};
        """.trimIndent(),
        // Assessment criteria column on cases
        "ALTER TABLE cases ADD COLUMN IF NOT EXISTS assessment_criteria TEXT",
        // Metadata column on plan_run (used for contingent runs from material-impact)
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS metadata TEXT",
        // Name and notes on plan_run
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS notes TEXT",
        // Absolute qty decrease on material_event (takes precedence over pct when set)
        "ALTER TABLE material_event ADD COLUMN IF NOT EXISTS qty_decrease_abs DOUBLE PRECISION",
        // Absolute qty decrease on material_impact_assessment
        "ALTER TABLE material_impact_assessment ADD COLUMN IF NOT EXISTS quantity_decrease_abs DOUBLE PRECISION",
        // Negotiation-chain columns on plan_run (multi-round material-agent negotiation)
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS negotiation_round INTEGER",
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS parent_plan_run_id INTEGER",
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS superseded_by_plan_run_id INTEGER",
        "CREATE INDEX IF NOT EXISTS ix_plan_run_parent ON plan_run (parent_plan_run_id)",
        "CREATE INDEX IF NOT EXISTS ix_plan_run_superseded_by ON plan_run (superseded_by_plan_run_id)",
        // User-designated active plan run (overrides default "latest success" resolver)
        "ALTER TABLE cases ADD COLUMN IF NOT EXISTS designated_active_plan_run_id INTEGER",
        """
        DO ${'$'}${'$'}
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_cases_designated_active_plan_run_id__id' AND table_name = 'cases'
          ) THEN
            ALTER TABLE cases ADD CONSTRAINT fk_cases_designated_active_plan_run_id__id
              FOREIGN KEY (designated_active_plan_run_id) REFERENCES plan_run(id) ON DELETE SET NULL;
          END IF;
        END
        ${'$'}${'$'};
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS ix_plan_run_event_run ON plan_run_event (plan_run_id)",
        "CREATE INDEX IF NOT EXISTS ix_plan_run_event_case ON plan_run_event (case_id)",
        // Elapsed time tracking on plan_run
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP",
        // Depth chosen by optimal-depth search (null when not used)
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS chosen_depth INTEGER",
        // Per-depth attempt durations for optimal-depth search (JSON array)
        "ALTER TABLE plan_run ADD COLUMN IF NOT EXISTS attempts TEXT",
        // Proportional lot entitlement alongside consumed qty
        "ALTER TABLE plan_supply_allocation ADD COLUMN IF NOT EXISTS qty_allocated DOUBLE PRECISION",
        // Fix FK constraints to use ON DELETE CASCADE (idempotent: drop if exists, re-add)
        *cascadeFkMigrations(),
        // Phase-out: drop manual override table and its snapshot column on plan_run
        "ALTER TABLE plan_run DROP COLUMN IF EXISTS override_snapshot",
        "DROP TABLE IF EXISTS manual_override",
    )
    val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
    stmts.forEach { sql ->
        conn.prepareStatement(sql, false).executeUpdate()
    }
}
