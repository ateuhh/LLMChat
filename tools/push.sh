#!/usr/bin/env bash
set -e
git add -A
msg=${1:-"chore: updates from Android Studio"}
git commit -m "$msg" || true   # если нечего коммитить — не падать
git push origin HEAD
