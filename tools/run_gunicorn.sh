#!/usr/bin/env zsh
set -e

cd /Users/mikhailboldyrev/AndroidStudioProjects/LLMChat

source tools/.env.local

source .venv/bin/activate

PYTHONPATH=. gunicorn \
  -w 2 -b 127.0.0.1:8766 tools.dev_chart_server:app \
  --access-logfile - \
  --error-logfile  -
