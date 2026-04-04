# CLAUDE.md

This file provides guidance to Claude Code when working in this repository.

## What this repo is
A **Kotlin/Ktor port** of the original Python/FastAPI allocation engine at `../allocator_inno`.
The frontend (Next.js) and database (PostgreSQL) are unchanged. Only the backend was ported.

---

## Commands

### Build & run locally (no Docker)
```bash
cd backend-kotlin
export DATABASE_URL=postgresql://postgres:postgres@localhost:5432/allocator
export CSV_ROOT_PATH=../csv
./gradlew run
# or build a fat JAR:
./gradlew shadowJar
java -jar build/libs/allocator-backend.jar
```

### Docker (full stack)
```bash
docker compose up --build
# backend builds via backend-kotlin/Dockerfile (multi-stage: JDK builder → JRE runtime)
```

### Frontend only (hot reload)
```bash
cd frontend
npm install
npm run dev
```

### Smoke test (requires backend running on :8000)
```bash
./scripts/smoke_test_kotlin.sh
```

### Compile check (fast)
```bash
cd backend-kotlin
./gradlew compileKotlin
```

---

## Stack

| Layer | Technology |
|-------|-----------|
| Backend | Ktor 2.3.12 (Netty), Kotlin 1.9 |
| ORM | Exposed 0.55.0 |
| Connection pool | HikariCP 5.1.0 |
| Serialization | kotlinx-serialization-json 1.7.3 |
| CSV parsing | OpenCSV 5.9 |
| Database | PostgreSQL (same schema as Python version) |
| Frontend | Next.js 14 App Router (unchanged) |

---

## Kotlin backend layout (`backend-kotlin/src/main/kotlin/com/allocator/`)

```
Application.kt        — main() entry point
Config.kt             — reads DATABASE_URL, CSV_ROOT_PATH, OPENAI_API_KEY from env
Database.kt           — JDBC init, schema creation, idempotent migrations
Tables.kt             — 15 Exposed Table objects (same schema as Python models.py)
Models.kt             — kotlinx-serialization DTOs for requests/responses
Plugins.kt            — installs ContentNegotiation, CORS, CallLogging, StatusPages; registers all routes

api/
  Cases.kt            — POST/GET /cases, GET/PUT/DELETE /cases/{id}, POST /cases/{id}/import-csv
  Overrides.kt        — GET/POST/PUT /cases/{id}/overrides, DELETE .../overrides/{override_id}
  Allocate.kt         — POST /cases/{id}/allocate (async), GET runs, GET .../feasible-demands,
                        GET .../plan/products-with-real-bom, GET .../plan/moves-with-transit
  Pegging.kt          — GET /cases/{id}/runs/{runId}/pegging?direction=...
                        Also contains feasibleDemandsFromActions() used by other routes.
  Views.kt            — GET supply-view, production-trace, raw-material-usage, trace-component,
                        allocation-actions, allocation-view, allocation-view-basket
  Explanations.kt     — GET explanations, GET allocation-explanation

services/
  CsvImportService.kt — imports 11 CSV tables into DB (port of csv_import.py)
  CaseLoader.kt       — loads all case data into Map<String, List<Map<String,Any?>>>
  TimeUtils.kt        — period index, supply/demand period helpers
  SkuPatterns.kt      — raw material SKU pattern matching (1xx-xxxx, 2xx-xxxx, 3xx-xxxx)
  AllocationEngine.kt — core allocation algorithm (port of allocation_engine.py, 35KB Python)
  PlanningEngine.kt   — recursive demand→supply planning (port of planning_engine.py, 54KB Python)
```

---

## Critical Exposed 0.55 API note
Exposed 0.55 **deprecated** the old `.select { condition }` syntax.
**Always use:**
```kotlin
Table.selectAll().where { condition }   // ✓ correct
Table.select { condition }              // ✗ deprecated / error
```

---

## Architecture

### Data Model (Inventory Graph DAG)
- **Nodes**: Inventory = (product, location, quantity, time/period)
- **Supplies/Demands**: Terminal nodes
- **Edges (Methods)**: `method_move` (transport), `method_make` (manufacture via BOM), `method_buy` (purchase)
- **BOM logic**: AND within same `alt_group`, OR across groups

### Key algorithms
- **AllocationEngine**: Scarcity-based proportional split. Processes components in ascending scarcity order (total qty); splits available qty across demands proportional to `target_weight` (customer priority × demand qty). Multi-pass: supply pass → production pass → demand fulfillment.
- **PlanningEngine**: Recursive demand→supply traversal (MAX_PLAN_DEPTH=500), cycle detection via `Set<Pair<String,String>>`, lot batching, pegging node construction.

### Period indexing
Period 0 = preexisting (null date). Periods 1, 2, … = chronological distinct dates across all supplies + demands.

### Background allocation
`POST /cases/{id}/allocate` creates an `AllocationRun` (status=running) and fires `runAllocationBackground()` in a Kotlin coroutine (`CoroutineScope(Dispatchers.IO)`). The run is polled via `GET .../status`.

### JSON handling
- DB text columns that store JSON (e.g. `req_component_ids`, `req_rates`, `config`, `payload`) are parsed with `kotlinx.serialization.json.Json.parseToJsonElement(...)`.
- Route responses that return complex/dynamic structures are built with `buildJsonObject { }` / `buildJsonArray { }`.

---

## Database schema
Core tables: `cases`, `demand`, `supply`, `product`, `location`, `customer`, `vendor`, `bom`, `method_make`, `method_move`, `method_buy`, `productlocation`
Results tables: `allocation_run`, `allocation_action`, `manual_override`

All domain tables carry `case_id` FK with CASCADE DELETE.

---

## Sample data
CSV files in `csv/` directory. After starting the backend, create a case via `POST /cases`, then `POST /cases/{id}/import-csv` with `{"csv_folder_path": "/path/to/csv"}` (or omit to use the `CSV_ROOT_PATH` env default).

---

## What is NOT ported (intentionally)
- `planning_copilot.py` — OpenAI natural-language intent parser (optional UI feature, not part of the allocation engine)
