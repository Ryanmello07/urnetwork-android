#!/usr/bin/env bash
# Shared, side-effect-free helpers for the Android acceptance runner.

# GNU timeout normally moves its child into a separate process group. Under an
# interactive runner that group is in the terminal background, so Gradle can
# be stopped by SIGTTIN even after a successful build. Keep every bounded
# Android command in the runner's foreground process group.
run_android_acceptance_timeout() {
  local foreground_timeout_executable="$1"
  shift
  "$foreground_timeout_executable" --foreground "$@"
}

# The SDK builder publishes one exact, non-secret owner token with the Android
# output. Acceptance checks it only while holding the output's kernel lock, so
# a successful build cannot silently consume a different writer's later swap.
android_acceptance_verify_sdk_build_owner() {
  local owner_file="$1" expected_owner="$2" actual_owner line_count

  case "$expected_owner" in
    ''|*[!A-Za-z0-9._:-]*) return 2 ;;
  esac
  [ -f "$owner_file" ] && [ ! -L "$owner_file" ] || return 1
  actual_owner="$(cat -- "$owner_file")" || return 1
  line_count="$(LC_ALL=C wc -l <"$owner_file" | tr -d '[:space:]')" || return 1
  [ "$line_count" = 1 ] && [ "$actual_owner" = "$expected_owner" ]
}

# Every emulator that opens the same acceptance AVD must use read-only mode.
# Android's emulator rejects a read-only peer when the first instance holds the
# AVD writable, which otherwise prevents the peer-to-peer phase from starting.
run_android_acceptance_shared_avd_emulator() {
  local emulator="$1" log_file="$2" argument
  local read_only=0
  shift 2

  for argument in "$@"; do
    [ "$argument" = -read-only ] && read_only=1
  done
  if [ "$read_only" -ne 1 ]; then
    echo "refusing to start a shared acceptance AVD without -read-only" >&2
    return 2
  fi
  exec "$emulator" "$@" >"$log_file" 2>&1
}

# Copy one build's app/test pair into the runner-owned cache before another
# flavor is built. Gradle may replace its output directory on the next build;
# peer-to-peer acceptance must never retain those ephemeral paths.
android_acceptance_cache_apks() {
  local app_apk="$1" test_apk="$2" cache_dir="$3"

  [ -f "$app_apk" ] && [ -f "$test_apk" ] || return 1
  mkdir -p "$cache_dir" || return 1
  cp "$app_apk" "$cache_dir/app.apk" || return 1
  cp "$test_apk" "$cache_dir/test.apk" || return 1
  [ -s "$cache_dir/app.apk" ] && [ -s "$cache_dir/test.apk" ]
}

# Installs one APK through the push-based transport used by `--no-streaming`.
# Some physical/OEM package managers never finish a streamed install. Bound
# both transports with TERM followed by KILL; retry only when the host adb
# explicitly says it does not recognize --no-streaming, never on a device-side
# rejection or timeout.
android_acceptance_install_apk() {
  local install_timeout_executable="$1" adb="$2" serial="$3" apk="$4" log_file="$5"
  local install_status=0

  : >"$log_file" || return 1
  "$install_timeout_executable" --foreground \
    --signal=TERM --kill-after=10s 180s \
    "$adb" -s "$serial" install --no-streaming -r -t "$apk" \
    >"$log_file" 2>&1 || install_status=$?
  if [ "$install_status" -eq 0 ]; then
    return 0
  fi
  if ! LC_ALL=C grep -Eiq \
      '^((adb(:|\.exe)?:[[:space:]]*)?)(unknown|unrecognized|invalid)[[:space:]]+(option|argument)(:|[[:space:]])+[^[:cntrl:]]*--no-streaming[[:space:]]*$' \
      "$log_file"; then
    return "$install_status"
  fi

  printf '\n[android acceptance] host adb lacks --no-streaming; using bounded streamed fallback\n' \
    >>"$log_file"
  "$install_timeout_executable" --foreground \
    --signal=TERM --kill-after=10s 180s \
    "$adb" -s "$serial" install -r -t "$apk" \
    >>"$log_file" 2>&1
}

# Smoke launches only the shipping application. Full/instrumented and P2P
# cells install both UIDs as one fail-fast operation. Keeping that choice here
# prevents an unused test package from blocking a diagnostic smoke campaign.
android_acceptance_install_cell_apks() {
  local mode="$1" install_timeout_executable="$2" adb="$3" serial="$4"
  local app_apk="$5" test_apk="$6" app_log="$7" test_log="$8"

  case "$mode" in
    smoke|full) ;;
    *) return 2 ;;
  esac
  android_acceptance_install_apk \
    "$install_timeout_executable" "$adb" "$serial" "$app_apk" "$app_log" || return 1
  if [ "$mode" = smoke ]; then
    printf '%s\n' \
      '[android acceptance] instrumentation APK not installed for launch-only smoke' \
      >"$test_log"
    return 0
  fi
  android_acceptance_install_apk \
    "$install_timeout_executable" "$adb" "$serial" "$test_apk" "$test_log" || return 1
}

android_acceptance_cell_install_mode() {
  case "$1" in
    0) printf 'full\n' ;;
    1) printf 'smoke\n' ;;
    *) return 2 ;;
  esac
}

# Verifies that the second-UID egress activity can run as the test APK's
# standalone application. Instrumentation dependencies that are also present
# in the target APK are not copied into the test APK, so a Kotlin reference in
# this activity compiles successfully but fails at runtime before the probe can
# make its first request.
verify_android_acceptance_egress_probe() {
  local analyzer="$1" test_apk="$2" bytecode

  if ! bytecode="$(timeout 60 "$analyzer" dex code \
    --class com.bringyour.network.acceptance.EgressProbeActivity "$test_apk")"; then
    echo "could not inspect the standalone Android egress probe" >&2
    return 1
  fi
  if grep -q 'Lkotlin/' <<<"$bytecode"; then
    echo "standalone Android egress probe depends on the unavailable Kotlin runtime" >&2
    return 1
  fi
  return 0
}

# Android verifies its Application class before an SDK-level guard can run.
# Keep API-29-only thermal listener types in the compat class so API 26-28 can
# link MainApplication. Inspect the built APK rather than trusting source
# layout, because Kotlin anonymous classes and fields can reintroduce the type.
verify_android_acceptance_legacy_application_linkage() {
  local analyzer="$1" app_apk="$2" bytecode unavailable_type

  unavailable_type="Landroid/os/PowerManager\$OnThermalStatusChangedListener;"

  if ! bytecode="$(timeout 60 "$analyzer" dex code \
    --class com.bringyour.network.MainApplication "$app_apk")"; then
    echo "could not inspect Android application linkage" >&2
    return 1
  fi
  if grep -Fq "$unavailable_type" <<<"$bytecode"; then
    echo "MainApplication links an API-29-only thermal listener type" >&2
    return 1
  fi
  return 0
}

