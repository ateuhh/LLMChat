# Dockerfile
FROM alpine:3.20

# Пакеты: git для логов, curl для отправки, tzdata для TZ, cronie для cron, bash опционально
RUN apk add --no-cache bash git curl tzdata cronie

# Таймзона (можно переопределить через ENV/compose)
ENV TZ=America/Denver
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Логи cron
RUN mkdir -p /var/log/cron

# Рабочая папка (в неё смонтируем ваш репо)
WORKDIR /workspace

# Стартовый скрипт
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# По умолчанию не запускаем ничего, всё идёт через entrypoint
ENV APP_COMMAND=""
ENV REPO_ROOT="/workspace/LLMChat"
ENV CRON_SCHEDULE="0 12 * * *"
# каждый день в 12:00

ENTRYPOINT ["/entrypoint.sh"]
