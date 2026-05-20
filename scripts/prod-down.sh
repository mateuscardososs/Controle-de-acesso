#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.production"
COMPOSE_FILE="${ROOT_DIR}/docker-compose.prod.yml"

if [[ -f "${ENV_FILE}" ]]; then
  docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" down "$@"
else
  docker compose -f "${COMPOSE_FILE}" down "$@"
fi
