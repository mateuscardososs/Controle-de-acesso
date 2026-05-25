#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.production"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.prod.yml"
SCOPE="${1:-${BACKUP_SCOPE:-hourly}}"
STAMP="$(date -u +%Y%m%d-%H%M%SZ)"

if [[ "${SCOPE}" != "hourly" && "${SCOPE}" != "daily" && "${SCOPE}" != "manual" ]]; then
  echo "Uso: ./scripts/backup.sh [hourly|daily|manual]"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Arquivo .env.production nao encontrado."
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

DB_NAME="${POSTGRES_DB:-access_control}"
DB_USER="${POSTGRES_USER:-access_user}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-access-control-prod}"
BACKUP_ROOT="${BACKUP_DIR:-${ROOT_DIR}/backups}"
DAILY_RETENTION_DAYS="${BACKUP_DAILY_RETENTION_DAYS:-90}"
HOURLY_RETENTION_DAYS="${BACKUP_HOURLY_RETENTION_DAYS:-14}"
MANUAL_RETENTION_DAYS="${BACKUP_MANUAL_RETENTION_DAYS:-90}"
RUN_DIR="${BACKUP_ROOT}/runs/${STAMP}"
DB_DIR="${BACKUP_ROOT}/db/${SCOPE}"
FILES_DIR="${BACKUP_ROOT}/files/${STAMP}"
LOGS_DIR="${BACKUP_ROOT}/logs/${STAMP}"
MANIFESTS_DIR="${BACKUP_ROOT}/manifests"

mkdir -p "${RUN_DIR}/db" "${RUN_DIR}/files" "${RUN_DIR}/logs" "${DB_DIR}" "${FILES_DIR}" "${LOGS_DIR}" "${MANIFESTS_DIR}"

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

wait_for_postgres() {
  local attempts=30
  until compose exec -T postgres pg_isready -U "${DB_USER}" -d "${DB_NAME}" >/dev/null 2>&1; do
    attempts=$((attempts - 1))
    if [[ "${attempts}" -le 0 ]]; then
      echo "PostgreSQL nao ficou pronto para backup."
      exit 1
    fi
    sleep 2
  done
}

echo "Iniciando backup ${SCOPE} em ${STAMP}."
compose up -d postgres >/dev/null
wait_for_postgres

compose exec -T postgres \
  pg_dump -U "${DB_USER}" -d "${DB_NAME}" \
  --format=custom --no-owner --no-acl > "${RUN_DIR}/db/postgres.dump"
cp "${RUN_DIR}/db/postgres.dump" "${DB_DIR}/${STAMP}.dump"

docker run --rm \
  -v "${PROJECT_NAME}_uploads_faces:/data:ro" \
  -v "${RUN_DIR}/files:/backup" \
  alpine:3.20 sh -c "cd /data && tar -czf /backup/uploads-faces.tar.gz ."
cp "${RUN_DIR}/files/uploads-faces.tar.gz" "${FILES_DIR}/uploads-faces.tar.gz"

docker run --rm \
  -v "${PROJECT_NAME}_backend_logs:/backend-logs:ro" \
  -v "${PROJECT_NAME}_nginx_logs:/nginx-logs:ro" \
  -v "${RUN_DIR}/logs:/backup" \
  alpine:3.20 sh -c "tar -czf /backup/technical-logs.tar.gz -C / backend-logs nginx-logs"
cp "${RUN_DIR}/logs/technical-logs.tar.gz" "${LOGS_DIR}/technical-logs.tar.gz"

GIT_COMMIT="$(git -C "${ROOT_DIR}" rev-parse --short=12 HEAD 2>/dev/null || echo "unavailable")"
APP_VERSION="$(grep -m 1 '<version>' "${ROOT_DIR}/pom.xml" | sed -E 's:.*<version>([^<]+)</version>.*:\1:' || true)"
ACTIVE_CONTAINERS="$(compose ps --format 'table {{.Name}}\t{{.Service}}\t{{.State}}\t{{.Status}}' 2>/dev/null || true)"
SIZE_REPORT="$(du -h "${RUN_DIR}/db/postgres.dump" "${RUN_DIR}/files/uploads-faces.tar.gz" "${RUN_DIR}/logs/technical-logs.tar.gz")"

cat > "${RUN_DIR}/manifest.txt" <<EOF
environment=${APP_ENVIRONMENT:-production-local}
scope=${SCOPE}
created_at_utc=${STAMP}
git_commit=${GIT_COMMIT}
application_version=${APP_VERSION:-unknown}
compose_project=${PROJECT_NAME}
database=${DB_NAME}

files:
${SIZE_REPORT}

active_containers:
${ACTIVE_CONTAINERS}

restore:
./scripts/restore.sh "${RUN_DIR}"
EOF

cp "${RUN_DIR}/manifest.txt" "${MANIFESTS_DIR}/${STAMP}.txt"
cp "${RUN_DIR}/manifest.txt" "${MANIFESTS_DIR}/latest-success.txt"
cat > "${BACKUP_ROOT}/latest-success.txt" <<EOF
created_at_utc=${STAMP}
scope=${SCOPE}
run_dir=${RUN_DIR}
manifest=${RUN_DIR}/manifest.txt
postgres_dump=${RUN_DIR}/db/postgres.dump
EOF

docker run --rm \
  -v "${PROJECT_NAME}_backups_data:/backup-volume" \
  -v "${RUN_DIR}:/backup-source:ro" \
  alpine:3.20 sh -c "mkdir -p /backup-volume/runs && rm -rf /backup-volume/runs/${STAMP} && cp -a /backup-source /backup-volume/runs/${STAMP}"

find "${BACKUP_ROOT}/db/hourly" -type f -name "*.dump" -mtime +"${HOURLY_RETENTION_DAYS}" -delete 2>/dev/null || true
find "${BACKUP_ROOT}/db/daily" -type f -name "*.dump" -mtime +"${DAILY_RETENTION_DAYS}" -delete 2>/dev/null || true
find "${BACKUP_ROOT}/db/manual" -type f -name "*.dump" -mtime +"${MANUAL_RETENTION_DAYS}" -delete 2>/dev/null || true
find "${BACKUP_ROOT}/runs" -mindepth 1 -maxdepth 1 -type d -mtime +"${DAILY_RETENTION_DAYS}" -exec rm -rf {} + 2>/dev/null || true
find "${BACKUP_ROOT}/files" -mindepth 1 -maxdepth 1 -type d -mtime +"${DAILY_RETENTION_DAYS}" -exec rm -rf {} + 2>/dev/null || true
find "${BACKUP_ROOT}/logs" -mindepth 1 -maxdepth 1 -type d -mtime +"${DAILY_RETENTION_DAYS}" -exec rm -rf {} + 2>/dev/null || true
find "${BACKUP_ROOT}/manifests" -type f -name "*.txt" ! -name "latest-success.txt" -mtime +"${DAILY_RETENTION_DAYS}" -delete 2>/dev/null || true

echo "Backup criado em ${RUN_DIR}"
echo "Ultimo sucesso: ${BACKUP_ROOT}/latest-success.txt"
