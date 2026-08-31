# Diagnostic log export — design

Date: 2026-08-30
Repos: `urnetwork-sdk`, `urnetwork-ios`, `urnetwork-android` (branch `beta/custom-server` in each)
Status: approved design, not yet planned

This spec lives in the android repo because it is the only one of the three
carrying the `docs/superpowers/specs` convention. It governs work in all three.

## Problem

A user hitting a connection problem has no way to hand over what the client
saw. Support has to reproduce it, and the detail that would explain it —
transport selection, window evaluation, provider dial failures — lives in
client logs nobody can retrieve.

### Everything that reads logs today is already broken

`sdk.GetLogDir()` returns `""` **in every process**, so every consumer of it
reads nothing.

`GetLogDir` reads the glog `log_dir` *flag* (`sdk/sdk.go:151-156`). But
`glog.SetLogDir` mutates only the package `logDirs` slice and `dirSet`, never
that flag (`glog/glog_file.go:583-585`), and `sdk.SetLogDir`
(`sdk/sdk.go:162-173`) no longer calls `flag.Set("log_dir", …)` — sdk commit
`9f41a00` "set log directory from app" (2025-11-25) removed those lines.

Verified empirically:

```
SetLogDir("/tmp/…/001") -> GetLogDir() = ""
files actually written:    [sdk.test.INFO,
                            sdk.test.Mac.ryanmello.log.INFO.20260830-201831.59048]
os.ReadDir(GetLogDir()) -> 0 entries, err=open : no such file or directory
```

Consequences, all of them current production behaviour:

- **`Device.UploadLogs(feedbackId)`** (`sdk/device_local.go:5621`) opens with
  `logDir := GetLogDir()` and `os.ReadDir(logDir)`. "Attach logs to feedback"
  has been attaching nothing since 2025-11-25.
- **Android** `ExportLogButton(logDir = Sdk.getLogDir())` and
  `ShareLogFileButton(logDir = Sdk.getLogDir())` receive `""`, so they list no
  files and render the "no log files found" branch. Android's export does not
  work either — and separately, `ExportLogButton` would only ever have taken
  the single newest `.log.INFO`, with no WARNING/ERROR/FATAL and no rotations.
- **iOS** `LogExportService.swift:50` gets `""` and falls through to its
  "No log directory available" ad-hoc file.

Fixing `GetLogDir` is therefore Task 1, and it repairs the feedback-attachment
flow as a side effect of the export work.

iOS is additionally broken in its own right. `SdkSetLogDir` is called only in
`PacketTunnelProvider.swift:608`, in the network-extension process. The app
holds a `SdkDeviceRemote`; the real `DeviceLocal` runs in the extension. glog
writes **no files at all** until `SetLogDir` is called — there is no default
temp destination (`glog/glog_file.go`, `SetLogDir` doc comment) — so the iOS
app process produces no log files whatsoever. And there is no app-group
entitlement in any of the three `.entitlements` files, so the app cannot reach
the extension's container regardless. `ExportLogsButton()` is commented out at
`FeedbackView.swift:60`, so none of it is reachable anyway.

`UploadLogs` is still the right structural precedent — it zips all four
severities and is already RPC-bridged (`DeviceRemote.UploadLogs` →
`DeviceLocalRpc.UploadLogs`, `sdk/device_rpc.go:5027`). But it uploads to the
API; it never produces a local file the user can see or keep.

## Goals

1. One action exports **everything** the client knows: all glog files, every
   severity and rotation, from every process, plus platform-side logs and a
   machine-readable manifest of device/build/connection state.
2. Works on **iOS and Android**, in the **developer menu** on both.
3. Ships in **production/release builds**, not only dev or beta builds.
4. Three export modes: **full raw**, **redacted**, and a **selective picker**.
5. Degrades rather than fails: a source that cannot be read is recorded as
   missing and the rest of the bundle is still produced.

## Non-goals

- Changing `UploadLogs` or the feedback flow. They stay as they are.
- Uploading the bundle anywhere. This produces a local file; the user chooses
  where it goes.
- Log viewing or search inside the app.
- Capturing other apps' or system-wide logs.

## Current state that constrains the design

**The iOS extension runs on a 20 MB device memory target**
(`PacketTunnelProvider.swift`, the `SdkNewDeviceLocalWithMemoryTarget` call).
glog caps each file at 16 MB and keeps 4. Zipping those inside the extension,
or streaming them over the RPC, would put megabytes through the one process
that cannot afford them — and precisely when the tunnel is already
misbehaving. This is the single most important constraint here.

