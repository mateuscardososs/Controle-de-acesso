#!/bin/bash
# Verifica e corrige o estado do banco para o fluxo de check-in público (/invite)
# Uso: ./check-db.sh

set -euo pipefail

ENV_FILE=".env.production"
COMPOSE_FILE="docker-compose.prod.yml"
SERVICE="postgres"

# Lê credenciais do .env.production ou usa defaults do docker-compose
if [ -f "$ENV_FILE" ]; then
  POSTGRES_USER=$(grep -E '^POSTGRES_USER=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || echo "access_user")
  POSTGRES_DB=$(grep -E '^POSTGRES_DB=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || echo "access_control")
else
  POSTGRES_USER="access_user"
  POSTGRES_DB="access_control"
fi

# Wrapper para executar SQL via docker compose exec
psql() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
    exec -T "$SERVICE" \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    --pset="border=2" --pset="linestyle=unicode" \
    -c "$1"
}

# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  VERIFICAÇÃO GERAL — tabela guests"
echo "══════════════════════════════════════════════════════════════"
# O que verifica:
#   prontos_para_checkin        → guests importados aguardando foto (fluxo /invite ok)
#   pendente_com_foto_orfao     → PENDING_REGISTRATION mas já tem foto — estado inconsistente
#   sincronizados               → concluíram o fluxo e foram para o sistema de acesso
#   cadastrados_nao_sincronizados → foto registrada mas sync falhou (ação requerida)
#   sem_camarote                → pré-cadastros sem invited_lounge — badge vazio na tela 2
#   cpf_invalido                → CPFs com dígito incorreto — validate-cpf nunca encontrará
psql "
SELECT
  COUNT(*) FILTER (WHERE status = 'PENDING_REGISTRATION' AND face_photo_url IS NULL)
    AS prontos_para_checkin,
  COUNT(*) FILTER (WHERE status = 'PENDING_REGISTRATION' AND face_photo_url IS NOT NULL)
    AS pendente_com_foto_orfao,
  COUNT(*) FILTER (WHERE status = 'COMPLETED' AND sync_status = 'SYNCED')
    AS sincronizados,
  COUNT(*) FILTER (WHERE status = 'COMPLETED' AND sync_status = 'SYNC_FAILED')
    AS cadastrados_nao_sincronizados,
  COUNT(*) FILTER (WHERE invited_lounge IS NULL AND status = 'PENDING_REGISTRATION')
    AS sem_camarote,
  COUNT(*) FILTER (
    WHERE cpf IS NULL
       OR LENGTH(REPLACE(REPLACE(cpf, '.', ''), '-', '')) != 11
  ) AS cpf_invalido
FROM guests;
"

# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  DISTRIBUIÇÃO POR STATUS / SYNC_STATUS"
echo "══════════════════════════════════════════════════════════════"
# Visão geral de quantos guests existem em cada combinação de status.
# Útil para confirmar que a importação via planilha chegou ao banco.
psql "
SELECT
  status,
  sync_status,
  COUNT(*)                                                  AS total,
  COUNT(*) FILTER (WHERE face_photo_url IS NOT NULL)        AS com_foto,
  COUNT(*) FILTER (WHERE invited_lounge IS NOT NULL)        AS com_camarote
FROM guests
GROUP BY status, sync_status
ORDER BY status, sync_status;
"

# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  GUESTS SEM ÁREAS VINCULADAS (guest_allowed_areas)"
echo "══════════════════════════════════════════════════════════════"
# O que verifica:
#   Guests PENDING_REGISTRATION sem nenhuma área em guest_allowed_areas.
#   Após tirar a foto, GuestService tenta sincronizar as áreas — se não houver
#   nenhuma, o sync vai falhar ou criar um acesso sem restrição de área.
#   Causa mais comum: invited_lounge não tem correspondência em AccessArea.
psql "
SELECT
  g.id,
  g.full_name,
  g.cpf,
  g.invited_lounge,
  COUNT(ga.area_id) AS areas_vinculadas
FROM guests g
LEFT JOIN guest_allowed_areas ga ON ga.guest_id = g.id
WHERE g.status = 'PENDING_REGISTRATION'
GROUP BY g.id, g.full_name, g.cpf, g.invited_lounge
HAVING COUNT(ga.area_id) = 0
ORDER BY g.full_name;
"

# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════════════"
echo "  CORREÇÃO DE DATAS (visit_start / visit_end)"
echo "══════════════════════════════════════════════════════════════"
echo "  Guests PENDING_REGISTRATION com visit_start ou visit_end NULL:"
psql "
SELECT id, full_name, cpf, visit_start, visit_end
FROM guests
WHERE status = 'PENDING_REGISTRATION'
  AND (visit_start IS NULL OR visit_end IS NULL)
ORDER BY full_name;
"

echo ""
echo "  ► Deseja corrigir datas nulas? (visit_start = NOW, visit_end = +7 dias)"
echo "    Isso é seguro — COALESCE só preenche onde está NULL. [s/N]"
read -r confirm
if [ "${confirm:-N}" = "s" ] || [ "${confirm:-N}" = "S" ]; then
  psql "
  UPDATE guests
  SET
    visit_start = COALESCE(visit_start, NOW()),
    visit_end   = COALESCE(visit_end,   NOW() + INTERVAL '7 days')
  WHERE status = 'PENDING_REGISTRATION'
    AND (visit_start IS NULL OR visit_end IS NULL);
  "
  echo ""
  echo "  Correção aplicada. Distribuição atualizada:"
  psql "
  SELECT status, sync_status, COUNT(*),
         COUNT(*) FILTER (WHERE face_photo_url IS NOT NULL) AS com_foto,
         COUNT(*) FILTER (WHERE invited_lounge IS NOT NULL) AS com_camarote
  FROM guests
  GROUP BY status, sync_status
  ORDER BY status, sync_status;
  "
else
  echo "  Correção pulada."
fi

echo ""
echo "Concluído."
