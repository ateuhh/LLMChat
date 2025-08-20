#!/usr/bin/env bash
set -euo pipefail

# =========================
# Config (override via env)
# =========================
APP_MODULE="${APP_MODULE:-app}"
GRADLE_TASK="${GRADLE_TASK:-assembleDebug}"
APK_PATH="${APK_PATH:-}"

# Host ADB (no adb connect)
ADB_HOST="${ADB_HOST:-host.docker.internal}"
ADB_PORT="${ADB_PORT:-5037}"

# Device selection / debug / behaviours
TARGET_SERIAL="${TARGET_SERIAL:-}"           # e.g. emulator-5554
SKIP_AAPT2_OVERRIDE="${SKIP_AAPT2_OVERRIDE:-0}"
DEBUG="${DEBUG:-0}"
QUIET_GOOGLE="${QUIET_GOOGLE:-1}"

# Overrides to mirror manual run
APP_ID_OVERRIDE="${APP_ID_OVERRIDE:-}"       # e.g. com.example.llmchat
COMPONENT_OVERRIDE="${COMPONENT_OVERRIDE:-}" # e.g. com.example.llmchat/.MainActivity

# AAPT2 pinning & cache behaviour
AAPT2_PIN="${AAPT2_PIN:-/opt/android-sdk/build-tools/34.0.0/aapt2}"
PRESERVE_AAPT2_CACHE="${PRESERVE_AAPT2_CACHE:-1}"

# Android SDK inside container
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"

# Tools (we'll add -s SERIAL later)
ADB_BASE="/usr/bin/adb -H ${ADB_HOST} -P ${ADB_PORT}"
ADB_CLI="$ADB_BASE"
SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
APK_ANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"

[[ "$DEBUG" == "1" ]] && set -x
log() { echo -e "\033[1;34m[entrypoint]\033[0m $*"; }

# =========================
# Helpers
# =========================
ensure_local_properties_sdkdir() {
  local props="local.properties"
  if [[ -f "$props" ]]; then
    if grep -q '^sdk.dir=' "$props"; then
      sed -i.bak 's|^sdk.dir=.*$|sdk.dir=/opt/android-sdk|' "$props"
    else
      echo 'sdk.dir=/opt/android-sdk' >> "$props"
    fi
  else
    echo 'sdk.dir=/opt/android-sdk' > "$props"
  fi
  log "local.properties -> $(grep '^sdk.dir=' "$props" || true)"
}

wait_for_host_adb() {
  log "Waiting for host ADB at ${ADB_HOST}:${ADB_PORT}..."
  for _ in {1..30}; do
    if $ADB_BASE version >/dev/null 2>&1; then
      log "Host ADB is available."
      return 0
    fi
    sleep 1
  done
  log "ERROR: Host ADB server not reachable at ${ADB_HOST}:${ADB_PORT}."
  exit 1
}

pick_device() {
  local s
  s="$($ADB_BASE devices | awk '/^emulator-/{print $1; exit}')" || true
  if [[ -z "$s" ]]; then
    s="$($ADB_BASE devices | awk 'NR>1 && $2=="device"{print $1; exit}')" || true
  fi
  echo -n "$s"
}

maybe_disable_setup_wizard() {
  local serial="$1"
  local candidates=(
    com.google.android.setupwizard
    com.android.setupwizard
    com.android.provision
    com.android.provisioning
    com.google.android.apps.restore
  )
  for pkg in "${candidates[@]}"; do
    if $ADB_CLI -s "$serial" shell pm path "$pkg" >/dev/null 2>&1; then
      $ADB_CLI -s "$serial" shell pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
    fi
  done
}

