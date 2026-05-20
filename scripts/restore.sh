#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.production"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.prod.yml"
BACKUP_PATH="${1:-}"

if [[ -z "${BACKUP_PATH}" ]]; then
  echo "Uso: ./scripts/restore.sh backups/YYYYmmdd-HHMMSS"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Arquivo .env.production nao encontrado."
  exit 1
fi

BACKUP_PATH="$(cd "${BACKUP_PATH}" && pwd)"

if [[ ! -f "${BACKUP_PATH}/postgres.dump" ]]; then
  echo "Backup invalido: postgres.dump nao encontrado em ${BACKUP_PATH}."
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

echo "Isto vai restaurar o banco e os uploads a partir de: ${BACKUP_PATH}"
echo "Servicos de aplicacao serao parados durante a restauracao."
read -r -p "Digite RESTAURAR para continuar: " CONFIRM
if [[ "${CONFIRM}" != "RESTAURAR" ]]; then
  echo "Restauracao cancelada."
  exit 1
fi

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" stop backend frontend nginx
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d postgres

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T postgres \
  dropdb -U "${POSTGRES_USER:-access_user}" --if-exists "${POSTGRES_DB:-access_control}"
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T postgres \
  createdb -U "${POSTGRES_USER:-access_user}" "${POSTGRES_DB:-access_control}"
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T postgres \
  pg_restore -U "${POSTGRES_USER:-access_user}" -d "${POSTGRES_DB:-access_control}" --no-owner --no-acl < "${BACKUP_PATH}/postgres.dump"

if [[ -f "${BACKUP_PATH}/uploads-faces.tar.gz" ]]; then
  docker run --rm \
    -v "${COMPOSE_PROJECT_NAME:-access-control-prod}_uploads_faces:/data" \
    -v "${BACKUP_PATH}:/backup:ro" \
    alpine:3.20 sh -c "rm -rf /data/* && tar -xzf /backup/uploads-faces.tar.gz -C /data"
fi

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d

echo "Restauracao concluida."