# Removes only the private run directory created by test-main.sh. Go module
# downloads are intentionally read-only, so ask Go to clean its cache first
# and make the remaining private tree writable before the final removal.
remove_android_acceptance_run_dir() {
  local run_dir="$1"

  case "$run_dir" in
    /*/urnetwork-android-acceptance.*) ;;
    *)
      echo "refusing to remove non-acceptance directory: $run_dir" >&2
      return 2
      ;;
  esac
  if [ -L "$run_dir" ]; then
    echo "refusing to remove symlinked acceptance directory: $run_dir" >&2
    return 2
  fi
  [ -e "$run_dir" ] || return 0

  if [ -d "$run_dir/go-mod-cache" ] && command -v go >/dev/null 2>&1; then
    GOMODCACHE="$run_dir/go-mod-cache" go clean -modcache >/dev/null 2>&1 || true
  fi
  chmod -R u+w "$run_dir" 2>/dev/null || true
  rm -rf -- "$run_dir"
}

android_acceptance_adb_device_ready() {
  local adb="$1" serial="$2" state

  if ! state="$(timeout 15 "$adb" -s "$serial" get-state </dev/null 2>/dev/null)"; then
    return 1
  fi
  [ "$(printf '%s' "$state" | tr -d '\r\n')" = device ]
}

android_acceptance_write_readiness_status() {
  local status_file="$1" status="$2" temporary
  temporary="${status_file}.tmp.$$"

  case "$status" in
    adb-unavailable|boot-incomplete|api-unavailable|shipping-api-unsupported|\
    abi-unavailable|shipping-abi-unsupported|\
    unlock-failed|wifi-state-unavailable|wifi-enable-failed|\
    network-unavailable|dns-unavailable|api-tcp-unreachable|ready) ;;
    *) return 2 ;;
  esac
  mkdir -p "$(dirname "$status_file")" || return 1
  printf 'status=%s\n' "$status" >"$temporary" || return 1
  chmod 600 "$temporary" || { rm -f "$temporary"; return 1; }
  mv "$temporary" "$status_file"
}

# Probe the control-plane transport the app actually needs. ICMP is not a
# service-readiness contract: it can be blocked while HTTPS is healthy. The
# output is deliberately a finite, non-secret reason token rather than raw
# network state, resolver output, SSIDs, or addresses.
android_acceptance_api_tcp_status() {
  local adb="$1" serial="$2" probe_output connectivity

  # Android 8/9 toybox netcat has no -z, while newer releases reject -q 0.
  # Connecting with empty stdin and -q 1 works across the shipping fleet and
  # still proves that the API's TCP listener accepted a connection.
  if probe_output="$(timeout 10 "$adb" -s "$serial" shell \
      toybox nc -w 5 -q 1 api.bringyour.com 443 </dev/null 2>&1)"; then
    printf 'ready\n'
    return 0
  fi
  connectivity="$(timeout 10 "$adb" -s "$serial" shell \
    dumpsys connectivity </dev/null 2>/dev/null || true)"
  connectivity="${connectivity//$'\r'/}"
  if grep -Eq '^Active default network:[[:space:]]*none[[:space:]]*$' \
      <<<"$connectivity"; then
    printf 'network-unavailable\n'
  elif grep -Eiq \
      'unknown host|no address associated with hostname|name or service not known|temporary failure in name resolution' \
      <<<"$probe_output"; then
    printf 'dns-unavailable\n'
  else
    printf 'api-tcp-unreachable\n'
  fi
  return 1
}

# Every general shipping flavor contains both Android ARM ABIs. Solana's
# hardware-specific flavor remains arm64-only and is planned exclusively for
# known Saga/Seeker devices later in the capability matrix.
android_acceptance_device_has_shipping_abi() {
  local abi_list="$1"

  case ",$abi_list," in
    *,arm64-v8a,*|*,armeabi-v7a,*) return 0 ;;
  esac
  return 1
}

# Fail the build cell before installation if a Gradle/filter regression drops
# one of its deliberate shipping ABIs. apkanalyzer reads the APK actually
# cached for this run, rather than trusting source configuration alone.
verify_android_acceptance_shipping_apk_abis() {
  local analyzer="$1" app_apk="$2" flavor="$3" actual expected

  if ! actual="$(timeout 60 "$analyzer" files list "$app_apk" | \
      awk -F/ '$2 == "lib" && NF >= 4 { print $3 }' | LC_ALL=C sort -u)"; then
    return 1
  fi
  case "$flavor" in
    github|ethos_dapp|fdroid) expected=$'arm64-v8a\narmeabi-v7a' ;;
    play) expected=$'arm64-v8a\narmeabi-v7a\nx86_64' ;;
    solana_dapp) expected='arm64-v8a' ;;
    *) return 2 ;;
  esac
  if [ "$actual" != "$expected" ]; then
    echo "shipping APK ABI contract failed for $flavor" >&2
    return 1
  fi
}

verify_android_acceptance_shipping_apk_min_sdk() {
  local analyzer="$1" app_apk="$2" flavor="$3" actual expected

  if ! actual="$(timeout 60 "$analyzer" manifest min-sdk "$app_apk" | tr -d '\r\n')"; then
    return 1
  fi
  case "$flavor" in
    ethos_dapp) expected=27 ;;
    github|play|solana_dapp|fdroid) expected=26 ;;
    *) return 2 ;;
  esac
  if [ "$actual" != "$expected" ]; then
    echo "shipping APK minimum-SDK contract failed for $flavor" >&2
    return 1
  fi
}

# Restore only Wi-Fi state changed by this runner. The ownership record is
# written before enabling Wi-Fi, so EXIT cleanup remains correct if the enable
# command or a later readiness probe is interrupted.
android_acceptance_restore_network() {
  local adb="$1" serial="$2" state_file="$3"

  [ -f "$state_file" ] || return 0
  [ "$(wc -l <"$state_file" | tr -d ' ')" = 1 ] || return 1
  [ "$(sed -n '1p' "$state_file")" = 'restore=wifi-disabled' ] || return 1
  timeout 15 "$adb" -s "$serial" shell svc wifi disable \
    </dev/null >/dev/null
}

android_acceptance_wait_for_network() {
  local adb="$1" serial="$2" state_file="$3" status_file="$4"
  local attempts="${5:-24}" reason wifi_status attempt temporary

  case "$attempts" in ''|*[!0-9]*|0) return 2 ;; esac
  if reason="$(android_acceptance_api_tcp_status "$adb" "$serial")"; then
    android_acceptance_write_readiness_status "$status_file" ready
    return 0
  fi

  # settings + svc are available on the oldest supported fleet releases;
  # `cmd wifi status/set-wifi-enabled` is not present on Android 8/9.
  wifi_status="$(timeout 15 "$adb" -s "$serial" shell settings get global wifi_on \
    </dev/null 2>/dev/null | tr -d '\r\n' || true)"
  case "$wifi_status" in
    1) ;;
    0)
      mkdir -p "$(dirname "$state_file")" || return 1
      temporary="${state_file}.tmp.$$"
      printf 'restore=wifi-disabled\n' >"$temporary" || return 1
      chmod 600 "$temporary" || { rm -f "$temporary"; return 1; }
      mv "$temporary" "$state_file" || { rm -f "$temporary"; return 1; }
      if ! timeout 15 "$adb" -s "$serial" shell svc wifi enable \
          </dev/null >/dev/null 2>&1; then
        android_acceptance_write_readiness_status "$status_file" wifi-enable-failed
        return 1
      fi
      ;;
    *)
      android_acceptance_write_readiness_status "$status_file" wifi-state-unavailable
      return 1
      ;;
  esac

  # A scan is a bounded nudge for already provisioned saved networks. Never
  # guess credentials, select an SSID, or silently skip an attached device.
  timeout 15 "$adb" -s "$serial" shell cmd wifi start-scan \
    </dev/null >/dev/null 2>&1 || true
  for attempt in $(seq 1 "$attempts"); do
    if reason="$(android_acceptance_api_tcp_status "$adb" "$serial")"; then
      android_acceptance_write_readiness_status "$status_file" ready
      return 0
    fi
    [ "$attempt" -eq "$attempts" ] || sleep 5
  done
  android_acceptance_write_readiness_status "$status_file" "$reason"
  return 1
}

# Wait for the platform, wake/unlock it, and only then wait for its network.
# Unlocking first matters on physical devices whose Wi-Fi autoconnect is
# quiescent while the saved acceptance device is asleep or keyguarded.
android_acceptance_prepare_device() {
  local adb="$1" serial="$2" unlock_code="$3" state_dir="$4" status_file="$5"
  local boot_attempts="${6:-180}" network_attempts="${7:-24}"
  local boot_completed api_level abi_list attempt

  case "$boot_attempts" in ''|*[!0-9]*|0) return 2 ;; esac
  mkdir -p "$state_dir" "$(dirname "$status_file")" || return 1
  if ! timeout 180 "$adb" -s "$serial" wait-for-device </dev/null; then
    android_acceptance_write_readiness_status "$status_file" adb-unavailable
    return 1
  fi
  boot_completed=""
  for attempt in $(seq 1 "$boot_attempts"); do
    boot_completed="$(timeout 10 "$adb" -s "$serial" shell \
      getprop sys.boot_completed </dev/null 2>/dev/null | tr -d '\r\n' || true)"
    [ "$boot_completed" = 1 ] && break
    [ "$attempt" -eq "$boot_attempts" ] || sleep 2
  done
  if [ "$boot_completed" != 1 ]; then
    android_acceptance_write_readiness_status "$status_file" boot-incomplete
    return 1
  fi
  if ! api_level="$(timeout 10 "$adb" -s "$serial" shell \
      getprop ro.build.version.sdk </dev/null 2>/dev/null | tr -d '\r\n')"; then
    android_acceptance_write_readiness_status "$status_file" api-unavailable
    return 1
  fi
  case "$api_level" in
    ''|*[!0-9]*)
      android_acceptance_write_readiness_status "$status_file" api-unavailable
      return 1
      ;;
  esac
  if [ "$api_level" -lt 26 ]; then
    android_acceptance_write_readiness_status "$status_file" shipping-api-unsupported
    return 1
  fi
  if ! abi_list="$(timeout 10 "$adb" -s "$serial" shell \
      getprop ro.product.cpu.abilist </dev/null 2>/dev/null | tr -d '\r\n')"; then
    android_acceptance_write_readiness_status "$status_file" abi-unavailable
    return 1
  fi
  if ! android_acceptance_device_has_shipping_abi "$abi_list"; then
    android_acceptance_write_readiness_status "$status_file" shipping-abi-unsupported
    return 1
  fi
  if ! android_acceptance_unlock_device "$adb" "$serial" "$unlock_code"; then
    android_acceptance_write_readiness_status "$status_file" unlock-failed
    return 1
  fi
  android_acceptance_wait_for_network \
    "$adb" "$serial" "$state_dir/network-state" "$status_file" "$network_attempts"
}

# Returns 0 when the current Android user is unlocked, 1 when it is locked,
# and 2 when the device does not expose a trustworthy lock-state signal. The
# current-user filter avoids accepting an unlocked background user while the
# foreground acceptance user remains locked.
android_acceptance_device_unlocked() {
  local adb="$1" serial="$2" trust current

  if ! trust="$(timeout 15 "$adb" -s "$serial" shell dumpsys trust </dev/null 2>/dev/null)"; then
    return 2
  fi
  current="$(grep -F '(current):' <<<"$trust" || true)"
  [ -n "$current" ] || current="$trust"
  if grep -Eq '(^|[[:space:],])deviceLocked=(0|false)($|[[:space:],])|Device locked:[[:space:]]*false' \
      <<<"$current"; then
    return 0
  fi
  if grep -Eq '(^|[[:space:],])deviceLocked=(1|true)($|[[:space:],])|Device locked:[[:space:]]*true' \
      <<<"$current"; then
    return 1
  fi
  return 2
}

# Returns 0 only when Android reports an interactive display. Some OEM
# keyguards accept input commands while PowerManager is still transitioning
# from sleep, then silently discard the credential before SystemUI can consume
# it. Treat an unknown power signal as unavailable rather than guessing.
android_acceptance_device_awake() {
  local adb="$1" serial="$2" power

  if ! power="$(timeout 15 "$adb" -s "$serial" shell dumpsys power </dev/null 2>/dev/null)"; then
    return 2
  fi
  if grep -Eq \
      '^[[:space:]]*mWakefulness=Awake([[:space:]]|$)|^[[:space:]]*mInteractive=true([[:space:]]|$)' \
      <<<"$power"; then
    return 0
  fi
  if grep -Eq \
      '^[[:space:]]*mWakefulness=(Asleep|Dozing|Dreaming)([[:space:]]|$)|^[[:space:]]*mInteractive=false([[:space:]]|$)' \
      <<<"$power"; then
    return 1
  fi
  return 2
}

# Wake the display once, then poll the authoritative platform state. Retrying
# the observation handles an asynchronous OEM wake transition without
# repeating credentials or guessing a fixed device-specific delay.
android_acceptance_wake_device() {
  local adb="$1" serial="$2" state

  timeout 15 "$adb" -s "$serial" shell input keyevent KEYCODE_WAKEUP \
    </dev/null >/dev/null 2>&1 || return 1
  for _ in $(seq 1 20); do
    if android_acceptance_device_awake "$adb" "$serial"; then
      return 0
    else
      state=$?
    fi
    [ "$state" -eq 1 ] || return 1
    sleep 0.25
  done
  return 1
}

# Secure keyguards do not have a portable editable text target. In particular,
# OnePlus SystemUI ignores `input text` even though adb reports success. Reveal
# the credential surface with a display-relative swipe, then let SystemUI
# consume one key event per digit. The full code exists only in shell memory
# and crosses adb on stdin; it is never placed in a process argument or log.
android_acceptance_enter_unlock_code() {
  local adb="$1" serial="$2" unlock_code="$3" size width height
  local center_x start_y end_y

  if ! size="$(timeout 15 "$adb" -s "$serial" shell wm size </dev/null 2>/dev/null | \
      tr -d '\r' | sed -nE 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/p' | tail -1)"; then
    return 1
  fi
  read -r width height <<<"$size"
  case "$width" in ''|*[!0-9]*|0) return 1 ;; esac
  case "$height" in ''|*[!0-9]*|0) return 1 ;; esac
  center_x=$((width / 2))
  start_y=$((height * 4 / 5))
  end_y=$((height / 5))
  [ "$center_x" -gt 0 ] && [ "$start_y" -gt "$end_y" ] || return 1

  timeout 15 "$adb" -s "$serial" shell wm dismiss-keyguard \
    </dev/null >/dev/null 2>&1 || true
  timeout 15 "$adb" -s "$serial" shell input keyevent KEYCODE_MENU \
    </dev/null >/dev/null 2>&1 || true
  timeout 15 "$adb" -s "$serial" shell input swipe \
    "$center_x" "$start_y" "$center_x" "$end_y" 300 \
    </dev/null >/dev/null 2>&1 || return 1
  sleep 1

  # These expansions belong to the remote Android shell. It validates and
  # consumes the stdin-only code, clearing each digit before starting the next
  # input process. No host or remote argv contains the complete credential.
  # shellcheck disable=SC2016
  printf '%s\n' "$unlock_code" | timeout 20 "$adb" -s "$serial" shell \
    'IFS= read -r ur_accept_unlock_code || exit 2; case "$ur_accept_unlock_code" in ""|*[!0-9]*) exit 2;; esac; [ "${#ur_accept_unlock_code}" -ge 4 ] && [ "${#ur_accept_unlock_code}" -le 16 ] || exit 2; while [ -n "$ur_accept_unlock_code" ]; do ur_accept_digit=${ur_accept_unlock_code%"${ur_accept_unlock_code#?}"}; ur_accept_unlock_code=${ur_accept_unlock_code#?}; case "$ur_accept_digit" in 0) ur_accept_key=KEYCODE_0;; 1) ur_accept_key=KEYCODE_1;; 2) ur_accept_key=KEYCODE_2;; 3) ur_accept_key=KEYCODE_3;; 4) ur_accept_key=KEYCODE_4;; 5) ur_accept_key=KEYCODE_5;; 6) ur_accept_key=KEYCODE_6;; 7) ur_accept_key=KEYCODE_7;; 8) ur_accept_key=KEYCODE_8;; 9) ur_accept_key=KEYCODE_9;; *) exit 2;; esac; input keyevent "$ur_accept_key" || exit 1; done; unset ur_accept_digit ur_accept_key ur_accept_unlock_code; input keyevent KEYCODE_ENTER' \
    >/dev/null 2>&1
}

# Wake and unlock one authorized device without exposing its code in argv,
# logs, or shell tracing. The code crosses adb only on standard input. USB
# debugging authorization is necessarily a one-time manual prerequisite: adb
# cannot inject input into a device that has not authorized this host.
android_acceptance_unlock_device() {
  local adb="$1" serial="$2" unlock_code="$3" state

  case "$unlock_code" in
    ''|*[!0-9]*) return 2 ;;
  esac
  [ "${#unlock_code}" -ge 4 ] && [ "${#unlock_code}" -le 16 ] || return 2
  android_acceptance_adb_device_ready "$adb" "$serial" || return 1
  android_acceptance_wake_device "$adb" "$serial" || return 1

  if android_acceptance_device_unlocked "$adb" "$serial"; then
    return 0
  else
    state=$?
  fi
  [ "$state" -eq 1 ] || return 1

  # Submit the credential exactly once. The loop below retries only the
  # lock-state observation, never the PIN.
  android_acceptance_enter_unlock_code "$adb" "$serial" "$unlock_code" || return 1

  for _ in $(seq 1 20); do
    if android_acceptance_device_unlocked "$adb" "$serial"; then
      return 0
    else
      state=$?
    fi
    [ "$state" -eq 1 ] || return 1
    sleep 0.25
  done
  return 1
}

android_acceptance_device_has_play_services() {
  local adb="$1" serial="$2" paths

  if ! paths="$(timeout 15 "$adb" -s "$serial" shell pm path com.google.android.gms </dev/null 2>/dev/null)"; then
    return 1
  fi
  paths="${paths//$'\r'/}"
  grep -Eq '^package:.+' <<<"$paths"
}

# Solana dapp builds are hardware-targeted and may be installed only on Saga
# or Seeker. Normalize common manufacturer-prefixed model spellings but do not
# infer eligibility merely from Play services or a generic Android release.
android_acceptance_is_solana_device() {
  local value normalized

  for value in "$@"; do
    normalized="$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')"
    case "$normalized" in
      saga|solanasaga|solanamobilesaga|seeker|solanaseeker|solanamobileseeker) return 0 ;;
    esac
  done
  return 1
}

# Resolve one immutable acceptance fleet from `adb devices -l`. Reserved
# performance devices are recorded but never selected, even if they are the
# only attached hardware. Every other visible adb target must be fully
# authorized and online: silently dropping an offline/unauthorized device
# would let a fleet run claim coverage it never exercised.
android_acceptance_select_adb_devices() {
  local raw_file="$1" selected_file="$2" excluded_file="$3"
  shift 3
  local selected_tmp="${selected_file}.tmp.$$"
  local excluded_tmp="${excluded_file}.tmp.$$"
  local line serial state reserved candidate

  : >"$selected_tmp" || return 1
  : >"$excluded_tmp" || { rm -f "$selected_tmp"; return 1; }
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in
      ""|"List of devices attached"|\**daemon*) continue ;;
    esac
    read -r serial state _ <<<"$line"
    if [ -z "${serial:-}" ] || [ -z "${state:-}" ]; then
      echo "malformed adb device row: $line" >&2
      rm -f "$selected_tmp" "$excluded_tmp"
      return 1
    fi
    case "$serial" in
      *[[:space:]]*)
        echo "invalid adb device serial" >&2
        rm -f "$selected_tmp" "$excluded_tmp"
        return 1
        ;;
    esac
    reserved=0
    for candidate in "$@"; do
      if [ "$serial" = "$candidate" ]; then
        reserved=1
        break
      fi
    done
    if [ "$reserved" -eq 1 ]; then
      printf '%s\t%s\treserved-for-performance\n' "$serial" "$state" >>"$excluded_tmp"
      continue
    fi
    if [ "$state" != device ]; then
      echo "adb device $serial is $state; every non-reserved attached device must be authorized and online" >&2
      rm -f "$selected_tmp" "$excluded_tmp"
      return 1
    fi
    if grep -Fqx -- "$serial" "$selected_tmp"; then
      echo "duplicate adb device serial: $serial" >&2
      rm -f "$selected_tmp" "$excluded_tmp"
      return 1
    fi
    printf '%s\n' "$serial" >>"$selected_tmp"
  done <"$raw_file"

  LC_ALL=C sort "$selected_tmp" >"${selected_tmp}.sorted" || {
    rm -f "$selected_tmp" "$selected_tmp.sorted" "$excluded_tmp"
    return 1
  }
  LC_ALL=C sort "$excluded_tmp" >"${excluded_tmp}.sorted" || {
    rm -f "$selected_tmp" "$selected_tmp.sorted" "$excluded_tmp" "$excluded_tmp.sorted"
    return 1
  }
  mv "${selected_tmp}.sorted" "$selected_file" || return 1
  mv "${excluded_tmp}.sorted" "$excluded_file" || return 1
  rm -f "$selected_tmp" "$excluded_tmp"
}

# The narrow device/case selectors are diagnostic-only. Requiring the pair
# prevents either option from silently changing the canonical fleet matrix.
android_acceptance_execution_mode() {
  local diagnostic_device_seen="$1" diagnostic_case_seen="$2"

  case "$diagnostic_device_seen:$diagnostic_case_seen" in
    0:0) printf 'canonical\n' ;;
    1:1) printf 'diagnostic\n' ;;
    *)
      echo "--diagnostic-device and --diagnostic-case must be supplied together" >&2
      return 2
      ;;
  esac
}

# A focused P2P invocation is intentionally one fresh build, one physical
# device, one flavor, and one case. Three confirmations must therefore be
# three independent invocations with independent cleanup boundaries. A
# diagnostic may never emit the result file consumed by final proof.
android_acceptance_validate_diagnostic_request() {
  local serial="$1" requested_case="$2" flavor_count="$3" flavor="$4"
  local repeat_count="$5" skip_build="$6" smoke_only="$7"
  local keep_emulator="$8" keep_fixture="$9" result_matrix="${10}"
  shift 10
  local reserved

  case "$serial" in
    ''|emulator-*|*[!A-Za-z0-9._:-]*)
      echo "--diagnostic-device must name one physical adb serial" >&2
      return 2
      ;;
  esac
  for reserved in "$@"; do
    if [ "$serial" = "$reserved" ]; then
      echo "diagnostic device $serial is reserved for performance testing" >&2
      return 2
    fi
  done
  if [ "$requested_case" != peer-to-peer ]; then
    echo "--diagnostic-case supports only peer-to-peer" >&2
    return 2
  fi
  if [ "$flavor_count" != 1 ]; then
    echo "diagnostic peer-to-peer requires exactly one --flavor" >&2
    return 2
  fi
  case "$flavor" in
    github|play|solana_dapp|ethos_dapp|fdroid) ;;
    *)
      echo "diagnostic peer-to-peer requires one exact shipping flavor" >&2
      return 2
      ;;
  esac
  if [ "$repeat_count" != 1 ]; then
    echo "diagnostic peer-to-peer requires --repeat=1; repeat the whole command for independent proof" >&2
    return 2
  fi
  if [ "$skip_build" != 0 ]; then
    echo "diagnostic peer-to-peer forbids --skip-build and requires a fresh paired APK build" >&2
    return 2
  fi
  if [ "$smoke_only" != 0 ]; then
    echo "diagnostic peer-to-peer cannot be combined with --smoke" >&2
    return 2
  fi
  if [ "$keep_emulator" != 0 ]; then
    echo "diagnostic peer-to-peer forbids --keep-emulator so cleanup remains mandatory" >&2
    return 2
  fi
  if [ "$keep_fixture" != 0 ]; then
    echo "diagnostic peer-to-peer does not own the account fixture; --keep-fixture is invalid" >&2
    return 2
  fi
  if [ -n "$result_matrix" ]; then
    echo "diagnostic selectors cannot write the canonical acceptance result matrix" >&2
    return 2
  fi
}

# Select from the already captured immutable fleet. Reserved serials and
# emulators are rejected independently of the fleet parser so a future caller
# cannot bypass the diagnostic boundary by handing this helper a custom file.
android_acceptance_select_diagnostic_device() {
  local captured_file="$1" output_file="$2" requested_serial="$3"
  shift 3
  local reserved matches temporary="${output_file}.tmp.$$"

  case "$requested_serial" in
    ''|emulator-*|*[!A-Za-z0-9._:-]*)
      echo "diagnostic device must be one physical adb serial" >&2
      return 2
      ;;
  esac
  for reserved in "$@"; do
    if [ "$requested_serial" = "$reserved" ]; then
      echo "diagnostic device $requested_serial is reserved for performance testing" >&2
      return 2
    fi
  done
  [ -f "$captured_file" ] || return 1
  matches="$(awk -v serial="$requested_serial" '$0 == serial { count++ } END { print count + 0 }' "$captured_file")" || \
    return 1
  if [ "$matches" != 1 ]; then
    echo "diagnostic device $requested_serial is not exactly once in the immutable eligible fleet" >&2
    return 1
  fi
  printf '%s\n' "$requested_serial" >"$temporary" || return 1
  mv "$temporary" "$output_file"
}

# The instant-account fixture belongs only to canonical full acceptance.
# Focused P2P authenticates with the bounded credential file and must neither
# create, replace, nor delete a shared fixture.
android_acceptance_manages_account_fixture() {
  local execution_mode="$1" smoke_only="$2"

  case "$execution_mode:$smoke_only" in
    canonical:0) return 0 ;;
    canonical:1|diagnostic:0) return 1 ;;
    *) return 2 ;;
  esac
}

# Give every selected device a collision-free artifact id without hiding its
# adb serial. The numeric prefix remains unique even when two serials collapse
# to the same filesystem-safe spelling.
android_acceptance_write_device_records() {
  local serials_file="$1" records_file="$2"
  local temporary="${records_file}.tmp.$$" serial safe index=0

  : >"$temporary" || return 1
  while IFS= read -r serial || [ -n "$serial" ]; do
    [ -n "$serial" ] || continue
    index=$((index + 1))
    safe="$(printf '%s' "$serial" | tr -c 'A-Za-z0-9._-' '_')"
    printf 'device-%03d-%s\t%s\n' "$index" "$safe" "$serial" >>"$temporary" || {
      rm -f "$temporary"
      return 1
    }
  done <"$serials_file"
  mv "$temporary" "$records_file"
}

# Write the capability-valid execution plan and every intentionally omitted
# cell. Target-major order builds each APK once, then runs it sequentially on
# compatible devices. Capability rows are:
#   device-id<TAB>serial<TAB>play-services(0|1)<TAB>solana-device(0|1)<TAB>api
android_acceptance_write_device_flavor_plan() {
  local capabilities_file="$1" plan_file="$2" skipped_file="$3"
  shift 3
  local temporary="${plan_file}.tmp.$$" skipped_temporary="${skipped_file}.tmp.$$"
  local target device_id serial play_services solana_device android_api extra reason

  : >"$temporary" || return 1
  : >"$skipped_temporary" || { rm -f "$temporary"; return 1; }
  for target in "$@"; do
    while IFS=$'\t' read -r device_id serial play_services solana_device android_api extra; do
      [ -n "$device_id" ] && [ -n "$serial" ] && \
        { [ "$play_services" = 0 ] || [ "$play_services" = 1 ]; } && \
        { [ "$solana_device" = 0 ] || [ "$solana_device" = 1 ]; } && \
        [ -n "$android_api" ] && [ "$android_api" = "${android_api//[^0-9]/}" ] && \
        [ -z "${extra:-}" ] || {
        rm -f "$temporary" "$skipped_temporary"
        return 1
      }
      reason=""
      if [ "$target" = play ] && [ "$play_services" -ne 1 ]; then
        reason="requires-google-play-services"
      elif [ "$target" = solana_dapp ] && [ "$solana_device" -ne 1 ]; then
        reason="requires-solana-seeker-or-saga"
      elif [ "$target" = ethos_dapp ] && [ "$android_api" -lt 27 ]; then
        reason="requires-android-api-27"
      fi
      if [ -n "$reason" ]; then
        printf '%s\t%s\t%s\t%s\n' "$device_id" "$serial" "$target" "$reason" >>"$skipped_temporary" || {
          rm -f "$temporary" "$skipped_temporary"
          return 1
        }
      elif ! printf '%s\t%s\t%s\n' "$device_id" "$serial" "$target" >>"$temporary"; then
        rm -f "$temporary" "$skipped_temporary"
        return 1
      fi
    done <"$capabilities_file"
  done
  mv "$temporary" "$plan_file" || { rm -f "$temporary" "$skipped_temporary"; return 1; }
  mv "$skipped_temporary" "$skipped_file"
}

# A shipping flavor may have fewer compatible devices, but it must never
# disappear from a canonical plan entirely.
android_acceptance_require_target_coverage() {
  local plan_file="$1"
  shift
  local target

  for target in "$@"; do
    awk -F '\t' -v target="$target" '$3 == target { found = 1 } END { exit !found }' "$plan_file" || {
      echo "no compatible Android device is attached for $target" >&2
      return 1
    }
  done
}

# Require exactly one result for every device/flavor/case cell. This prevents
# an early loop exit or a duplicate row from being summarized as an aggregate
# Android pass.
android_acceptance_verify_device_flavor_results() {
  local plan_file="$1" results_file="$2"
  awk -F '\t' '
    BEGIN {
      split("email phone instant password data-plane peer-to-peer", required, " ")
      failed = 0
    }
    NR == FNR {
      if (NF != 3 || $1 == "" || $2 == "" || $3 == "") { failed = 1; next }
      pair = $1 FS $2 FS $3
      if (++plans[pair] != 1) failed = 1
      next
    }
    {
      if (NF != 6) { failed = 1; next }
      pair = $1 FS $2 FS $3
      if (!(pair in plans)) { failed = 1; next }
      valid_case = 0
      for (i in required) if ($4 == required[i]) valid_case = 1
      if (!valid_case || ($5 != "PASS" && $5 != "FAIL") || $6 == "") {
        failed = 1
        next
      }
      cell = pair FS $4
      if (++seen[cell] != 1) failed = 1
      if ($5 != "PASS") failed = 1
    }
    END {
      for (pair in plans) {
        for (i in required) {
          cell = pair FS required[i]
          if (seen[cell] != 1) failed = 1
        }
      }
      exit failed
    }
  ' "$plan_file" "$results_file"
}

# Diagnostic output is deliberately not a partial canonical matrix. It must
# contain exactly the selected plan row and its one successful requested case.
android_acceptance_verify_diagnostic_result() {
  local plan_file="$1" results_file="$2" requested_case="$3"

  [ "$requested_case" = peer-to-peer ] || return 2
  awk -F '\t' -v requested_case="$requested_case" '
    NR == FNR {
      if (NF != 3 || $1 == "" || $2 == "" || $3 == "") failed = 1
      plan_count++
      plan = $1 FS $2 FS $3
      next
    }
    {
      if (NF != 6 || $1 FS $2 FS $3 != plan || $4 != requested_case ||
          $5 != "PASS" || $6 == "") failed = 1
      result_count++
    }
    END {
      if (plan_count != 1 || result_count != 1) failed = 1
      exit failed
    }
  ' "$plan_file" "$results_file"
}

# Smoke results deliberately do not masquerade as full auth/data-plane rows.
# Require one launch result for every planned device/flavor cell instead.
android_acceptance_verify_device_flavor_smoke_results() {
  local plan_file="$1" results_file="$2"
  awk -F '\t' '
    NR == FNR {
      if (NF != 3 || $1 == "" || $2 == "" || $3 == "") { failed = 1; next }
      cell = $1 FS $2 FS $3
      if (++plans[cell] != 1) failed = 1
      next
    }
    {
      if (NF != 5 || $1 == "" || $2 == "" || $3 == "" || $5 == "") { failed = 1; next }
      cell = $1 FS $2 FS $3
      if (!(cell in plans) || ++seen[cell] != 1 || $4 != "PASS") failed = 1
    }
    END {
      for (cell in plans) if (seen[cell] != 1) failed = 1
      exit failed
    }
  ' "$plan_file" "$results_file"
}

# Matches only an authoritative foreground-activity field, not an arbitrary
# historical ActivityRecord elsewhere in dumpsys output.
android_acceptance_has_foreground_component() {
  local activity_dump="$1" component="$2" package_name activity_name
  local short_component full_component line

  package_name="${component%%/*}"
  activity_name="${component#*/}"
  [ -n "$package_name" ] && [ -n "$activity_name" ] && \
    [ "$package_name" != "$component" ] || return 1
  case "$activity_name" in
    .*)
      short_component="$package_name/$activity_name"
      full_component="$package_name/${package_name}${activity_name}"
      ;;
    "$package_name".*)
      short_component="$package_name/.${activity_name#"$package_name."}"
      full_component="$package_name/$activity_name"
      ;;
    *)
      short_component="$component"
      full_component="$component"
      ;;
  esac

  while IFS= read -r line; do
    case "$line" in
      *mResumedActivity:*|*mResumedActivity=*|*topResumedActivity:*|*topResumedActivity=*|*mFocusedActivity:*|*mFocusedActivity=*) ;;
      *) continue ;;
    esac
    case "$line" in
      *"$short_component "*|*"$short_component}"*|*"$short_component]"*|*"$short_component,"*|\
      *"$full_component "*|*"$full_component}"*|*"$full_component]"*|*"$full_component,"*) return 0 ;;
    esac
  done <<<"${activity_dump//$'\r'/}"
  return 1
}

