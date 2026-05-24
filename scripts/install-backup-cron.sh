#!/usr/bin/env bash
# Instala o cron de backup horario do consuma_catraca no usuario atual.
#
# Em Mac (desenvolvimento/producao local):
#   Execute como o usuario que inicia Docker Desktop.
#   O cron roda no espaco do usuario via launchd/cron nativo do macOS.
#
# Em servidor Linux (producao):
#   Execute como o mesmo usuario que executa `docker compose` em producao.
#   Normalmente: sudo -u deployer ./scripts/install-backup-cron.sh
#   O usuario precisa ter permissao para escrever no diretorio do projeto
#   e para executar comandos Docker sem sudo (grupo docker).
#
# Verificar apos instalar:
#   crontab -l
#   tail -f backups/backup-cron.log
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARKER="# consuma_catraca hourly backup"
ENTRY="0 * * * * cd ${ROOT_DIR} && ./scripts/backup.sh hourly >> ${ROOT_DIR}/backups/backup-cron.log 2>&1  ${MARKER}"

mkdir -p "${ROOT_DIR}/backups"
touch "${ROOT_DIR}/backups/backup-cron.log"

CURRENT_CRON="$(crontab -l 2>/dev/null || true)"

if printf '%s\n' "${CURRENT_CRON}" | grep -Fq "${MARKER}"; then
  echo "Cron de backup ja instalado para este projeto em: ${ROOT_DIR}"
else
  {
    if [ -n "${CURRENT_CRON}" ]; then
      printf '%s\n' "${CURRENT_CRON}"
    fi
    printf '%s\n' "${ENTRY}"
  } | crontab -
  echo "Cron de backup instalado para: ${ROOT_DIR}"
  echo "Horario: todo inicio de hora (0 * * * *)"
fi

echo
echo "Crontab atual:"
crontab -l
echo
echo "Log de backup: ${ROOT_DIR}/backups/backup-cron.log"
echo "Para verificar o ultimo sucesso: cat ${ROOT_DIR}/backups/latest-success.txt"
