package com.allocator.api

import com.allocator.*
import com.allocator.services.CsvImportService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.toJavaInstant
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

private fun formatTs(ts: kotlinx.datetime.Instant): String =
    ISO.format(ts.toJavaInstant())

/**
 * Case CRUD routes — port of api/cases.py.
 * Prefix: /cases
 */
fun Routing.caseRoutes() {

    route("/cases") {

        // POST /cases — create
        post {
            val body = call.receive<CaseCreate>()
            val response = transaction {
                val insertedId = Cases.insert { it[name] = body.name }[Cases.id]
                val row = Cases.selectAll().where { Cases.id eq insertedId }.single()
                CaseResponse(
                    id = row[Cases.id],
                    name = row[Cases.name],
                    createdAt = formatTs(row[Cases.createdAt]),
                )
            }
            call.respond(HttpStatusCode.Created, response)
        }

        // GET /cases — list all (newest first) with counts
        get {
            val cases = transaction {
                Cases.selectAll().orderBy(Cases.createdAt, SortOrder.DESC).map { row ->
                    val caseId = row[Cases.id]
                    val demandCount   = Demands.selectAll().where { Demands.caseId eq caseId }.count().toInt()
                    val supplyCount   = Supplies.selectAll().where { Supplies.caseId eq caseId }.count().toInt()
                    val allocRunCount = AllocationRuns.selectAll().where { AllocationRuns.caseId eq caseId }.count().toInt()
                    val planRunCount  = PlanRuns.selectAll().where { PlanRuns.caseId eq caseId }.count().toInt()
                    CaseDetailResponse(
                        id = caseId,
                        name = row[Cases.name],
                        createdAt = formatTs(row[Cases.createdAt]),
                        demandCount   = demandCount,
                        supplyCount   = supplyCount,
                        runCount      = allocRunCount,
                        planRunCount  = planRunCount,
                    )
                }
            }
            call.respond(cases)
        }

        route("/{case_id}") {

            // GET /cases/{case_id} — detail with counts
            get {
                val caseId = call.parameters["case_id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid case_id")
                val detail = transaction {
                    val c = Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                        ?: throw NoSuchElementException("Case not found")
                    val demandCount   = Demands.selectAll().where { Demands.caseId eq caseId }.count().toInt()
                    val supplyCount   = Supplies.selectAll().where { Supplies.caseId eq caseId }.count().toInt()
                    val allocRunCount = AllocationRuns.selectAll().where { AllocationRuns.caseId eq caseId }.count().toInt()
                    val planRunCount  = PlanRuns.selectAll().where { PlanRuns.caseId eq caseId }.count().toInt()
                    val designatedId  = c[Cases.designatedActivePlanRunId]
                    val successIds    = PlanRuns.selectAll()
                        .where { (PlanRuns.caseId eq caseId) and (PlanRuns.status eq "success") }
                        .map { it[PlanRuns.id] }
                    val activePlanRunId = com.allocator.services.resolveActiveRunId(designatedId, successIds)
                    CaseDetailResponse(
                        id = c[Cases.id],
                        name = c[Cases.name],
                        createdAt = formatTs(c[Cases.createdAt]),
                        demandCount  = demandCount,
                        supplyCount  = supplyCount,
                        runCount     = allocRunCount,
                        planRunCount = planRunCount,
                        activePlanRunId = activePlanRunId,
                    )
                }
                call.respond(detail)
            }

            // PUT /cases/{case_id} — update name
            put {
                val caseId = call.parameters["case_id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid case_id")
                val body = call.receive<CaseUpdate>()
                val response = transaction {
                    Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                        ?: throw NoSuchElementException("Case not found")
                    if (body.name != null) {
                        Cases.update({ Cases.id eq caseId }) { it[name] = body.name }
                    }
                    val row = Cases.selectAll().where { Cases.id eq caseId }.single()
                    CaseResponse(
                        id = row[Cases.id],
                        name = row[Cases.name],
                        createdAt = formatTs(row[Cases.createdAt]),
                    )
                }
                call.respond(response)
            }

            // DELETE /cases/{case_id}
            delete {
                val caseId = call.parameters["case_id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid case_id")
                transaction {
                    val count = Cases.deleteWhere { Cases.id eq caseId }
                    if (count == 0) throw NoSuchElementException("Case not found")
                }
                call.respond(HttpStatusCode.NoContent)
            }

            // POST /cases/{case_id}/import-csv
            post("/import-csv") {
                val caseId = call.parameters["case_id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid case_id")
                val folderPathParam = call.request.queryParameters["folder_path"]

                transaction {
                    Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                        ?: throw NoSuchElementException("Case not found")
                }

                val folder = resolveCsvFolder(folderPathParam)
                if (!folder.toFile().isDirectory) {
                    throw IllegalArgumentException("Folder not found: $folder")
                }

                CsvImportService.importFromFolder(caseId, folder)
                call.respond(ImportCsvResponse(status = "ok", caseId = caseId))
            }

            // POST /cases/{case_id}/upload-csv — multipart upload of CSV files not mounted into the container
            post("/upload-csv") {
                val caseId = call.parameters["case_id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid case_id")

                transaction {
                    Cases.selectAll().where { Cases.id eq caseId }.singleOrNull()
                        ?: throw NoSuchElementException("Case not found")
                }

                val filesByName = mutableMapOf<String, ByteArray>()
                val multipart = call.receiveMultipart()
                var part = multipart.readPart()
                while (part != null) {
                    if (part is PartData.FileItem) {
                        val filename = part.originalFileName
                            ?.substringAfterLast('/')
                            ?.substringAfterLast('\\')
                            ?.lowercase()
                        if (filename != null && filename in CsvImportService.KNOWN_FILENAMES) {
                            filesByName[filename] = part.streamProvider().use { it.readBytes() }
                        }
                    }
                    part.dispose()
                    part = multipart.readPart()
                }

                if (filesByName.isEmpty()) {
                    throw IllegalArgumentException(
                        "No recognized CSV files in upload (expected one of: ${CsvImportService.KNOWN_FILENAMES.sorted().joinToString(", ")})"
                    )
                }

                CsvImportService.importFromUploads(caseId, filesByName)
                call.respond(ImportCsvResponse(status = "ok", caseId = caseId))
            }
        }
    }
}

/**
 * Resolve the CSV folder path — mirrors _csv_folder_path() in cases.py.
 * Tries: provided path → config.csvRootPath relative to cwd → ../csv fallback.
 */
private fun resolveCsvFolder(folderPath: String?): Path {
    if (folderPath != null) {
        val p = Paths.get(folderPath)
        return if (p.isAbsolute) p else Paths.get("").toAbsolutePath().resolve(p)
    }
    val base = Paths.get("").toAbsolutePath()
    val candidate = base.resolve(config.csvRootPath)
    if (candidate.toFile().isDirectory) return candidate
    val parent = base.parent?.resolve(config.csvRootPath)
    if (parent != null && parent.toFile().isDirectory) return parent
    return base.resolve(config.csvRootPath)
}