android_acceptance_pid_list_valid() {
  local pid_list="$1" pid pid_count=0

  case "$pid_list" in
    ''|*[!0-9\ ]*) return 1 ;;
  esac
  for pid in $pid_list; do
    case "$pid" in
      ''|*[!0-9]*) return 1 ;;
    esac
    [ "$pid" -gt 0 ] || return 1
    pid_count=$((pid_count + 1))
  done
  [ "$pid_count" -gt 0 ]
}

# Resolve, launch, and verify both the authoritative foreground component and
# a surviving app process. Some Samsung API-28 builds return `Status: timeout`
# after displaying the requested activity; that OEM wait result is retained in
# the log but is not itself a launch failure when both independent facts hold.
# The caller owns logcat/screenshot collection and package removal.
android_acceptance_launch_smoke() {
  local adb="$1" serial="$2" package_name="$3" log_file="$4"
  local poll_attempts="${5:-10}"
  local resolved activity_output activity_status=0 reported_status
  local activity_dump pid foreground_status process_status

  case "$poll_attempts" in ''|*[!0-9]*|0) return 2 ;; esac

  if ! resolved="$(timeout 30 "$adb" -s "$serial" shell cmd package resolve-activity \
    --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package_name" </dev/null 2>/dev/null)"; then
    return 1
  fi
  resolved="$(printf '%s\n' "$resolved" | tr -d '\r' | sed '/^[[:space:]]*$/d' | tail -1)"
  case "$resolved" in
    "$package_name"/*) ;;
    *) return 1 ;;
  esac
  activity_output="$(timeout 60 "$adb" -s "$serial" shell am start -W -n "$resolved" </dev/null 2>&1)" || \
    activity_status=$?
  printf '%s\n' "$activity_output" >"$log_file"
  if [ "$activity_status" -ne 0 ]; then
    printf 'HarnessVerification: command-exit=%s\n' "$activity_status" >>"$log_file"
    return 1
  fi
  activity_output="${activity_output//$'\r'/}"
  reported_status="$(sed -n 's/^Status:[[:space:]]*//p' <<<"$activity_output")"
  case "$reported_status" in
    ok|timeout) ;;
    *)
      printf 'HarnessVerification: launch-status=unexpected\n' >>"$log_file"
      return 1
      ;;
  esac
  for _ in $(seq 1 "$poll_attempts"); do
    activity_dump="$(timeout 15 "$adb" -s "$serial" shell dumpsys activity activities \
      </dev/null 2>/dev/null || true)"
    pid="$(timeout 15 "$adb" -s "$serial" shell pidof "$package_name" </dev/null 2>/dev/null | tr -d '\r\n' || true)"
    foreground_status=missing
    process_status=missing
    android_acceptance_has_foreground_component "$activity_dump" "$resolved" && \
      foreground_status=expected
    android_acceptance_pid_list_valid "$pid" && process_status=alive
    if [ "$foreground_status" = expected ] && [ "$process_status" = alive ]; then
      printf 'HarnessVerification: launch-status=%s foreground=expected process=alive\n' \
        "$reported_status" >>"$log_file"
      return 0
    fi
    sleep 0.5
  done
  printf 'HarnessVerification: launch-status=%s foreground=%s process=%s\n' \
    "$reported_status" "$foreground_status" "$process_status" >>"$log_file"
  return 1
}