quiet_google_overlays() {
  local serial="$1"
  [[ "$QUIET_GOOGLE" != "1" ]] && return 0
  local candidates=(
    com.google.android.googlequicksearchbox
    com.google.android.as
    com.google.android.settings.intelligence
    com.google.android.ondevicepersonalization
    com.google.android.katniss
    com.google.android.apps.gemini
  )
  for pkg in "${candidates[@]}"; do
    if $ADB_CLI -s "$serial" shell pm path "$pkg" >/dev/null 2>&1; then
      $ADB_CLI -s "$serial" shell pm disable-user --user 0 "$pkg" >/dev/null 2>&1 || true
    fi
  done
}

ensure_home_role() {
  local serial="$1"
  local current
  current="$($ADB_CLI -s "$serial" shell cmd role holders android.app.role.HOME 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$current" ]]; then return 0; fi
  local launchers=(com.google.android.apps.nexuslauncher com.android.launcher3)
  for l in "${launchers[@]}"; do
    if $ADB_CLI -s "$serial" shell pm path "$l" >/dev/null 2>&1; then
      $ADB_CLI -s "$serial" shell cmd role add-role-holder android.app.role.HOME "$l" >/dev/null 2>&1 || true
      break
    fi
  done
}

ensure_launcher_ready() {
  local serial="$1"
  for _ in {1..120}; do
    local s
    s="$($ADB_CLI -s "$serial" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')"
    [[ "$s" == "stopped" || -z "$s" ]] && break
    sleep 1
  done
  ensure_home_role "$serial"
  $ADB_CLI -s "$serial" shell cmd statusbar collapse >/dev/null 2>&1 || true
  $ADB_CLI -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  $ADB_CLI -s "$serial" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1 || true
  sleep 1
}

wait_for_device() {
  local serial="$1"
  log "Waiting for device $serial..."
  $ADB_CLI -s "$serial" wait-for-device || true
  $ADB_CLI -s "$serial" shell input keyevent 26 >/dev/null 2>&1 || true
  $ADB_CLI -s "$serial" shell input swipe 300 1000 300 500 >/dev/null 2>&1 || true
  $ADB_CLI -s "$serial" shell input keyevent 82 >/dev/null 2>&1 || true
  $ADB_CLI -s "$serial" shell settings put global device_provisioned 1 >/dev/null 2>&1 || true
  $ADB_CLI -s "$serial" shell settings put secure user_setup_complete 1 >/dev/null 2>&1 || true
  maybe_disable_setup_wizard "$serial"
  quiet_google_overlays "$serial"
  local BOOT=""; local tries=0
  while [[ "$BOOT" != "1" && $tries -lt 300 ]]; do
    sleep 2
    BOOT="$($ADB_CLI -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    tries=$((tries+1))
  done
  ensure_launcher_ready "$serial"
}

latest_build_tools_dirs() {
  if [[ -d "$ANDROID_HOME/build-tools" ]]; then
    (cd "$ANDROID_HOME/build-tools" && ls -1 | sort -V)
  fi
}

find_arm64_aapt2_path() {
  local p
  for d in $(latest_build_tools_dirs); do
    p="$ANDROID_HOME/build-tools/$d/aapt2"
    if [[ -x "$p" ]]; then
      if { command -v file >/dev/null 2>&1 && file "$p" | grep -qiE 'aarch64|ARM'; } \
         || { command -v readelf >/dev/null 2>&1 && readelf -h "$p" 2>/dev/null | grep -qi 'AArch64'; }; then
        echo -n "$p"
        return 0
      fi
    fi
  done
  return 1
}

ensure_arm64_aapt2() {
  local aapt2_found=""
  aapt2_found="$(find_arm64_aapt2_path || true)"
  if [[ -n "$aapt2_found" ]]; then
    echo -n "$aapt2_found"; return 0
  fi
  log "No ARM aapt2 found. Installing extra build-tools..."
  yes | "$SDKMGR" --sdk_root="$ANDROID_HOME" "build-tools;34.0.0" "build-tools;33.0.2" >/dev/null || true
  aapt2_found="$(find_arm64_aapt2_path || true)"
  if [[ -z "$aapt2_found" ]]; then
    log "ERROR: Could not find ARM aapt2 even after installing build-tools."
    exit 1
  fi
  echo -n "$aapt2_found"
}

