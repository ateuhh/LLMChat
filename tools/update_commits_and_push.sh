#!/usr/bin/env sh
set -eu

# ===== Настройки (можно переопределять через ENV) =====
# Автор для фильтрации коммитов: сначала email, иначе name из git config.
AUTHOR="${AUTHOR:-$(git config user.email 2>/dev/null || echo "")}"
[ -n "$AUTHOR" ] || AUTHOR="$(git config user.name 2>/dev/null || echo "")"

# Сколько коммитов показывать
LIMIT="${LIMIT:-50}"

# Ветка: по умолчанию текущая
BRANCH="${BRANCH:-HEAD}"

# С какого времени брать коммиты (опционально), пример: SINCE="2025-01-01"
SINCE_ARG=""
[ -n "${SINCE:-}" ] && SINCE_ARG="--since=${SINCE}"

# ===== Подготовка =====
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

README="README.md"
SECTION_TITLE="## Недавние коммиты"
START_MARK="<!-- MY_COMMITS_START -->"
END_MARK="<!-- MY_COMMITS_END -->"

# Временный файл для списка (кроссплатформенный mktemp)
LIST_FILE="$(mktemp 2>/dev/null || mktemp -t commits)"

# ===== Сбор коммитов =====
# Формат строки: "- YYYY-MM-DD — сообщение (sha)"
if [ -n "$AUTHOR" ]; then
  git log "$BRANCH" --author="$AUTHOR" --no-merges \
    --pretty=format:'- %ad — %s (%h)' --date=short -n "$LIMIT" $SINCE_ARG > "$LIST_FILE" || true
else
  git log "$BRANCH" --no-merges \
    --pretty=format:'- %ad — %s (%h)' --date=short -n "$LIMIT" $SINCE_ARG > "$LIST_FILE" || true
fi

# Если ничего не нашли — добавим заглушку
if [ ! -s "$LIST_FILE" ]; then
  printf "%s\n" "— нет коммитов по заданным критериям —" > "$LIST_FILE"
fi

# ===== Убедимся, что README существует =====
if [ ! -f "$README" ]; then
  printf "# %s\n\n" "$(basename "$ROOT")" > "$README"
fi

# ===== Вставка/обновление секции =====
if grep -q "$START_MARK" "$README" 2>/dev/null && grep -q "$END_MARK" "$README" 2>/dev/null; then
  # Обновляем содержимое между маркерами
  awk -v start="$START_MARK" -v end="$END_MARK" -v list="$LIST_FILE" '
    BEGIN { inblock=0 }
    $0==start {
      print $0
      while ((getline line < list) > 0) print line
      close(list)
      inblock=1
      next
    }
    $0==end { print $0; inblock=0; next }
    inblock!=1 { print }
  ' "$README" > "${README}.new" && mv "${README}.new" "$README"
else
  # Добавляем секцию в конец файла
  {
    printf "\n%s\n\n" "$SECTION_TITLE"
    printf "%s\n" "$START_MARK"
    cat "$LIST_FILE"
    printf "%s\n" "$END_MARK"
  } >> "$README"
fi

rm -f "$LIST_FILE"

# ===== Коммит и пуш =====
git add -A
git commit -m "docs: update README with recent commits" || true
git push origin HEAD