# The acceptance AVD's launcher can ANR while multiple read-only instances
# start under software rendering. Its system error dialog then owns the
# foreground window and hides the app from UI Automator even though the app is
# healthy. This is a dedicated test AVD, so suppress those unrelated dialogs
# before any instrumentation starts.
android_acceptance_suppress_system_error_dialogs() {
  local adb="$1" serial="$2"

  timeout 15 "$adb" -s "$serial" shell settings put global hide_error_dialogs 1 \
    </dev/null >/dev/null
}

# Compose can take longer to become idle while an app-owned infinite animation
# advances. Disabling platform animations is only an optimization: physical
# OEM builds may deny WRITE_SECURE_SETTINGS even to adb shell, which must not
# reject an otherwise valid product smoke. Each setting is journaled as
# pending before mutation and owned only after a verified change. This closes
# the interruption window without claiming settings that were never changed.
android_acceptance_animation_value_valid() {
  [[ "$1" =~ ^(null|[0-9]+([.][0-9]+)?|[.][0-9]+)$ ]]
}

android_acceptance_animation_value_zero() {
  [[ "$1" =~ ^(0+([.]0*)?|[.]0+)$ ]]
}

android_acceptance_read_animation_scale() {
  local adb="$1" serial="$2" key="$3" value

  if ! value="$(timeout 15 "$adb" -s "$serial" shell settings get global "$key" \
      </dev/null 2>/dev/null)"; then
    return 1
  fi
  value="${value//$'\r'/}"
  android_acceptance_animation_value_valid "$value" || return 2
  printf '%s\n' "$value"
}

