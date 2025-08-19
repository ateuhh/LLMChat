#!/usr/bin/env sh
set -eu

# ============ НАСТРОЙКИ ============
# Куда отправлять: telegram | slack (по умолчанию: telegram, если TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT_ID заданы)
DEST="${DEST:-}"

# Telegram vars (обязательны, если DEST=telegram)
TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_CHAT_ID="${TELEGRAM_CHAT_ID:-}"

# Slack vars (обязательны, если DEST=slack)
SLACK_WEBHOOK_URL="${SLACK_WEBHOOK_URL:-}"

# Фильтр по автору (опционально). Если пусто — все коммиты проекта.
AUTHOR="${AUTHOR:-}"

# Ветка (по умолчанию текущая)
BRANCH="${BRANCH:-HEAD}"

# Окно времени (последние 24 часа)
SINCE="${SINCE:-24 hours ago}"
UNTIL="${UNTIL:-now}"

# Лимит (страховочный, на случай бурной активности)
LIMIT="${LIMIT:-200}"

# Включать мердж-коммиты? (0 = нет, 1 = да)
INCLUDE_MERGES="${INCLUDE_MERGES:-0}"

# Корень репозитория
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# Имя репозитория и ветка
REPO_NAME="$(basename "$ROOT")"
CURR_BRANCH="$(git rev-parse --abbrev-ref HEAD || echo "$BRANCH")"

# Определяем DEST по наличию токенов, если не задан
if [ -z "$DEST" ]; then
  if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ]; then
    DEST="telegram"
  elif [ -n "$SLACK_WEBHOOK_URL" ]; then
    DEST="slack"
  else
    echo "ERROR: Не задан канал отправки. Укажите DEST=telegram (и TELEGRAM_*) или DEST=slack (и SLACK_WEBHOOK_URL)." >&2
    exit 1
  fi
fi

# ============ СБОР КОММИТОВ ============
LIST_FILE="$(mktemp 2>/dev/null || mktemp -t commits)"
NO_MERGES_OPT="--no-merges"
[ "$INCLUDE_MERGES" = "1" ] && NO_MERGES_OPT=""

LOG_CMD="git log $BRANCH $NO_MERGES_OPT --since=\"$SINCE\" --until=\"$UNTIL\" --date=iso-local --pretty=format:-\ %ad\ —\ %an:\ %s\ \(%h\) -n $LIMIT"
if [ -n "$AUTHOR" ]; then
  LOG_CMD="git log $BRANCH $NO_MERGES_OPT --author=\"$AUTHOR\" --since=\"$SINCE\" --until=\"$UNTIL\" --date=iso-local --pretty=format:-\ %ad\ —\ %an:\ %s\ \(%h\) -n $LIMIT"
fi

# Выполняем и пишем в файл
# shellcheck disable=SC2086
sh -c "$LOG_CMD" > "$LIST_FILE" || true

if [ ! -s "$LIST_FILE" ]; then
  echo "— за последние 24 часа коммитов не найдено —" > "$LIST_FILE"
fi

# Заголовок отчёта
NOW_HUMAN="$(date)"
HEADER="Отчёт по коммитам за 24 часа
Проект: $REPO_NAME
Ветка:  $CURR_BRANCH
Окно:   $SINCE → $UNTIL (локальное время)
Сформировано: $NOW_HUMAN

Коммиты:"

# Полный текст сообщения (без форматирования Markdown, чтобы не экранировать)
MSG_FILE="$(mktemp 2>/dev/null || mktemp -t msg)"
{
  printf "%s\n\n" "$HEADER"
  cat "$LIST_FILE"
} > "$MSG_FILE"

# ============ ОТПРАВКА ============
send_telegram() {
  if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
    echo "ERROR: TELEGRAM_BOT_TOKEN и/или TELEGRAM_CHAT_ID не заданы." >&2
    exit 1
  fi

  # Telegram ограничивает сообщение ~4096 символами.
  # Разобьём по строкам на чанки ~3900 символов.
  MAX=3900
  buffer=""
  buflen=0

  # Пройдёмся по строкам исходного файла
  while IFS= read -r line || [ -n "$line" ]; do
    # +1 за перевод строки
    line_len=${#line}
    newlen=$(( buflen + line_len + (buflen>0 ? 1 : 0) ))
    if [ "$newlen" -gt "$MAX" ]; then
      # отправляем буфер
      curl -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
        -d "chat_id=${TELEGRAM_CHAT_ID}" \
        --data-urlencode "text=${buffer}" >/dev/null
      buffer="$line"
      buflen=${#line}
    else
      if [ "$buflen" -gt 0 ]; then
        buffer="${buffer}
${line}"
        buflen=$(( buflen + line_len + 1 ))
      else
        buffer="${line}"
        buflen=$line_len
      fi
    fi
  done < "$MSG_FILE"

  # Отправим остаток
  if [ "$buflen" -gt 0 ]; then
    curl -sS -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage" \
      -d "chat_id=${TELEGRAM_CHAT_ID}" \
      --data-urlencode "text=${buffer}" >/dev/null
  fi
}

json_escape() {
  # Экранируем для JSON: \, ", и переводим \n в литерал \n
  # shellcheck disable=SC2001
  sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e ':a;N;$!ba;s/\n/\\n/g'
}

send_slack() {
  if [ -z "$SLACK_WEBHOOK_URL" ]; then
    echo "ERROR: SLACK_WEBHOOK_URL не задан." >&2
    exit 1
  fi
  PAYLOAD_TEXT="$(cat "$MSG_FILE" | json_escape)"
  curl -sS -X POST -H 'Content-type: application/json' \
    --data "{\"text\":\"${PAYLOAD_TEXT}\"}" \
    "$SLACK_WEBHOOK_URL" >/dev/null
}

case "$DEST" in
  telegram) send_telegram ;;
  slack)    send_slack ;;
  *) echo "ERROR: неизвестный DEST='$DEST'." >&2; exit 1 ;;
esac

rm -f "$LIST_FILE" "$MSG_FILE"
