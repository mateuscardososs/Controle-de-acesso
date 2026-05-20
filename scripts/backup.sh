#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.production"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.prod.yml"
BACKUP_DIR="${ROOT_DIR}/backups"
STAMP="$(date +%Y%m%d-%H%M%S)"
DEST_DIR="${BACKUP_DIR}/${STAMP}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Arquivo .env.production nao encontrado."
  exit 1
fi

set -a
source "${ENV_FILE}"
set +a

mkdir -p "${DEST_DIR}"

docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" exec -T postgres \
  pg_dump -U "${POSTGRES_USER:-access_user}" -d "${POSTGRES_DB:-access_control}" \
  --format=custom --no-owner --no-acl > "${DEST_DIR}/postgres.dump"

docker run --rm \
  -v "${COMPOSE_PROJECT_NAME:-access-control-prod}_uploads_faces:/data:ro" \
  -v "${DEST_DIR}:/backup" \
  alpine:3.20 sh -c "cd /data && tar -czf /backup/uploads-faces.tar.gz ."

docker run --rm \
  -v "${COMPOSE_PROJECT_NAME:-access-control-prod}_backend_logs:/backend-logs:ro" \
  -v "${COMPOSE_PROJECT_NAME:-access-control-prod}_nginx_logs:/nginx-logs:ro" \
  -v "${DEST_DIR}:/backup" \
  alpine:3.20 sh -c "tar -czf /backup/logs.tar.gz -C / backend-logs nginx-logs"

cat > "${DEST_DIR}/README.txt" <<EOF
Backup gerado em ${STAMP}

Conteudo:
- postgres.dump
- uploads-faces.tar.gz
- logs.tar.gz

Restauracao:
./scripts/restore.sh "${DEST_DIR}"
EOF

echo "Backup criado em ${DEST_DIR}"