android_acceptance_disable_animations() {
  local adb="$1" serial="$2" state_dir="$3"
  local diagnostic_file="${4:-${state_dir}.diagnostic}"
  local key value observed pending owned write_output write_status
  local changed=0 denied=0 diagnostic_tmp="${diagnostic_file}.tmp.$$"

  [ ! -e "$state_dir" ] || return 1
  [ ! -e "$diagnostic_file" ] || return 1
  mkdir -p "$(dirname "$state_dir")" "$(dirname "$diagnostic_file")" || return 1
  mkdir "$state_dir" || return 1
  chmod 700 "$state_dir" || return 1
  printf 'version=1\n' >"$diagnostic_tmp" || return 1
  chmod 600 "$diagnostic_tmp" || { rm -f "$diagnostic_tmp"; return 1; }
  mv "$diagnostic_tmp" "$diagnostic_file" || { rm -f "$diagnostic_tmp"; return 1; }

  for key in \
    window_animation_scale \
    transition_animation_scale \
    animator_duration_scale; do
    if value="$(android_acceptance_read_animation_scale "$adb" "$serial" "$key")"; then
      :
    else
      printf '%s=read-failed\nstatus=failure\n' "$key" >>"$diagnostic_file" || true
      return 1
    fi
    if android_acceptance_animation_value_zero "$value"; then
      printf '%s=already-zero\n' "$key" >>"$diagnostic_file" || return 1
      continue
    fi

    pending="$state_dir/$key.pending"
    owned="$state_dir/$key.owned"
    printf '%s\n' "$value" >"${pending}.tmp.$$" || return 1
    chmod 600 "${pending}.tmp.$$" || { rm -f "${pending}.tmp.$$"; return 1; }
    mv "${pending}.tmp.$$" "$pending" || { rm -f "${pending}.tmp.$$"; return 1; }

    if write_output="$(timeout 15 "$adb" -s "$serial" shell \
        settings put global "$key" 0 </dev/null 2>&1)"; then
      write_status=0
    else
      write_status=$?
    fi
    if observed="$(android_acceptance_read_animation_scale "$adb" "$serial" "$key")"; then
      :
    else
      printf '%s=verify-failed\nstatus=failure\n' "$key" >>"$diagnostic_file" || true
      return 1
    fi

    if android_acceptance_animation_value_zero "$observed"; then
      mv "$pending" "$owned" || return 1
      printf '%s=changed\n' "$key" >>"$diagnostic_file" || return 1
      changed=$((changed + 1))
      continue
    fi
    if [ "$observed" = "$value" ] && [ "$write_status" -ne 0 ] && \
        grep -Eiq 'SecurityException|WRITE_SECURE_SETTINGS|Permission denial' \
          <<<"$write_output"; then
      rm -f "$pending"
      printf '%s=permission-denied\n' "$key" >>"$diagnostic_file" || return 1
      denied=$((denied + 1))
      continue
    fi
    if [ "$observed" = "$value" ] && [ "$write_status" -eq 0 ]; then
      rm -f "$pending"
      printf '%s=not-applied\n' "$key" >>"$diagnostic_file" || return 1
      denied=$((denied + 1))
      continue
    fi
    printf '%s=write-failed\nstatus=failure\n' "$key" >>"$diagnostic_file" || true
    return 1
  done

  rmdir "$state_dir" 2>/dev/null || true
  if [ "$denied" -gt 0 ]; then
    printf 'status=best-effort\n' >>"$diagnostic_file" || return 1
    echo "[android acceptance] animation optimization unavailable on $serial; see $diagnostic_file" >&2
  elif [ "$changed" -gt 0 ]; then
    printf 'status=disabled\n' >>"$diagnostic_file" || return 1
  else
    printf 'status=no-change\n' >>"$diagnostic_file" || return 1
  fi
  return 0
}