**`clearOldLogs` keeps only the 4 most recent files across the whole
directory** (`sdk/sdk.go:79`). Two processes writing into one directory would
evict each other's history.

**The iOS app process never sets a log directory at all.** Since glog writes
nothing until `SetLogDir`, that process emits no log files at all — its SDK
logging is simply lost, not merely misplaced.

**glog log directories contain symlinks as well as files.** Each severity gets
a `<program>.<SEVERITY>` symlink beside the real
`<program>.<host>.<user>.log.<SEVERITY>.<YYYYMMDD>-<HHMMSS>.<pid>` file
(`glog/glog_file.go:124-140`). Inventory must skip symlinks or every file is
counted twice.

**A glog entry is not always one line.** Files open with a 5-line plaintext
header and rotate with a 2-line footer; backtraces are appended as
`"\n\n%v\n"`, and those continuation lines carry no `[IWEF]` header prefix
(`glog/glog.go:179-199`, `glog/glog_file.go:392-414`). A redaction filter must
treat unprefixed lines as continuations rather than assuming one entry per
line. The header format is
`[IWEF]mmdd hh:mm:ss.uuuuuu threadid file:line] msg`, and individual messages
are capped at `MaxLogMessageLen = 15000` (`glog/logsink.go:33`).

**Platform-side logging is not captured anywhere today**: 249 `print()` calls
on iOS (not even in `OSLog`) and 196 `android.util.Log` calls on Android
(logcat only). None of it reaches a file.

**`defaultLogDir` in `glog_ios.go`, `glog_android.go` and `glog_macos.go` is
dead code** — defined in all three, called from nowhere. Left alone by this
work; noted so nobody mistakes it for the live path.

**Both developer menus already ship in release builds.** Neither the iOS
`Section("Developer")` in `SettingsForm-iOS.swift:467` nor the Android entry
in `SettingsScreen.kt` is behind `#if DEBUG` or `BuildConfig.DEBUG`. Goal 3
therefore requires adding no gate, rather than removing one.

## Architecture

The organising principle: **bulk data moves as files on disk; only small
structured state crosses the RPC.**

### Log directory layout

Each process writes into its own subdirectory of a shared root:

```
<AppGroup container>/Logs/app/          iOS app process
<AppGroup container>/Logs/extension/    iOS PacketTunnelProvider
<filesDir>/logs/app/                    Android (single process)
```

This is load-bearing, not cosmetic:

- `clearOldLogs` already takes a directory, so "keep the 4 most recent"
  becomes **4 per process** rather than 4 in total. The eviction problem is
  solved without modifying that function.
- Source attribution is the subdirectory name, rather than parsing glog's
  `<program>.<host>.<user>.log.<severity>.<date>-<time>.<pid>` filenames.
- It forces the iOS app process to set a log directory, which fixes the
  "app-process logs go to a temp path" gap as a side effect.

New SDK functions, replacing bare `SetLogDir` at the call sites:

```go
// SetLogDirForProcess points glog at <root>/<processName> and records root
// so the exporter can enumerate every process's logs. When root is unusable
// it falls back to a process-local directory and returns nil -- logging must
// never be what breaks a launch. It returns a non-nil error only when neither
// the root nor the fallback can be opened, in which case glog keeps its
// previous destination. The directory actually in use is always readable via
// GetLogDir, and the recorded root via GetLogRoot.
func SetLogDirForProcess(root string, processName string) error

// GetLogRoot returns the recorded root, or "" if only a legacy SetLogDir ran.
func GetLogRoot() string
```

`SetLogDir` and `GetLogDir` stay for compatibility and for any caller that
does not need the multi-process shape.

### iOS: app group shared container

Add `com.apple.security.application-groups` to `network.entitlements` and
`extension.entitlements`. Both processes resolve the container with
`FileManager.default.containerURL(forSecurityApplicationGroupIdentifier:)` and
call `SetLogDirForProcess(container/Logs, "app" | "extension")`.

The app then reads every file directly off disk and builds the zip in its own
process, which holds `com.apple.developer.kernel.increased-memory-limit`. The
extension does no extra work beyond writing its logs to a different path.

**Degradation (goal 5).** If `containerURL(...)` returns nil — the entitlement
is absent from the running build's provisioning profile — each process falls
back to its own private directory. The app can then still collect its own
logs; the extension's are unreachable. The export proceeds and records
`{"source": "extension", "available": false, "reason": "app group container
unavailable"}` in the manifest, with a matching line in the bundle's
`README.txt` and a visible note in the export UI. **An export never fails
because a source is missing.** This is what makes the feature safe to ship in
a production build whose profile predates the app group.

### SDK: the bundle builder

