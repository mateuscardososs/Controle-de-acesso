#!/bin/bash
set -euo pipefail

BASE_URL="http://localhost:80"
ENV_FILE=".env.production"
COMPOSE_FILE="docker-compose.prod.yml"

ADMIN_EMAIL=$(grep APP_SEED_ADMIN_EMAIL "$ENV_FILE" | cut -d= -f2)
ADMIN_PASS=$(grep  APP_SEED_ADMIN_PASSWORD "$ENV_FILE" | cut -d= -f2)

TOKEN=$(curl -sf -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASS\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

echo "✓ Autenticado"

GUEST_IDS=$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  exec -T postgres \
  psql -U access_user -d access_control -t -A -c "
    SELECT id
    FROM   guests
    WHERE  invited_lounge IN ('Institucional 1', 'Institucional Vereadores')
      AND  status = 'COMPLETED'
      AND  sync_status != 'SYNCING';
  ")

TOTAL=$(echo "$GUEST_IDS" | grep -c '[a-z0-9-]' || true)
echo "✓ ${TOTAL} guests encontrados para resync"

if [ "$TOTAL" -eq 0 ]; then
  echo "Nenhum guest elegível. Saindo."
  exit 0
fi

QUEUED=0
FAILED=0
for ID in $GUEST_IDS; do
  RESPONSE=$(curl -sf -X POST "$BASE_URL/api/integration/retry/guest/$ID" \
    -H "Authorization: Bearer $TOKEN" 2>&1) && {
    echo "  → $ID: queued"
    QUEUED=$((QUEUED + 1))
  } || {
    echo "  ✗ $ID: FALHOU ($RESPONSE)"
    FAILED=$((FAILED + 1))
  }
done

echo ""
echo "══════════════════════════════"
echo "  Enfileirados : $QUEUED"
echo "  Falhas       : $FAILED"
echo "══════════════════════════════"
