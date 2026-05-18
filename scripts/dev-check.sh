#!/usr/bin/env bash
set -u

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

status=0

check_cmd() {
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "OK   $label"
  else
    echo "FAIL $label"
    status=1
  fi
}

check_port() {
  local label="$1"
  local port="$2"
  if nc -z localhost "$port" >/dev/null 2>&1; then
    echo "OK   $label na porta $port"
  else
    echo "WARN $label nao responde na porta $port"
  fi
}

check_cmd "Docker CLI" command -v docker
check_cmd "Docker daemon" docker info
check_cmd "Docker Compose" docker compose version

echo
echo "Containers Docker Compose:"
if docker info >/dev/null 2>&1; then
  docker compose ps
else
  echo "Docker daemon indisponivel; nao foi possivel listar containers."
fi

echo
if docker info >/dev/null 2>&1; then
  if docker compose ps --status running postgres 2>/dev/null | grep -q "access-control-postgres"; then
    echo "OK   PostgreSQL container rodando"
  else
    echo "WARN PostgreSQL container nao parece estar rodando"
  fi
fi

check_port "PostgreSQL" 5432
if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:5432 -sTCP:LISTEN 2>/dev/null | grep -v -E 'COMMAND|com\\.docke|docker' >/dev/null; then
  echo "WARN Processo local nao-Docker tambem escuta na porta 5432; o backend pode conectar no PostgreSQL errado"
fi
check_port "Backend API" 8080
check_port "Frontend Next.js" 3000
check_port "RabbitMQ Management" 15672
check_port "Prometheus" 9090
check_port "pgAdmin opcional" 5050

echo
if [[ "$status" -eq 0 ]]; then
  echo "Checklist basico concluido. WARN indica servico opcional/parado, nao necessariamente erro."
else
  echo "Ha falhas de ambiente. Abra o Docker Desktop e rode: ./scripts/dev-up.sh"
fi

exit "$status"