configure_gradle_aapt2() {
  local aapt2_path
  if [[ -x "$AAPT2_PIN" ]]; then
    aapt2_path="$AAPT2_PIN"
  else
    aapt2_path="$(ensure_arm64_aapt2)"
  fi
  log "Using aapt2: $aapt2_path"
  mkdir -p /root/.gradle
  {
    echo "android.aapt2FromMavenOverride=$aapt2_path"
    echo "android.useAapt2Daemon=false"
    echo "org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8"
    echo "org.gradle.workers.max=2"
  } >> /root/.gradle/gradle.properties
  if [[ "${PRESERVE_AAPT2_CACHE}" != "1" ]]; then
    find /root/.gradle/caches -type d -name "aapt2*" -prune -exec rm -rf {} + 2>/dev/null || true
  fi
}

build_apk() {
  log "Собираем APK :${APP_MODULE}:${GRADLE_TASK} ..."
  if [[ -f "./gradlew" ]]; then
    chmod +x ./gradlew

    # На всякий случай зададим org.gradle.vfs.watch=false (если нет в проекте)
    if ! grep -q 'org.gradle.vfs.watch=' gradle.properties 2>/dev/null; then
      echo "org.gradle.vfs.watch=false" >> gradle.properties || true
    fi

    local logf="/workspace/.gradle-build.log"
    rm -f "$logf"

    # ВАЖНО: получить реальный код выхода gradle, даже с pipe | tee
    set +e
    ./gradlew :"$APP_MODULE":"$GRADLE_TASK" \
      --no-daemon --stacktrace --warning-mode=all --info \
      | tee "$logf"
    local rc=${PIPESTATUS[0]}
    set -e

    if [[ $rc -ne 0 ]]; then
      log "Gradle FAILED (exit=$rc). Последние строки лога:"
      tail -n 120 "$logf" || true
      exit $rc
    fi
  else
    log "ОШИБКА: gradlew не найден в /workspace. Смонтируйте проект в /workspace."
    exit 1
  fi
}


find_apk() {
  if [[ -n "$APK_PATH" && -f "$APK_PATH" ]]; then
    echo "$APK_PATH"; return
  fi
  local apk
  apk="$(find "$APP_MODULE"/build/outputs/apk -type f \( -name "*debug*.apk" -o -name "*release*.apk" -o -name "*.apk" \) | head -n1 || true)"
  if [[ -z "$apk" || ! -f "$apk" ]]; then
    log "ERROR: APK not found in $APP_MODULE/build/outputs/apk"
    exit 1
  fi
  echo "$apk"
}

