#!/usr/bin/env bash
# Smoke test for the Kotlin backend.
# Runs a full create-case → import-csv → allocate → poll → check-views flow.
# Prerequisites: backend is running on localhost:8000 and csv/ folder is populated.

set -euo pipefail

BASE="http://localhost:8000"
CSV_DIR="$(cd "$(dirname "$0")/.." && pwd)/csv"

ok()  { echo "[OK]  $*"; }
fail(){ echo "[FAIL] $*"; exit 1; }
check_status() {
  local label=$1 url=$2 expected=${3:-200}
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "$url")
  if [[ "$code" == "$expected" ]]; then ok "$label → $code"; else fail "$label → got $code, want $expected"; fi
}

echo "=== Kotlin backend smoke test ==="
echo "Base URL: $BASE"
echo ""

# 1. Health check
check_status "GET /health" "$BASE/health"

# 2. Create a case
echo ""
echo "--- Cases ---"
CASE_JSON=$(curl -s -X POST "$BASE/cases" \
  -H "Content-Type: application/json" \
  -d '{"name":"smoke-test"}')
CASE_ID=$(echo "$CASE_JSON" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
[[ -n "$CASE_ID" ]] || fail "Could not create case"
ok "POST /cases → id=$CASE_ID"

# 3. List cases
check_status "GET /cases" "$BASE/cases"

# 4. Import CSV
echo ""
echo "--- CSV import ---"
IMPORT_RESP=$(curl -s -X POST "$BASE/cases/$CASE_ID/import-csv" \
  -H "Content-Type: application/json" \
  -d "{\"csv_folder_path\":\"$CSV_DIR\"}")
echo "$IMPORT_RESP" | grep -q '"status"' || fail "Import did not return status"
ok "POST /cases/$CASE_ID/import-csv"

# 5. Run allocation
echo ""
echo "--- Allocation ---"
ALLOC_RESP=$(curl -s -X POST "$BASE/cases/$CASE_ID/allocate" \
  -H "Content-Type: application/json" \
  -d '{}')
RUN_ID=$(echo "$ALLOC_RESP" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
[[ -n "$RUN_ID" ]] || fail "Could not start allocation run"
ok "POST /cases/$CASE_ID/allocate → run_id=$RUN_ID"

# 6. Poll until done (max 60s)
echo "Polling run status..."
for i in $(seq 1 30); do
  STATUS=$(curl -s "$BASE/cases/$CASE_ID/runs/$RUN_ID/status" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
  echo "  attempt $i: $STATUS"
  if [[ "$STATUS" == "success" ]]; then ok "Run completed"; break; fi
  if [[ "$STATUS" == "failed" ]]; then fail "Run failed"; fi
  sleep 2
done
[[ "$STATUS" == "success" ]] || fail "Run did not complete within timeout"

# 7. Feasible demands
echo ""
echo "--- Views ---"
check_status "GET feasible-demands" "$BASE/cases/$CASE_ID/runs/$RUN_ID/feasible-demands"

# 8. Supply view
check_status "GET supply-view" "$BASE/cases/$CASE_ID/runs/$RUN_ID/supply-view"

# 9. Allocation actions (paginated)
check_status "GET allocation-actions" "$BASE/cases/$CASE_ID/runs/$RUN_ID/allocation-actions?limit=10"

# 10. Allocation view
check_status "GET allocation-view" "$BASE/cases/$CASE_ID/runs/$RUN_ID/allocation-view?max_actions=50&skip_basket=true"

# 11. Production trace
check_status "GET production-trace" "$BASE/cases/$CASE_ID/runs/$RUN_ID/production-trace"

# 12. Raw material usage
check_status "GET raw-material-usage" "$BASE/cases/$CASE_ID/runs/$RUN_ID/raw-material-usage"

# 13. Pegging — pick first demand
echo ""
echo "--- Pegging ---"
FIRST_DEMAND=$(curl -s "$BASE/cases/$CASE_ID/runs/$RUN_ID/feasible-demands" | grep -o '"demand_id":"[^"]*"' | head -1 | cut -d'"' -f4)
if [[ -n "$FIRST_DEMAND" ]]; then
  ENC_DEMAND=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$FIRST_DEMAND'))" 2>/dev/null || echo "$FIRST_DEMAND")
  check_status "GET pegging (demand-to-supply)" \
    "$BASE/cases/$CASE_ID/runs/$RUN_ID/pegging?direction=demand-to-supply&demand_id=$ENC_DEMAND"
else
  echo "[SKIP] No demand found for pegging test"
fi

# 14. Overrides
echo ""
echo "--- Overrides ---"
check_status "GET /overrides" "$BASE/cases/$CASE_ID/overrides"

OVERRIDE_RESP=$(curl -s -X POST "$BASE/cases/$CASE_ID/overrides" \
  -H "Content-Type: application/json" \
  -d '{"entity_type":"supply","entity_key":"test-key","payload":{"quantity":5}}')
OVERRIDE_ID=$(echo "$OVERRIDE_RESP" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
[[ -n "$OVERRIDE_ID" ]] || fail "Could not create override"
ok "POST /overrides → id=$OVERRIDE_ID"

curl -s -X DELETE "$BASE/cases/$CASE_ID/overrides/$OVERRIDE_ID" -o /dev/null
ok "DELETE /overrides/$OVERRIDE_ID"

# 15. Clean up
echo ""
echo "--- Cleanup ---"
DEL_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE/cases/$CASE_ID")
[[ "$DEL_CODE" == "204" ]] || fail "DELETE /cases/$CASE_ID → $DEL_CODE"
ok "DELETE /cases/$CASE_ID → 204"

echo ""
echo "=== All smoke tests passed ==="
