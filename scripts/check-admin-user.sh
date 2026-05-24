#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.production"

POSTGRES_USER="access_user"
POSTGRES_DB="access_control"
CONTAINER="consuma_catraca-postgres-1"

if [ -f "${ENV_FILE}" ]; then
  POSTGRES_USER="$(grep -E '^POSTGRES_USER=' "${ENV_FILE}" | cut -d'=' -f2- | tr -d '"' | tr -d "'")"
  POSTGRES_DB="$(grep -E '^POSTGRES_DB=' "${ENV_FILE}" | cut -d'=' -f2- | tr -d '"' | tr -d "'")"
  POSTGRES_USER="${POSTGRES_USER:-access_user}"
  POSTGRES_DB="${POSTGRES_DB:-access_control}"
fi

if ! docker inspect --format '{{.State.Running}}' "${CONTAINER}" 2>/dev/null | grep -q "true"; then
  echo "ERRO: container '${CONTAINER}' nao encontrado ou nao esta rodando."
  exit 1
fi

echo "Usuarios com role ADMIN no banco '${POSTGRES_DB}':"
echo

RESULT=$(docker exec -i "${CONTAINER}" psql -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -t -c \
  "SELECT email, role, active FROM users WHERE role = 'ADMIN' ORDER BY email;" 2>&1)

if [ -z "$(echo "${RESULT}" | tr -d ' \n')" ]; then
  echo "ATENCAO: nenhum usuario ADMIN encontrado no banco."
  echo "Para criar o primeiro admin, habilite temporariamente APP_SEED_ADMIN_ENABLED=true"
  echo "com APP_SEED_ADMIN_EMAIL, APP_SEED_ADMIN_NAME e APP_SEED_ADMIN_PASSWORD no .env.production,"
  echo "reinicie o backend, confirme o login, depois desabilite e reinicie novamente."
  exit 1
fi

echo "${RESULT}"
echo
echo "Total de admins encontrados: $(echo "${RESULT}" | grep -c '|' || echo 0)"
