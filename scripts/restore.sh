#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.production"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.prod.yml"
BACKUP_PATH="${1:-}"

if [[ -z "${BACKUP_PATH}" ]]; then
  echo "Uso: ./scripts/restore.sh backups/runs/YYYYmmdd-HHMMSSZ"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Arquivo .env.production nao encontrado."
  exit 1
fi

if [[ ! -e "${BACKUP_PATH}" ]]; then
  echo "Backup nao encontrado: ${BACKUP_PATH}"
  exit 1
fi

BACKUP_PATH="$(cd "${BACKUP_PATH}" && pwd)"
DB_DUMP="${BACKUP_PATH}/db/postgres.dump"
UPLOADS_ARCHIVE="${BACKUP_PATH}/files/uploads-faces.tar.gz"

if [[ ! -f "${DB_DUMP}" && -f "${BACKUP_PATH}/postgres.dump" ]]; then
  DB_DUMP="${BACKUP_PATH}/postgres.dump"
fi

if [[ ! -f "${UPLOADS_ARCHIVE}" && -f "${BACKUP_PATH}/uploads-faces.tar.gz" ]]; then
  UPLOADS_ARCHIVE="${BACKUP_PATH}/uploads-faces.tar.gz"
fi

if [[ ! -f "${DB_DUMP}" ]]; then
  echo "Backup invalido: dump PostgreSQL nao encontrado em ${BACKUP_PATH}."
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

DB_NAME="${POSTGRES_DB:-access_control}"
DB_USER="${POSTGRES_USER:-access_user}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-access-control-prod}"

compose() {
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

wait_for_postgres() {
  local attempts=30
  until compose exec -T postgres pg_isready -U "${DB_USER}" -d postgres >/dev/null 2>&1; do
    attempts=$((attempts - 1))
    if [[ "${attempts}" -le 0 ]]; then
      echo "PostgreSQL nao ficou pronto para restore."
      exit 1
    fi
    sleep 2
  done
}

echo "Restore solicitado a partir de: ${BACKUP_PATH}"
echo "Dump PostgreSQL: ${DB_DUMP}"
if [[ -f "${UPLOADS_ARCHIVE}" ]]; then
  echo "Uploads/faces: ${UPLOADS_ARCHIVE}"
else
  echo "Uploads/faces: arquivo nao encontrado; somente o banco sera restaurado."
fi
echo
echo "IMPORTANTE: backend, frontend e nginx serao parados durante a restauracao."
echo "O banco ${DB_NAME} sera sobrescrito."
read -r -p "Digite RESTAURAR ${DB_NAME} para continuar: " CONFIRM
if [[ "${CONFIRM}" != "RESTAURAR ${DB_NAME}" ]]; then
  echo "Restauracao cancelada."
  exit 1
fi

compose stop backend frontend nginx >/dev/null || true
compose up -d postgres >/dev/null
wait_for_postgres

compose exec -T postgres dropdb -U "${DB_USER}" --if-exists "${DB_NAME}"
compose exec -T postgres createdb -U "${DB_USER}" "${DB_NAME}"
compose exec -T postgres pg_restore -U "${DB_USER}" -d "${DB_NAME}" --no-owner --no-acl < "${DB_DUMP}"

if [[ -f "${UPLOADS_ARCHIVE}" ]]; then
  UPLOADS_ARCHIVE_DIR="$(dirname "${UPLOADS_ARCHIVE}")"
  UPLOADS_ARCHIVE_FILE="$(basename "${UPLOADS_ARCHIVE}")"
  docker run --rm \
    -v "${PROJECT_NAME}_uploads_faces:/data" \
    -v "${UPLOADS_ARCHIVE_DIR}:/backup:ro" \
    alpine:3.20 sh -c "find /data -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar -xzf /backup/${UPLOADS_ARCHIVE_FILE} -C /data"
fi

compose up -d

echo "Restauracao concluida."
echo "Valide a aplicacao e os eventos antes de liberar a operacao."
