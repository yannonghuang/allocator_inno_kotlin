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
        Supplies, MethodMoves, AllocationRuns, AllocationActions,
        ManualOverrides
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
        Triple("manual_override","fk_manual_override_case_id__id","cases"),
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
        // Fix FK constraints to use ON DELETE CASCADE (idempotent: drop if exists, re-add)
        *cascadeFkMigrations()
    )
    val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
    stmts.forEach { sql ->
        conn.prepareStatement(sql, false).executeUpdate()
    }
}
