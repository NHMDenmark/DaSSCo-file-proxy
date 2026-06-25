#!/usr/bin/env bash
# Demo the TUS 1.0.0 large-file upload endpoints implemented by LargeFiles.java.
#
# Requirements: bash, curl, python3, base64.
#
# Default local stack assumptions:
#   - file proxy: http://localhost:8080/file_proxy/api
#   - keycloak:   http://localhost:8083/keycloak, realm dassco
#   - postgres runs in docker container dassco-file-proxy-database-1
#   - PREPARE_LOCAL=1 creates a tiny local demo asset/share directly in postgres
#
# Quick start:
#   TOKEN=<jwt> ./scripts/demo-tus.sh
#
# Or let the script fetch a client-credentials token using the local defaults:
#   ./scripts/demo-tus.sh
#
# Useful overrides:
#   API_BASE=http://localhost:8081/file_proxy/api \
#   INSTITUTION=institution_1 COLLECTION=i1_c1 ASSET_GUID=deleteShare_1 \
#   ./scripts/demo-tus.sh
#
# For a non-local environment, disable direct postgres setup and provide a token:
#   PREPARE_LOCAL=0 TOKEN=<jwt> API_BASE=https://.../file_proxy/api ./scripts/demo-tus.sh
#
# If you specifically want to exercise the share API before the TUS calls:
#   CREATE_SHARE=1 ./scripts/demo-tus.sh
#
# Disable the between-step prompts for CI/repeat runs:
#   DEMO_PAUSE=0 ./scripts/demo-tus.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "${SCRIPT_DIR}/pom.xml" ]]; then
  PROJECT_ROOT="${SCRIPT_DIR}"
elif [[ -f "${SCRIPT_DIR}/../pom.xml" ]]; then
  PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
else
  PROJECT_ROOT="$(pwd)"
fi

API_BASE="${API_BASE:-http://localhost:8080/file_proxy/api}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8083/keycloak}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-dassco}"
KEYCLOAK_CLIENT_ID="${KEYCLOAK_CLIENT_ID:-dassco-file-proxy}"
KEYCLOAK_CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-TZIStDhwTLJSsVYaLBTI0HG7B0nNJ3px}"

INSTITUTION="${INSTITUTION:-tus-demo-institution}"
COLLECTION="${COLLECTION:-tus-demo-collection}"
ASSET_GUID="${ASSET_GUID:-tus-demo-asset}"
ALLOCATION_MB="${ALLOCATION_MB:-100}"
DEMO_USER="${DEMO_USER:-tus-demo}"
REMOTE_PATH="${REMOTE_PATH:-tus-demo/hello-tus.txt}"
CREATE_SHARE="${CREATE_SHARE:-0}"
DEMO_PAUSE="${DEMO_PAUSE:-1}"