# Restore only a setting whose journal proves this runner changed it. A
# pending entry is reconciled after interruption: the original value means no
# write landed, zero means the runner's write landed, and every other value is
# a foreign change that cleanup must not overwrite.
android_acceptance_restore_animations() {
  local adb="$1" serial="$2" state_dir="$3"
  local journal name key phase original current restore_output
  local seen_window=0 seen_transition=0 seen_animator=0

  [ -e "$state_dir" ] || return 0
  [ -d "$state_dir" ] && [ ! -L "$state_dir" ] || return 1
  # Validate the complete journal before changing the device. This prevents a
  # later duplicate or foreign entry from being discovered after an earlier
  # owned setting was already restored.
  for journal in "$state_dir"/*; do
    [ -e "$journal" ] || continue
    [ -f "$journal" ] && [ ! -L "$journal" ] || return 1
    name="$(basename "$journal")"
    case "$name" in
      window_animation_scale.pending|window_animation_scale.owned)
        [ "$seen_window" -eq 0 ] || return 1
        seen_window=1
        ;;
      transition_animation_scale.pending|transition_animation_scale.owned)
        [ "$seen_transition" -eq 0 ] || return 1
        seen_transition=1
        ;;
      animator_duration_scale.pending|animator_duration_scale.owned)
        [ "$seen_animator" -eq 0 ] || return 1
        seen_animator=1
        ;;
      *) return 1 ;;
    esac
    [ "$(awk 'END { print NR }' "$journal")" -eq 1 ] || return 1
    original="$(<"$journal")"
    android_acceptance_animation_value_valid "$original" || return 1
  done

  # Resolve every current value before the first restore as well. A foreign
  # edit in any owned setting blocks the whole transaction without allowing a
  # lexically earlier journal entry to be applied first.
  for journal in "$state_dir"/*; do
    [ -e "$journal" ] || continue
    name="$(basename "$journal")"
    key="${name%.*}"
    original="$(<"$journal")"
    if current="$(android_acceptance_read_animation_scale "$adb" "$serial" "$key")"; then
      :
    else
      return 1
    fi
    [ "$current" = "$original" ] || \
      android_acceptance_animation_value_zero "$current" || return 1
  done

  for journal in "$state_dir"/*; do
    [ -e "$journal" ] || continue
    name="$(basename "$journal")"
    case "$name" in
      window_animation_scale.pending|window_animation_scale.owned|\
      transition_animation_scale.pending|transition_animation_scale.owned|\
      animator_duration_scale.pending|animator_duration_scale.owned)
        key="${name%.*}"
        phase="${name##*.}"
        ;;
      *) return 1 ;;
    esac
    original="$(<"$journal")"
    if current="$(android_acceptance_read_animation_scale "$adb" "$serial" "$key")"; then
      :
    else
      return 1
    fi
    if [ "$current" = "$original" ]; then
      rm -f "$journal" || return 1
      continue
    fi
    android_acceptance_animation_value_zero "$current" || return 1

    if [ "$original" = null ]; then
      if ! restore_output="$(timeout 15 "$adb" -s "$serial" shell \
          settings delete global "$key" </dev/null 2>&1)"; then
        return 1
      fi
    elif ! restore_output="$(timeout 15 "$adb" -s "$serial" shell \
        settings put global "$key" "$original" </dev/null 2>&1)"; then
      return 1
    fi
    if current="$(android_acceptance_read_animation_scale "$adb" "$serial" "$key")"; then
      :
    else
      return 1
    fi
    [ "$current" = "$original" ] || return 1
    rm -f "$journal" || return 1
    : "$phase" "$restore_output"
  done
  rmdir "$state_dir"
}

# Returns 0 when the app-private file exists, 1 when the device is reachable
# and the file does not exist, and 2 when its state cannot be verified.
android_acceptance_private_file_status() {
  local adb="$1" serial="$2" package_name="$3" relative_path="$4"

  if timeout 30 "$adb" -s "$serial" shell run-as "$package_name" \
    test -f "$relative_path" >/dev/null 2>&1; then
    return 0
  fi
  if android_acceptance_adb_device_ready "$adb" "$serial"; then
    return 1
  fi
  return 2
}

# Pulls one private client ID without accepting adb/run-as diagnostics as data.
# Missing packages and files are ordinary cleanup states; an unreachable device
# or malformed existing file remains an error.
pull_android_acceptance_private_client_id() {
  local adb="$1" serial="$2" package_name="$3" relative_path="$4" destination="$5"
  local file_status temporary client_id line_count

  rm -f "$destination"
  if android_acceptance_private_file_status \
    "$adb" "$serial" "$package_name" "$relative_path"; then
    file_status=0
  else
    file_status=$?
  fi
  case "$file_status" in
    0) ;;
    1) return 0 ;;
    *) return 1 ;;
  esac

  mkdir -p "$(dirname "$destination")"
  temporary="$(mktemp "${destination}.tmp.XXXXXX")"
  if ! timeout 30 "$adb" -s "$serial" exec-out run-as "$package_name" \
    cat "$relative_path" >"$temporary" 2>/dev/null; then
    rm -f "$temporary"
    return 1
  fi
  line_count="$(awk 'END { print NR }' "$temporary")"
  IFS= read -r client_id <"$temporary" || true
  client_id="${client_id%$'\r'}"
  case "$line_count:$client_id" in
    1:|1:*[!A-Za-z0-9._-]*)
      rm -f "$temporary"
      return 1
      ;;
    1:*) ;;
    *)
      rm -f "$temporary"
      return 1
      ;;
  esac
  printf '%s\n' "$client_id" >"$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$destination"
}

# Pulls retained client IDs only when their private file exists. Some adb
# versions print a remote cat error on stdout while returning success; probing
# first prevents that diagnostic from being mistaken for a client ID.
pull_android_acceptance_active_clients() {
  local adb="$1" serial="$2" run_dir="$3" destination="$4"
  local temporary="$run_dir/active-client-ids" file_status client_id index=0

  if android_acceptance_private_file_status \
    "$adb" "$serial" com.bringyour.network files/acceptance/active-client-ids; then
    file_status=0
  else
    file_status=$?
  fi
  case "$file_status" in
    0) ;;
    1) rm -f "$temporary"; return 0 ;;
    *) rm -f "$temporary"; return 1 ;;
  esac

  if ! timeout 30 "$adb" -s "$serial" exec-out run-as com.bringyour.network \
    cat files/acceptance/active-client-ids >"$temporary" 2>/dev/null; then
    rm -f "$temporary"
    return 1
  fi
  mkdir -p "$destination"
  while read -r client_id; do
    [ -n "$client_id" ] || continue
    case "$client_id" in
      *[!A-Za-z0-9._-]*)
        echo "invalid retained client ID from Android" >&2
        rm -f "$temporary"
        return 1
        ;;
    esac
    index=$((index + 1))
    printf '%s\n' "$client_id" >"$destination/active-client-id-$index"
    chmod 600 "$destination/active-client-id-$index"
  done < <(sort -u "$temporary")
  rm -f "$temporary"
}

# Returns 0 only after an authoritative package-list query proves absence, 1
# when the exact package remains installed, and 2 when the query fails or has
# unrecognized output. A failed `pm path` is ambiguous (missing package and
# transport errors both occur), so cleanup must not use it as absence proof.
android_acceptance_package_absent() {
  local package_timeout_executable="$1" adb="$2" serial="$3" package_name="$4"
  local package_list line

  if ! package_list="$("$package_timeout_executable" --foreground \
      --signal=TERM --kill-after=5s 15s \
      "$adb" -s "$serial" shell pm list packages "$package_name" \
      </dev/null 2>/dev/null)"; then
    return 2
  fi
  while IFS= read -r line; do
    line="${line%$'\r'}"
    [ -n "$line" ] || continue
    case "$line" in
      "package:$package_name") return 1 ;;
      package:*) ;;
      *) return 2 ;;
    esac
  done <<<"$package_list"
  return 0
}

# Attempts one bounded uninstall and then judges only the independently queried
# package state. A command can fail after removing the package, so its output is
# retained while verified absence remains success. Installed or unverifiable
# state is fail-closed and never converted into a clean result.
android_acceptance_uninstall_package() {
  local uninstall_timeout_executable="$1" adb="$2" serial="$3" package_name="$4"
  local log_file="$5" uninstall_status=0 absence_status

  : >"$log_file" || return 2
  "$uninstall_timeout_executable" --foreground \
    --signal=TERM --kill-after=5s 30s \
    "$adb" -s "$serial" uninstall "$package_name" \
    </dev/null >"$log_file" 2>&1 || uninstall_status=$?
  if android_acceptance_package_absent \
      "$uninstall_timeout_executable" "$adb" "$serial" "$package_name"; then
    if [ "$uninstall_status" -ne 0 ]; then
      printf '\n[android acceptance] uninstall exit=%s; absence independently verified\n' \
        "$uninstall_status" >>"$log_file"
    fi
    return 0
  else
    absence_status=$?
  fi
  printf '\n[android acceptance] uninstall exit=%s; absence verification=%s\n' \
    "$uninstall_status" "$absence_status" >>"$log_file"
  return "$absence_status"
}