New file `sdk/diagnostics.go`. Everything exported here must be
gomobile-bindable: no `context.Context`, no maps, no `[]byte`, no slices of
structs, no variadics. Lists use the existing `exportedList[T]` wrapper, the
same way the rest of the SDK does — the `build_android` recipe's `// skipped`
gate fails the build on any unexpectedly unbindable export, so this is
enforced by CI rather than by review.

```go
// LogFileInfo describes one file the bundle could include.
type LogFileInfo struct {
    Name           string // glog file name
    Path           string // absolute path
    Source         string // "app" | "extension"
    Severity       string // "INFO" | "WARNING" | "ERROR" | "FATAL"
    ByteCount      int64
    ModifiedMillis int64
}

type LogFileInfoList struct { /* exportedList[*LogFileInfo] */ }

// LogInventory enumerates every log file under the recorded root, across all
// processes. Backs the selective picker and the size estimate shown before
// export.
func LogInventory() *LogFileInfoList

type ExportOptions struct {
    Redact              bool
    IncludeManifest     bool
    IncludePlatformLogs bool
    SelectedNames       *StringList // nil or empty means every file
}

// ExportDiagnosticBundle writes a zip to destPath and returns the result,
// including any sources that could not be read. Missing sources are reported,
// never fatal. A malformed destPath, or an unwritable destination, is fatal.
func ExportDiagnosticBundle(destPath string, opts *ExportOptions) (*ExportResult, error)

type ExportResult struct {
    ByteCount        int64
    FileCount        int
    MissingSources   *StringList // human-readable, e.g. "extension: app group container unavailable"
}
```

`zipLogs` (`sdk/device_local.go:5678`) is generalised to take an entry name
and an `io.Reader` so the same writer serves `UploadLogs`, the raw export and
the redacted export. `UploadLogs`'s observable behaviour does not change.

### Manifest — the one thing that does cross the RPC

```go
// On the Device interface, implemented by DeviceLocal, RPC-bridged to
// DeviceRemote exactly as UploadLogs is.
DiagnosticManifestJson() string
```

It returns kilobytes of JSON, so the RPC round trip is trivial — this is the
correct use of the channel, in contrast to shipping log files through it. On
iOS it is what lets the manifest describe **extension-side** state
(connection status, transport/window state, provider stats) that the app
process cannot see directly.

Contents: app version and build, SDK version, OS version, device model,
network space and environment, connect/provide state, current performance
profile and provide settings, instance id, client id, glog verbosity, the
per-source availability list, and the export mode used. Client and instance
ids are subject to redaction like anything else.

If the RPC is unavailable (tunnel not running), the manifest is written with
the app-side fields populated and the device-side fields marked unavailable.

### Redaction

A streaming per-line filter applied while copying each entry into the zip, so
no whole file is ever held in memory.

- UUID-shaped identifiers and IPv4/IPv6 literals with optional `:port` are
  replaced by a salted, stable token — the same input maps to the same output
  for the life of one export, so a flow can still be followed across lines
  without revealing the real address.
- The salt is generated per export and is **never written into the bundle**.
  The manifest records only that redaction was applied.
- Only these patterns are touched. Timestamps, component tags, counters and
  message structure survive verbatim.

Redaction is a mode, not a default, and the UI is explicit about which mode
produced a given file. At the default verbosity (`v=0`, set in `sdk.go:82`)
destination IPs are not logged at all — the `[multi]drop packet ... -> %s:%d`
line at `connect/ip_remote_multi_client.go:5864` sits behind `V(1)`. Raising
verbosity for debugging is exactly what puts them in the file, which is why
the redacted mode exists.

### Platform logs — deliberately asymmetric

**Android** dumps its own logcat at export time (`logcat -d -v threadtime`),
written to `platform/logcat.txt`. Since Android 4.1 an app can read only its
own entries, which is precisely the wanted scope. The existing 196 `Log.*`
call sites are left alone.

**iOS cannot do the equivalent.** `OSLogStore` reads only the current process,
so the app cannot see the extension's entries, and the 249 `print()` calls are
not in `OSLog` at all. Rather than build a second capture mechanism, Swift
logging is routed *into glog*:

- New SDK export `LogPlatform(level int, tag string, message string)` writes
  through the same `connect.Logger` the Go side uses.
- A thin Swift `URLog` wrapper calls both `os_log` (so live Xcode debugging is
  unchanged) and `SdkLogPlatform` (so it lands in the file).
- The `print()` call sites are migrated to `URLog`.

Swift logs then land in the same per-process files the exporter already
collects, needing no extra transport and no extra entitlement. The migration
is large but mechanical and belongs in its own commit, separate from the
feature.

