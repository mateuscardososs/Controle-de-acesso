#!/usr/bin/env bash
set -euo pipefail

CONTAINER="consuma_catraca-nginx-1"

if ! docker inspect --format '{{.State.Running}}' "${CONTAINER}" 2>/dev/null | grep -q "true"; then
  echo "ERRO: container '${CONTAINER}' nao encontrado ou nao esta rodando."
  echo "Verifique com: docker compose -f docker-compose.prod.yml ps"
  exit 1
fi

echo "Recarregando configuracao do Nginx em '${CONTAINER}'..."
if docker exec "${CONTAINER}" nginx -s reload; then
  echo "Nginx recarregado com sucesso."
  echo "Se havia 502 por cache de DNS apos restart do backend, aguarde 5 segundos e teste novamente."
else
  echo "ERRO: falha ao recarregar o Nginx. Verifique com:"
  echo "  docker logs ${CONTAINER} --tail=50"
  exit 1
fi
