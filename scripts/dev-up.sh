#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI nao encontrado. Instale/abra o Docker Desktop e tente novamente."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon indisponivel. Abra o Docker Desktop e aguarde inicializar."
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose v2 indisponivel. Atualize o Docker Desktop."
  exit 1
fi

if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:5432 -sTCP:LISTEN 2>/dev/null | grep -v -E 'COMMAND|com\\.docke|docker' >/dev/null; then
  echo "Aviso: ha um processo local nao-Docker escutando na porta 5432."
  echo "Isso pode fazer o Spring conectar no PostgreSQL errado e falhar com role \"access_user\" inexistente."
  echo "Pare o PostgreSQL local antes de rodar o backend, ou libere a porta 5432 para o Docker Compose."
  echo
fi

echo "Subindo infraestrutura com docker compose..."
docker compose up -d

echo "Aguardando PostgreSQL responder..."
for attempt in {1..30}; do
  if docker compose exec -T postgres pg_isready -U access_user -d access_control >/dev/null 2>&1; then
    echo "PostgreSQL pronto."
    break
  fi

  if [[ "$attempt" == "30" ]]; then
    echo "PostgreSQL nao respondeu a tempo. Veja logs com: docker compose logs postgres"
    exit 1
  fi

  sleep 2
done

echo
echo "Containers:"
docker compose ps

echo
echo "Proximos comandos:"
echo "  Backend:  ./mvnw spring-boot:run"
echo "  Frontend: cd frontend && npm run dev"
echo
echo "URLs:"
echo "  API health: http://localhost:8080/api/health"
echo "  Frontend:   http://localhost:3000"
echo "  RabbitMQ:   http://localhost:15672"
echo "  Prometheus: http://localhost:9090"