# Local demo setup. Disable for shared/dev/staging/prod environments.
PREPARE_LOCAL="${PREPARE_LOCAL:-1}"
SHARE_MOUNT_FOLDER="${SHARE_MOUNT_FOLDER:-target}"
if [[ "${SHARE_MOUNT_FOLDER}" != /* ]]; then
  SHARE_MOUNT_FOLDER="${PROJECT_ROOT}/${SHARE_MOUNT_FOLDER}"
fi
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-dassco-file-proxy-database-1}"
POSTGRES_DB="${POSTGRES_DB:-dassco_file_proxy}"
POSTGRES_USER="${POSTGRES_USER:-dassco_file_proxy}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-dassco_file_proxy}"
POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
POSTGRES_PORT="${POSTGRES_PORT:-5433}"
START_ASSET_SERVICE_STUB="${START_ASSET_SERVICE_STUB:-${PREPARE_LOCAL}}"
ASSET_SERVICE_STUB_PORT="${ASSET_SERVICE_STUB_PORT:-8084}"

TMP_DIR="${TMP_DIR:-$(mktemp -d)}"
KEEP_TMP="${KEEP_TMP:-0}"
ASSET_SERVICE_STUB_PID=""

cleanup() {
  if [[ -n "${ASSET_SERVICE_STUB_PID}" ]]; then
    kill "${ASSET_SERVICE_STUB_PID}" >/dev/null 2>&1 || true
    wait "${ASSET_SERVICE_STUB_PID}" 2>/dev/null || true
  fi
  if [[ "${KEEP_TMP}" != "1" ]]; then
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup EXIT

if [[ "${KEEP_TMP}" == "1" ]]; then
  echo "Keeping temp files in ${TMP_DIR}"
fi

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require curl
require python3
require base64

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

section() {
  printf '\n\033[1;36m== %s ==\033[0m\n' "$1"
}

step_done() {
  local happened="$1"
  local next="${2:-}"
  printf '\n\033[1;32mWhat happened:\033[0m %s\n' "${happened}"
  if [[ -n "${next}" ]]; then
    printf '\033[1;34mNext we will:\033[0m %s\n' "${next}"
  fi
  if [[ "${DEMO_PAUSE}" == "1" && -r /dev/tty && -w /dev/tty ]]; then
    read -r -p "Press Enter to continue..." _ </dev/tty
  fi
}

b64() {
  printf '%s' "$1" | base64 | tr -d '\n'
}

origin_from_url() {
  python3 - "$1" <<'PY'
from urllib.parse import urlsplit
import sys
u = urlsplit(sys.argv[1])
print(f"{u.scheme}://{u.netloc}")
PY
}

sql_literal() {
  python3 - "$1" <<'PY'
import sys
print("'" + sys.argv[1].replace("'", "''") + "'")
PY
}

validate_segment() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[A-Za-z0-9_-]+$ ]]; then
    fail "${name}='${value}' contains characters not accepted by this TUS upload URI. Use only A-Z, a-z, 0-9, '_' or '-'."
  fi
}

psql_file() {
  local sql_file="$1"

  if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -Fxq "${POSTGRES_CONTAINER}"; then
    docker exec -i "${POSTGRES_CONTAINER}" psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" < "${sql_file}"
    return
  fi

  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="${POSTGRES_PASSWORD}" psql -v ON_ERROR_STOP=1 -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -f "${sql_file}"
    return
  fi

  fail "PREPARE_LOCAL=1 needs docker with container '${POSTGRES_CONTAINER}' or a local psql client. Set PREPARE_LOCAL=0 to skip DB setup."
}

start_asset_service_stub() {
  [[ "${START_ASSET_SERVICE_STUB}" == "1" ]] || return 0

  # FileService.largeFileUpload records an asset-change event after a successful upload
  # when the token's keycloak_id exists in dassco_user. In the local stack, the real
  # asset service often is not running, so provide the tiny endpoint needed for the demo.
  if curl -sS --max-time 1 -o /dev/null "http://localhost:${ASSET_SERVICE_STUB_PORT}/" >/dev/null 2>&1; then
    echo "Asset service/stub already listening on localhost:${ASSET_SERVICE_STUB_PORT}"
    return 0
  fi

  python3 - "${ASSET_SERVICE_STUB_PORT}" "${ASSET_GUID}" "${INSTITUTION}" "${COLLECTION}" > "${TMP_DIR}/asset-service-stub.log" 2>&1 <<'PY' &
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import sys

port = int(sys.argv[1])
asset_guid = sys.argv[2]
institution = sys.argv[3]
collection = sys.argv[4]

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        return

    def _read_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length:
            self.rfile.read(length)

    def _json(self, status, value):
        body = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _no_content(self):
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        if "/api/v1/assets/inprogress" in self.path:
            self._json(200, [])
            return
        if "/api/v1/assetmetadata/" in self.path:
            self._json(200, {
                "asset_guid": asset_guid,
                "asset_locked": False,
                "parent_guids": [],
                "institution": institution,
                "collection": collection,
            })
            return
        self._json(200, {"status": "ok", "service": "tus-demo-asset-service-stub"})

    def do_POST(self):
        self._read_body()
        self._no_content()

    def do_PUT(self):
        self._read_body()
        self._no_content()

HTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY
  ASSET_SERVICE_STUB_PID=$!

  for _ in {1..30}; do
    if curl -sS --max-time 1 -o /dev/null "http://localhost:${ASSET_SERVICE_STUB_PORT}/" >/dev/null 2>&1; then
      echo "Started local asset-service stub on localhost:${ASSET_SERVICE_STUB_PORT}"
      return 0
    fi
    sleep 0.1
  done

  fail "Asset-service stub did not start on localhost:${ASSET_SERVICE_STUB_PORT}"
}

prepare_local_share() {
  [[ "${PREPARE_LOCAL}" == "1" ]] || return 0

  validate_segment "INSTITUTION" "${INSTITUTION}"
  validate_segment "COLLECTION" "${COLLECTION}"
  validate_segment "ASSET_GUID" "${ASSET_GUID}"

  local share_uri="/assetfiles/${INSTITUTION}/${COLLECTION}/${ASSET_GUID}/"
  local local_share_dir="${SHARE_MOUNT_FOLDER%/}${share_uri}"
  mkdir -p "${local_share_dir}"

  local institution_sql collection_sql asset_sql user_sql node_host_sql share_uri_sql
  institution_sql=$(sql_literal "${INSTITUTION}")
  collection_sql=$(sql_literal "${COLLECTION}")
  asset_sql=$(sql_literal "${ASSET_GUID}")
  user_sql=$(sql_literal "${DEMO_USER}")
  node_host_sql=$(sql_literal "$(origin_from_url "${API_BASE}")")
  share_uri_sql=$(sql_literal "${share_uri}")

  local sql_file="${TMP_DIR}/prepare-local-share.sql"
  cat > "${sql_file}" <<SQL
DO \$\$
DECLARE
  v_collection_id int;
  v_workstation_id int;
  v_directory_id bigint;
BEGIN
  INSERT INTO institution(institution_name)
  SELECT ${institution_sql}
  WHERE NOT EXISTS (SELECT 1 FROM institution WHERE institution_name = ${institution_sql});

  SELECT collection_id INTO v_collection_id
  FROM collection
  WHERE collection_name = ${collection_sql} AND institution_name = ${institution_sql}
  LIMIT 1;

  IF v_collection_id IS NULL THEN
    INSERT INTO collection(collection_name, institution_name)
    VALUES (${collection_sql}, ${institution_sql})
    RETURNING collection_id INTO v_collection_id;
  END IF;

  SELECT workstation_id INTO v_workstation_id
  FROM workstation
  WHERE workstation_name = 'tus-demo-workstation' AND institution_name = ${institution_sql}
  LIMIT 1;

  IF v_workstation_id IS NULL THEN
    INSERT INTO workstation(workstation_name, institution_name, workstation_status)
    VALUES ('tus-demo-workstation', ${institution_sql}, 'IN_SERVICE')
    RETURNING workstation_id INTO v_workstation_id;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM asset WHERE asset_guid = ${asset_sql}) THEN
    INSERT INTO asset(
      asset_guid, asset_pid, asset_locked, subject, collection_id, digitiser_id,
      file_formats, payload_type, status, tags, workstation_id, internal_status,
      make_public, metadata_source, push_to_specify, metadata_version,
      camera_setting_control, date_asset_taken, date_asset_finalised,
      date_metadata_ingested, legality_id
    ) VALUES (
      ${asset_sql}, ${asset_sql} || '-pid', false, 'folder', v_collection_id, null,
      null, 'conventional', 'WORKING_COPY', null, v_workstation_id, 'METADATA_RECEIVED',
      true, 'tus-demo', true, 'demo',
      'demo', now(), now(),
      now(), null
    );
  END IF;

  SELECT d.directory_id INTO v_directory_id
  FROM directories d
  JOIN shared_assets sa ON sa.directory_id = d.directory_id
  WHERE d.access = 'WRITE'::access_type AND sa.asset_guid = ${asset_sql}
  LIMIT 1;

  IF v_directory_id IS NULL THEN
    INSERT INTO directories(uri, node_host, access, allocated_storage_mb, creation_datetime, specify_sync_log_id)
    VALUES (${share_uri_sql}, ${node_host_sql}, 'WRITE'::access_type, ${ALLOCATION_MB}, now(), null)
    RETURNING directory_id INTO v_directory_id;
  END IF;

  UPDATE directories
  SET allocated_storage_mb = GREATEST(allocated_storage_mb, ${ALLOCATION_MB}),
      awaiting_erda_sync = false
  WHERE directory_id = v_directory_id;

  DELETE FROM shared_assets WHERE asset_guid = ${asset_sql};
  INSERT INTO shared_assets(directory_id, asset_guid, creation_datetime)
  VALUES (v_directory_id, ${asset_sql}, now());

  INSERT INTO user_access(directory_id, username, token, creation_datetime)
  SELECT v_directory_id, ${user_sql}, '', now()
  WHERE NOT EXISTS (
    SELECT 1 FROM user_access WHERE directory_id = v_directory_id AND username = ${user_sql}
  );

  DELETE FROM active_large_uploads WHERE asset_guid = ${asset_sql};
END
\$\$;
SQL

  psql_file "${sql_file}" >/dev/null
  echo "Prepared local share directory: ${local_share_dir}"
  start_asset_service_stub
}

absolute_url() {
  local maybe_url="$1"
  if [[ "${maybe_url}" == http://* || "${maybe_url}" == https://* ]]; then
    printf '%s\n' "${maybe_url}"
  elif [[ "${maybe_url}" == /* ]]; then
    printf '%s%s\n' "$(origin_from_url "${API_BASE}")" "${maybe_url}"
  else
    printf '%s/%s\n' "${API_BASE%/}" "${maybe_url}"
  fi
}

expect_code() {
  local actual="$1"
  local expected_csv="$2"
  local label="$3"
  IFS=',' read -r -a expected <<< "${expected_csv}"
  for code in "${expected[@]}"; do
    if [[ "${actual}" == "${code}" ]]; then
      return 0
    fi
  done
  echo "Unexpected HTTP status for ${label}: got ${actual}, expected ${expected_csv}" >&2
  [[ -s "${LAST_BODY}" ]] && { echo "Response body:" >&2; cat "${LAST_BODY}" >&2; echo >&2; }
  exit 1
}

LAST_HEADERS=""
LAST_BODY=""
LAST_STATUS=""
request() {
  local label="$1"
  shift
  LAST_HEADERS="${TMP_DIR}/headers.$(date +%s%N)"
  LAST_BODY="${TMP_DIR}/body.$(date +%s%N)"

  echo "-- ${label}"
  set +e
  LAST_STATUS=$(curl -sS -D "${LAST_HEADERS}" -o "${LAST_BODY}" -w '%{http_code}' "$@")
  local curl_exit=$?
  set -e
  if [[ ${curl_exit} -ne 0 ]]; then
    echo "curl failed for ${label}" >&2
    exit ${curl_exit}
  fi

  echo "HTTP ${LAST_STATUS}"
  awk '{ line=$0; sub(/\r$/, "", line); lower=tolower(line); if (lower ~ /^(http\/|location:|tus-|upload-|content-type:|cache-control:)/) print "  " line }' "${LAST_HEADERS}"
  if [[ -s "${LAST_BODY}" ]]; then
    echo "Body:"
    cat "${LAST_BODY}"
    echo
  fi
}

get_header() {
  local header_name="$1"
  awk -v h="${header_name}" '{ line=$0; sub(/\r$/, "", line); lower=tolower(line); prefix=tolower(h) ":"; if (index(lower, prefix) == 1) { sub("^[^:]*:[[:space:]]*", "", line); value=line } } END{ print value }' "${LAST_HEADERS}"
}

fetch_token_if_needed() {
  if [[ -n "${TOKEN:-}" ]]; then
    echo "Using TOKEN from environment"
    return
  fi

  echo "Fetching Keycloak token..."
  local token_url="${KEYCLOAK_URL%/}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token"
  local token_json="${TMP_DIR}/token.json"
  curl -sS -X POST "${token_url}" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=client_credentials' \
    --data-urlencode "client_id=${KEYCLOAK_CLIENT_ID}" \
    --data-urlencode "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
    --data-urlencode 'scope=openid offline_access' \
    -o "${token_json}"

  TOKEN=$(python3 - "${token_json}" <<'PY'
import json, sys
with open(sys.argv[1], 'r', encoding='utf-8') as f:
    data = json.load(f)
if 'access_token' not in data:
    raise SystemExit(json.dumps(data, indent=2))
print(data['access_token'])
PY
)
  echo "Fetched token for client ${KEYCLOAK_CLIENT_ID}"
}

try_create_share() {
  [[ "${CREATE_SHARE}" == "1" ]] || return 0

  echo "Creating writable share via API..."
  local payload="${TMP_DIR}/create-share.json"
  cat > "${payload}" <<JSON
{
  "assets": [
    {
      "asset_guid": "${ASSET_GUID}",
      "parent_guids": [],
      "institution": "${INSTITUTION}",
      "collection": "${COLLECTION}"
    }
  ],
  "users": ["${DEMO_USER}"],
  "allocation_mb": ${ALLOCATION_MB}
}
JSON

  request "POST createShareInternal" \
    -X POST "${API_BASE%/}/shares/assets/${ASSET_GUID}/createShareInternal" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    --data-binary "@${payload}"

  if [[ "${LAST_STATUS}" =~ ^2 ]]; then
    return 0
  fi

  if grep -Eqi 'already checked out|writeable directory|writable directory' "${LAST_BODY}"; then
    echo "Continuing: share appears to already be writable."
    return 0
  fi

  fail "Could not create share. Use an asset that already has a writable share, or set CREATE_SHARE=0 and prepare it manually."
}

make_demo_file() {
  if [[ -n "${FILE:-}" ]]; then
    [[ -f "${FILE}" ]] || fail "FILE does not exist: ${FILE}"
    DEMO_FILE="${FILE}"
    return
  fi

  DEMO_FILE="${TMP_DIR}/hello-tus.txt"
  cat > "${DEMO_FILE}" <<'EOF'
Hello from a TUS resumable upload demo.

This file is intentionally uploaded in two PATCH requests:
1. create upload resource with POST
2. send the first chunk
3. ask the server for the current offset with HEAD
4. resume from that offset with a second PATCH

If this text is visible through /assetfiles after the demo, the upload completed.
EOF
}

split_demo_file() {
  FILE_SIZE=$(wc -c < "${DEMO_FILE}" | tr -d '[:space:]')
  [[ "${FILE_SIZE}" -gt 1 ]] || fail "Demo file must be at least 2 bytes"
  FIRST_CHUNK_SIZE="${FIRST_CHUNK_SIZE:-$(( FILE_SIZE / 2 ))}"
  [[ "${FIRST_CHUNK_SIZE}" -gt 0 && "${FIRST_CHUNK_SIZE}" -lt "${FILE_SIZE}" ]] || fail "FIRST_CHUNK_SIZE must be between 1 and $(( FILE_SIZE - 1 ))"
  SECOND_CHUNK_SIZE=$(( FILE_SIZE - FIRST_CHUNK_SIZE ))

  CHUNK_1="${TMP_DIR}/chunk-1.bin"
  CHUNK_2="${TMP_DIR}/chunk-2.bin"
  python3 - "${DEMO_FILE}" "${FIRST_CHUNK_SIZE}" "${CHUNK_1}" "${CHUNK_2}" <<'PY'
from pathlib import Path
import sys
source = Path(sys.argv[1]).read_bytes()
first = int(sys.argv[2])
Path(sys.argv[3]).write_bytes(source[:first])
Path(sys.argv[4]).write_bytes(source[first:])
PY
}

main() {
  section "Demo setup"
  fetch_token_if_needed
  prepare_local_share
  try_create_share
  make_demo_file
  split_demo_file

  local filename
  filename=$(basename "${DEMO_FILE}")
  local upload_url="${API_BASE%/}/large-files/${INSTITUTION}/${COLLECTION}/${ASSET_GUID}/upload"
  local assetfiles_url="${API_BASE%/}/assetfiles/${INSTITUTION}/${COLLECTION}/${ASSET_GUID}/"
  local local_uploaded_file="${SHARE_MOUNT_FOLDER%/}/assetfiles/${INSTITUTION}/${COLLECTION}/${ASSET_GUID}/${REMOTE_PATH#/}"
  local metadata="filename $(b64 "${filename}"),path $(b64 "${REMOTE_PATH}")"

  echo "Demo configuration:"
  cat <<EOF
Project root: ${PROJECT_ROOT}
API base:     ${API_BASE}
Asset:        ${INSTITUTION}/${COLLECTION}/${ASSET_GUID}
Upload URL:   ${upload_url}
Remote path:  ${REMOTE_PATH}
Local file:   ${local_uploaded_file}
File:         ${DEMO_FILE} (${FILE_SIZE} bytes)
Chunks:       ${FIRST_CHUNK_SIZE} + ${SECOND_CHUNK_SIZE} bytes
Local setup:  PREPARE_LOCAL=${PREPARE_LOCAL}
EOF
  step_done "Setup is complete: authentication is ready, local demo data is prepared if enabled, and the sample file has been split into two chunks." "ask the server which TUS capabilities it supports using OPTIONS."

  section "1. OPTIONS: show TUS capabilities"
  request "OPTIONS upload endpoint" \
    -X OPTIONS "${upload_url}" \
    -H "Authorization: Bearer ${TOKEN}"
  expect_code "${LAST_STATUS}" "200,204" "OPTIONS"
  step_done "The server advertised its TUS support, including version $(get_header Tus-Version) and extensions: $(get_header Tus-Extension)." "create a new TUS upload resource with POST and read its Location header."

  section "2. POST: create upload resource"
  request "POST create TUS upload" \
    -X POST "${upload_url}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Tus-Resumable: 1.0.0' \
    -H "Upload-Length: ${FILE_SIZE}" \
    -H "Upload-Metadata: ${metadata}"
  # The TUS spec uses 201 Created, but the embedded tus-java-server currently returns 204 with Location.
  expect_code "${LAST_STATUS}" "201,204" "POST create upload"

  local location
  location=$(get_header Location)
  [[ -n "${location}" ]] || fail "POST response did not include a Location header"
  local created_upload_url
  created_upload_url=$(absolute_url "${location}")
  echo "Created upload resource: ${created_upload_url}"
  step_done "The server created a dedicated upload resource. The client must use this Location URL for the following HEAD and PATCH requests." "check the initial server-side upload offset with HEAD."

  section "3. HEAD: initial offset"
  request "HEAD created upload" \
    -X HEAD "${created_upload_url}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Tus-Resumable: 1.0.0'
  expect_code "${LAST_STATUS}" "200,204" "HEAD initial offset"
  local offset
  offset=$(get_header Upload-Offset)
  [[ "${offset}" == "0" ]] || fail "Expected initial Upload-Offset 0, got '${offset}'"
  step_done "The new upload exists and currently has offset 0, meaning no bytes have been received yet." "send the first chunk with PATCH from Upload-Offset 0."

  section "4. PATCH: send first chunk"
  request "PATCH first chunk" \
    -X PATCH "${created_upload_url}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Tus-Resumable: 1.0.0' \
    -H 'Content-Type: application/offset+octet-stream' \
    -H 'Upload-Offset: 0' \
    --data-binary "@${CHUNK_1}"
  expect_code "${LAST_STATUS}" "204" "PATCH first chunk"
  local next_offset
  next_offset=$(get_header Upload-Offset)
  [[ "${next_offset}" == "${FIRST_CHUNK_SIZE}" ]] || fail "Expected Upload-Offset ${FIRST_CHUNK_SIZE}, got '${next_offset}'"
  step_done "The first ${FIRST_CHUNK_SIZE} bytes were accepted, and the server advanced Upload-Offset to ${next_offset}." "use HEAD again to recover the offset, just like a client would after an interrupted upload."

  section "5. HEAD: prove the upload can be resumed"
  request "HEAD after first chunk" \
    -X HEAD "${created_upload_url}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Tus-Resumable: 1.0.0'
  expect_code "${LAST_STATUS}" "200,204" "HEAD after first chunk"
  offset=$(get_header Upload-Offset)
  [[ "${offset}" == "${FIRST_CHUNK_SIZE}" ]] || fail "Expected resumable offset ${FIRST_CHUNK_SIZE}, got '${offset}'"
  step_done "A HEAD request recovered the current offset (${offset}) without uploading data; this is the value a client uses to resume after interruption." "intentionally send a chunk at the wrong offset to show the conflict protection."

  section "6. PATCH: deliberate wrong offset conflict"
  request "PATCH wrong offset, expect conflict" \
    -X PATCH "${created_upload_url}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Tus-Resumable: 1.0.0' \
    -H 'Content-Type: application/offset+octet-stream' \
    -H 'Upload-Offset: 0' \
    --data-binary "@${CHUNK_2}"
  expect_code "${LAST_STATUS}" "409" "PATCH wrong offset conflict"
  step_done "The server rejected a chunk sent at the wrong offset with HTTP 409, protecting the upload from corrupted byte order." "resume from the correct server offset and finish the upload."

  section "7. PATCH: resume from server offset and finish"
  request "PATCH second chunk" \
    -X PATCH "${created_upload_url}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Tus-Resumable: 1.0.0' \
    -H 'Content-Type: application/offset+octet-stream' \
    -H "Upload-Offset: ${offset}" \
    --data-binary "@${CHUNK_2}"
  expect_code "${LAST_STATUS}" "204" "PATCH second chunk"
  next_offset=$(get_header Upload-Offset)
  [[ "${next_offset}" == "${FILE_SIZE}" ]] || fail "Expected final Upload-Offset ${FILE_SIZE}, got '${next_offset}'"
  step_done "The client resumed from offset ${offset}, sent the remaining ${SECOND_CHUNK_SIZE} bytes, and the server reached the full file size (${FILE_SIZE})." "verify that the completed file is visible through the asset-files endpoint."

  section "8. GET /assetfiles: verify file is now visible"
  request "GET checked-out asset files" \
    -X GET "${assetfiles_url}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Accept: application/json'
  expect_code "${LAST_STATUS}" "200" "GET asset files"
  step_done "The uploaded file is now visible through the asset-files listing at the path from the TUS Upload-Metadata header. On this local setup it is written to ${local_uploaded_file}." "wrap up the demo and print the local file path."

  section "Done"
  echo "TUS demo completed successfully. Uploaded path metadata: ${REMOTE_PATH}"
}

main "$@"
