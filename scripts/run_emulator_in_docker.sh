#!/usr/bin/env bash
set -euo pipefail

IMAGE="${IMAGE:-llmchat-android-emu}"

# Params for entrypoint
export APP_MODULE="${APP_MODULE:-app}"
export GRADLE_TASK="${GRADLE_TASK:-assembleDebug}"
export ADB_HOST="${ADB_HOST:-host.docker.internal}"
export ADB_PORT="${ADB_PORT:-5037}"

# Device / app
export TARGET_SERIAL="${TARGET_SERIAL:-${ANDROID_SERIAL:-}}"
export APP_ID_OVERRIDE="${APP_ID_OVERRIDE:-com.example.llmchat}"
export COMPONENT_OVERRIDE="${COMPONENT_OVERRIDE:-}"   # optional
export SKIP_AAPT2_OVERRIDE="${SKIP_AAPT2_OVERRIDE:-1}"
export DEBUG="${DEBUG:-1}"
export QUIET_GOOGLE="${QUIET_GOOGLE:-1}"

echo "Image name:   $IMAGE"
echo "Module:       $APP_MODULE"
echo "Gradle task:  $GRADLE_TASK"
echo "ADB host:     ${ADB_HOST}:${ADB_PORT}"
[[ -n "${TARGET_SERIAL}" ]] && echo "Target serial: ${TARGET_SERIAL}"
echo "App ID:       ${APP_ID_OVERRIDE}"
[[ -n "${COMPONENT_OVERRIDE}" ]] && echo "Component:    ${COMPONENT_OVERRIDE}"

# Ensure image exists
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Docker image '$IMAGE' not found."
  echo "Build it with:"
  echo "  docker build -t $IMAGE -f docker/android/Dockerfile ."
  exit 1
fi

DOCKER_ARGS=( --rm -it -v "$PWD:/workspace" --name android-emu )
if [[ "$(uname -s)" == "Linux" ]]; then
  DOCKER_ARGS+=( --add-host=host.docker.internal:host-gateway )
fi

ENV_VARS=(
  -e APP_MODULE -e GRADLE_TASK -e APK_PATH
  -e ADB_HOST -e ADB_PORT
  -e TARGET_SERIAL -e ANDROID_SERIAL
  -e APP_ID_OVERRIDE -e COMPONENT_OVERRIDE
  -e SKIP_AAPT2_OVERRIDE -e DEBUG -e QUIET_GOOGLE
  -e AAPT2_PIN -e PRESERVE_AAPT2_CACHE
)

exec docker run "${DOCKER_ARGS[@]}" "${ENV_VARS[@]}" "$IMAGE"
