#!/usr/bin/env sh
set -e

# Печать инфо
echo "TZ=${TZ:-unset}"
echo "REPO_ROOT=${REPO_ROOT:-/workspace/LLMChat}"
echo "CRON_SCHEDULE=${CRON_SCHEDULE:-0 12 * * *}"
echo "DEST=${DEST:-unset} (telegram|slack)"
[ -n "$TELEGRAM_BOT_TOKEN" ] && echo "TELEGRAM_BOT_TOKEN is set"
[ -n "$SLACK_WEBHOOK_URL" ] && echo "SLACK_WEBHOOK_URL is set"

# Проверим, смонтирован ли репозиторий
if [ ! -d "${REPO_ROOT}/.git" ]; then
  echo "⚠️  Внимание: ${REPO_ROOT} не выглядит как git-репозиторий. Убедитесь, что том смонтирован правильно." >&2
fi

# Сформируем crontab динамически (чтобы можно было менять время без ребилда)
# Alpine читает /etc/crontabs/root
cat >/etc/crontabs/root <<EOF
SHELL=/bin/sh
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
${CRON_SCHEDULE} cd ${REPO_ROOT} && sh tools/daily_commits_report.sh >> /var/log/cron/daily_commits.log 2>&1
EOF

echo "Установленный crontab:"
crontab -l || true

# Если задан APP_COMMAND — запускаем ваше приложение в фоне
if [ -n "$APP_COMMAND" ]; then
  echo "Запускаю ваше приложение: $APP_COMMAND"
  # shellcheck disable=SC2086
  sh -lc "$APP_COMMAND" &
fi

# Запускаем cron на переднем плане, чтобы контейнер не завершался
echo "Старт cron…"
exec crond -f -l 8 -d 8