### Bundle format

```
urnetwork-diagnostics-<ISO8601>.zip
├── README.txt              what this is, which mode produced it, what is missing
├── manifest.json           DiagnosticManifestJson output + export metadata
├── logs/app/*.log.*        app-process glog files
├── logs/extension/*.log.*  iOS only, when the app group is available
└── platform/logcat.txt     Android only
```

## UI

Three actions on both platforms, in the developer menu:

- **Export all logs (raw)**
- **Export redacted logs**
- **Choose logs…** — the selective picker, listing `LogInventory()` grouped by
  source, each row showing severity, size and modified time, with checkboxes
  and an "Export selected" action.

Each screen shows the total size before exporting, and any unavailable source
with its reason.

**iOS** — a Diagnostics section in the existing `DeveloperView`. The route
already exists: `SettingsForm-iOS.swift:467` → `navigate(.developer)` →
`AccountNavStackView.swift:220`. Delivery via `ShareLink` and `fileExporter`.
`LogExportService`, `LogExportDocument`, `LogExportTransferrable` and
`ExportLogsButton` are **deleted**, not extended — they read the wrong
process's directory, and the commented-out call site goes with them.

**Android** — the developer menu **already exists on `beta/custom-server`**, as
of the upstream merge of 2026-08-30. Upstream had absorbed the
`feat/vpn-reliability-and-dev-menu` work and extended it, so the merged
version is a superset of that branch's:

| | on `beta/custom-server` |
|---|---|
| `ui/settings/DeveloperScreen.kt` | present, 900 lines (branch had 553) |
| `ui/settings/DeveloperViewModel.kt` | present, 606 lines (branch had 245) |
| `Route.Developer` | `ui/MainNavViewModel.kt:105` |
| nav wiring | `ui/MainNavHost.kt:78` (import), `:1119-1126` (composable) |
| settings entry | `ui/settings/SettingsScreen.kt:1346-1370` |
| strings | all 51 keys present |

**No cherry-pick is needed, and `feat/vpn-reliability-and-dev-menu` must not be
applied** — re-applying its purely-additive `SettingsScreen.kt` hunk would
duplicate the Developer entry, and the branch predates the seedphrase work.
The only remaining Android UI work is adding a Diagnostics section to the
existing `DeveloperScreen`.

Delivery via `ActivityResultContracts.CreateDocument("application/zip")` and a
FileProvider share intent. The FileProvider already exists — authority
`${applicationId}.fileprovider` at `AndroidManifest.xml:75-83`, with
`res/xml/file_paths.xml` already exposing `filesDir` as `logs` and
`cacheDir/share` as `share` — so no manifest or paths change is required.
`ShareLogFileButton.kt` is the share-intent pattern to reuse.

The Android Feedback screen's existing buttons are left in place; this feature
does not change that flow.

## Testing

**Go** (`sdk`), table-driven, in the style of the existing suite:
- inventory: multi-process discovery, source and severity attribution, a
  missing or unreadable source directory
- selection: named subsets, empty selection meaning "all", a name that no
  longer exists on disk
- redaction: stability (same input → same token within one export), isolation
  (different exports → different tokens), idempotence, and that non-sensitive
  text, timestamps and component tags are untouched
- zip structure and manifest shape, including a bundle produced with a source
  deliberately unavailable
- retention: 4 files kept **per process**, verified with two processes writing
  under one root

**Android**: JVM unit tests for the export viewmodel, running under the
existing `:app:testGithubDebugUnitTest`.

**iOS**: `networkTests` coverage for the export service and the
container-unavailable fallback.

## Ops and rollout

Registering the App Group in the Apple Developer portal and regenerating the
provisioning profiles is a prerequisite for **signed** builds to collect
extension logs. It is not a prerequisite for merging or for CI: the beta
workflow builds with `CODE_SIGNING_ALLOWED=NO`, and the degradation path means
a build without the entitlement still exports app-process logs and says so.

Order of work: SDK first (it carries the shared implementation), then Android
(dev menu cherry-pick, then the feature), then iOS (entitlements and log
directories, then the feature), with the `print()` migration last and
separate.

## Risks

- **App Group provisioning** is an external dependency for signed builds. The
  degradation path is what keeps it from being a blocker.
- **Redaction correctness** — over-redaction destroys the evidence the bundle
  exists to carry; under-redaction leaks. This gets the heaviest tests.
- **The `print()` migration** is a ~249-site diff. Kept separate so it cannot
  destabilise the feature.
- **Bundle size** — up to 4 files × 16 MB per process before compression.
  The inventory screen shows the total before the user commits to an export.