extract_app_info() {
  local apk="$1"
  local pkg=""; local act=""; local target=""
  if [[ -x "$APK_ANALYZER" ]]; then
    pkg="$("$APK_ANALYZER" manifest application-id "$apk" 2>/dev/null || true)"; pkg="$(echo "$pkg" | tr -d '\r')"
    target="$("$APK_ANALYZER" manifest target-sdk "$apk" 2>/dev/null || true)"
    local manifest; manifest="$("$APK_ANALYZER" manifest print "$apk" 2>/dev/null || true)"
    act="$(echo "$manifest" | awk '
      /<activity / {in_a=1; name=""}
      in_a && /android:name=/ { if (match($0,/android:name="([^"]+)"/,m)) name=m[1] }
      /<category / && /android.intent.category.LAUNCHER/ { if (in_a && name!=""){print name; exit} }
      /<\/activity>/ {in_a=0; name=""}
    ')"
    if [[ -n "$target" && "$target" -ge 31 && -n "$act" ]]; then
      local exported=""
      exported="$(echo "$manifest" | awk -v a="$act" '
        $0 ~ "<activity " && $0 ~ "android:name=\""a"\"" { in=1 }
        in && /android:exported="/ { if (match($0,/android:exported="(true|false)"/,m)) { print m[1]; exit } }
        /<\/activity>/ { in=0 }
      ')"
      if [[ "$exported" != "true" ]]; then
        log "WARNING: targetSdk=$target and launcher activity '$act' has no android:exported=\"true\". App may not start."
      fi
    fi
  else
    log "WARN: apkanalyzer not found; app info may be incomplete."
  fi
  echo "${pkg}|${act}"
}

compose_component() {
  local pkg="$1"; local act="$2"
  if [[ -n "$COMPONENT_OVERRIDE" ]]; then
    echo -n "$COMPONENT_OVERRIDE"; return
  fi
  local comp=""
  if [[ -z "$pkg" && -z "$act" ]]; then
    echo ""; return
  fi
  if [[ -n "$act" ]]; then
    if [[ "$act" == */* ]]; then
      comp="$act"
    elif [[ "$act" == .* ]]; then
      comp="$pkg/$act"
    else
      comp="$pkg/$act"
    fi
  fi
  echo -n "$comp"
}

resolve_component_via_pm() {
  local pkg="$1"
  local comp=""
  comp="$($ADB_CLI shell cmd package resolve-activity --brief \
           -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$pkg" 2>/dev/null \
           | tr -d '\r')"
  if [[ -n "$comp" && "$comp" == "$pkg/"* ]]; then
    echo -n "$comp"; return 0
  fi
  comp="$($ADB_CLI shell cmd package query-intent-activities \
           -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null \
           | tr -d '\r' | awk -v p="$pkg" '
              /ActivityInfo|^activity:/ {
                if ($0 ~ p"/") {
                  if (match($0, /(com\.[A-Za-z0-9._-]+)\/(\.?[A-Za-z0-9._-]+)/, m)) {
                    print m[1] "/" m[2]; exit
                  }
                }
              }')"
  [[ -n "$comp" ]] && echo -n "$comp"
}

top_focused_pkg() {
  local pkg=""
  pkg="$($ADB_CLI shell dumpsys activity activities 2>/dev/null \
        | sed -nE 's/.*topResumedActivity.* ([^ ]+)\/[A-Za-z0-9._$-]+.*/\1/p' | head -n1 | tr -d '\r' || true)"
  if [[ -z "$pkg" ]]; then
    pkg="$($ADB_CLI shell dumpsys activity activities 2>/dev/null \
          | sed -nE 's/.*mResumedActivity:.* ([^ ]+)\/[A-Za-z0-9._$-]+.*/\1/p' | head -n1 | tr -d '\r' || true)"
  fi
  if [[ -z "$pkg" ]]; then
    pkg="$($ADB_CLI shell dumpsys window windows 2>/dev/null \
          | sed -nE 's/.*mCurrentFocus=Window{[^ ]+ ([^ ]+)\/[A-Za-z0-9._$-]+}.*/\1/p' | head -n1 | tr -d '\r' || true)"
  fi
  echo -n "$pkg"
}

install_apk() {
  local apk="$1"
  log "Installing APK..."
  if ! $ADB_CLI install -t -g -r -d "$apk"; then
    log "Install failed; retry without -d ..."
    $ADB_CLI install -t -g -r "$apk" >/dev/null 2>&1 || true
  fi
}

launch_app() {
  local pkg="$1"; local act="$2"
  local app_id="$pkg"
  [[ -n "$APP_ID_OVERRIDE" ]] && app_id="$APP_ID_OVERRIDE"
  local comp
  comp="$(compose_component "$app_id" "$act")"
  if [[ -z "$comp" && -n "$app_id" ]]; then
    comp="$(resolve_component_via_pm "$app_id" || true)"
  fi

  if [[ -n "$app_id" ]]; then
    $ADB_CLI shell am force-stop "$app_id" >/dev/null 2>&1 || true
  fi

  for attempt in 1 2 3; do
    if [[ -n "$comp" ]]; then
      log "RUN: am start -W --activity-clear-top -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x14000000 -n $comp"
      $ADB_CLI shell am start -W \
        --activity-clear-top \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        -f 0x14000000 \
        -n "$comp" >/dev/null 2>&1 || true
      [[ $attempt -eq 2 ]] && $ADB_CLI shell cmd activity start-activity --user 0 -n "$comp" >/dev/null 2>&1 || true
    fi
    [[ $attempt -eq 2 && -n "$app_id" ]] && $ADB_CLI shell monkey -p "$app_id" -c android.intent.category.LAUNCHER -v 10 >/dev/null 2>&1 || true
    sleep 1
    local top; top="$(top_focused_pkg)"
    if [[ "$top" == "$app_id" ]]; then
      return 0
    fi
    $ADB_CLI shell input keyevent 3 >/dev/null 2>&1 || true
  done
  return 1
}

print_logcat_tail() {
  log "------ logcat tail (crash/AM/ATM) ------"
  $ADB_CLI logcat -d -t 600 2>/dev/null \
    | grep -E "AndroidRuntime|FATAL EXCEPTION|Activity(Task)?Manager|am_proc_start|am_create_activity|am_focused_activity|Permission Denial|SecurityException" \
    | tail -n 200 || true
  log "----------------------------------------"
}

# =========================
# Main
# =========================
cd /workspace

wait_for_host_adb

# Pick device (or use TARGET_SERIAL)
DEVICE_SERIAL=""
if [[ -n "$TARGET_SERIAL" ]]; then
  if $ADB_BASE devices | awk 'NR>1 && $2=="device"{print $1}' | grep -qx "$TARGET_SERIAL"; then
    DEVICE_SERIAL="$TARGET_SERIAL"
  else
    log "ERROR: TARGET_SERIAL '$TARGET_SERIAL' not found among devices:"
    $ADB_BASE devices || true
    exit 1
  fi
else
  for _ in {1..60}; do
    DEVICE_SERIAL="$(pick_device || true)"
    [[ -n "$DEVICE_SERIAL" ]] && break
    sleep 2
  done
fi
if [[ -z "$DEVICE_SERIAL" ]]; then
  log "ERROR: No devices found on host ADB."
  $ADB_BASE devices || true
  exit 1
fi

# Pin serial globally
export ANDROID_SERIAL="$DEVICE_SERIAL"
ADB_CLI="$ADB_BASE -s $DEVICE_SERIAL"
log "Using device: $DEVICE_SERIAL"

wait_for_device "$DEVICE_SERIAL"
ensure_local_properties_sdkdir

if [[ "$SKIP_AAPT2_OVERRIDE" != "1" ]]; then
  configure_gradle_aapt2
else
  log "Skipping aapt2 override (SKIP_AAPT2_OVERRIDE=1)"
fi

build_apk
APK="$(find_apk)"
log "APK: $APK"

APP_INFO="$(extract_app_info "$APK")"
EXTRACTED_APP_ID="${APP_INFO%%|*}"
EXTRACTED_ACTIVITY="${APP_INFO##*|}"

APP_ID="${APP_ID_OVERRIDE:-$EXTRACTED_APP_ID}"
LAUNCH_ACTIVITY="${EXTRACTED_ACTIVITY}"

log "applicationId (final): ${APP_ID:-<unknown>}"
log "launchable-activity (manifest): ${LAUNCH_ACTIVITY:-<unknown>}"
[[ -n "$COMPONENT_OVERRIDE" ]] && log "component override: $COMPONENT_OVERRIDE"

install_apk "$APK"

if [[ -n "$APP_ID" ]]; then
  if ! $ADB_CLI shell pm path "$APP_ID" >/dev/null 2>&1; then
    log "ERROR: Package $APP_ID not installed."
    print_logcat_tail
    exit 1
  fi
fi

if ! launch_app "$APP_ID" "$LAUNCH_ACTIVITY"; then
  log "ERROR: App did not come to foreground."
  print_logcat_tail
  exit 1
fi

log "Done. App should be in foreground. Device: $DEVICE_SERIAL"
tail -f /dev/null
