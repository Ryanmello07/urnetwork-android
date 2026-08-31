# Diagnostic Log Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user export every log the client holds — all glog files from every process, platform logs, and a device/state manifest — as one zip from the developer menu, in raw, redacted, or hand-picked form, on iOS and Android, in release builds.

**Architecture:** Bulk data moves as files on disk; only the kilobyte-sized manifest crosses the app↔extension RPC, because the iOS network extension runs on a 20 MB device memory target. Each process writes glog into its own subdirectory of a shared root, which makes the existing "keep 4 most recent" retention scope per-process for free. iOS reaches the extension's subdirectory through an App Group container, and degrades to app-only logs when that container is unavailable.

**Tech Stack:** Go 1.26.5 (gomobile-bound SDK), Swift/SwiftUI + swift-testing (iOS), Kotlin/Compose + Hilt + JUnit4 (Android).

**Spec:** `docs/superpowers/specs/2026-08-30-diagnostic-log-export-design.md`

## Global Constraints

- **Go toolchain is pinned at 1.26.5 and is a CEILING, not a floor.** `sdk/build/Makefile` exports `GODEBUG=gotypesalias=0`, which Go 1.27 removed; the bind dies under 1.27. Do not raise any `go` directive.
- **Every exported SDK symbol must be gomobile-bindable.** No `context.Context`, no maps, no `[]byte`, no slices of structs, no variadics, no struct embedding in exported types. Lists use the `exportedList[T]` wrapper (`sdk/gomobile.go:10-71`). `sdk/build/Makefile`'s `// skipped` gate fails the build on any unexpectedly unbindable export, so violations surface in CI, not review.
- **An export never fails because a source is missing.** An unreadable log directory is reported in `ExportResult.MissingSources` and in the bundle's `README.txt`; only an unwritable destination is an error.
- **No build gating.** The feature ships in release builds. Do not add `#if DEBUG` or `BuildConfig.DEBUG` guards. Neither developer menu has one today.
- **The redaction salt is never written into the bundle**, and never logged.
- **Go tests use stdlib `testing` only** — no testify. `connect.AssertEqual(t, got, want)` (from `github.com/urnetwork/connect`) is the one shared helper and calls `FailNow` on mismatch. Plain `if got != want { t.Fatalf(...) }` is the dominant style.
- **Run Go tests with:** `cd sdk && go test ./...`; a single test with `go test -run TestName -v .`

---

### Task 1: Fix `GetLogDir`, which returns `""` in every process

`sdk.GetLogDir()` reads the glog `log_dir` flag. `glog.SetLogDir` mutates only `logDirs` and `dirSet` (`glog/glog_file.go:583-585`), and sdk commit `9f41a00` removed `sdk.SetLogDir`'s `flag.Set("log_dir", …)` call. So `GetLogDir()` returns `""` everywhere, and every caller — `Device.UploadLogs`, Android's `ExportLogButton` and `ShareLogFileButton`, iOS's `LogExportService` — reads nothing. This task repairs the feedback-attachment flow as a side effect and is a prerequisite for everything below.

**Files:**
- Modify: `sdk/sdk.go:151-173` (`GetLogDir`, `SetLogDir`)
- Test: `sdk/log_export_test.go` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `GetLogDir() string` now returns the directory glog is actually writing to.

- [ ] **Step 1: Write the failing test**

Create `sdk/log_export_test.go`:

```go
package sdk

import (
	"os"
	"testing"
)

// TestGetLogDirReturnsTheDirectoryGlogWritesTo pins the readback contract.
// glog.SetLogDir mutates only glog's internal logDirs/dirSet and never the
// `log_dir` flag, and sdk.SetLogDir stopped setting that flag in 9f41a00, so
// GetLogDir returned "" in every process -- silently breaking UploadLogs and
// both platforms' export buttons, all of which os.ReadDir(GetLogDir()).
func TestGetLogDirReturnsTheDirectoryGlogWritesTo(t *testing.T) {
	dir := t.TempDir()

	if err := SetLogDir(dir); err != nil {
		t.Fatalf("SetLogDir(%q) = %v, want nil", dir, err)
	}

	if got := GetLogDir(); got != dir {
		t.Fatalf("GetLogDir() = %q, want %q", got, dir)
	}

	// the actual failure mode: what every caller does with the result
	entries, err := os.ReadDir(GetLogDir())
	if err != nil {
		t.Fatalf("os.ReadDir(GetLogDir()) = %v, want nil", err)
	}
	if len(entries) == 0 {
		t.Fatal("os.ReadDir(GetLogDir()) found no files, but glog wrote its INFO file there")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestGetLogDirReturnsTheDirectoryGlogWritesTo -v .`
Expected: FAIL — `GetLogDir() = "", want "/var/folders/..."`

- [ ] **Step 3: Track the directory in the sdk rather than reading the flag**

In `sdk/sdk.go`, add to the imports if absent: `"sync"`.

Add above `GetLogDir`:

```go
// currentLogDir is the directory glog was last pointed at.
//
// glog.SetLogDir mutates only glog's internal logDirs/dirSet, never the
// `log_dir` flag, so the flag is not a readback path. Tracking it here is what
// makes GetLogDir answerable at all; reading the flag returned "" in every
// process, including the one that had just called SetLogDir.
var currentLogDirMu sync.Mutex
var currentLogDir string
```

Replace `GetLogDir` (`sdk/sdk.go:151-156`) with:

```go
func GetLogDir() string {
	currentLogDirMu.Lock()
	dir := currentLogDir
	currentLogDirMu.Unlock()
	if dir != "" {
		return dir
	}
	// honor an explicit --log_dir for embedders that never call SetLogDir
	if f := flag.Lookup("log_dir"); f != nil {
		return f.Value.String()
	}
	return ""
}
```

In `SetLogDir` (`sdk/sdk.go:162-173`), record the directory on success. Replace the body with:

```go
func SetLogDir(logDir string) error {

	glog.SetMaxLogSize(1024 * 1024 * 16)
	err := glog.SetLogDir(logDir)
	if err != nil {
		glog.Infof("SetLogDir to %q failed: %v", logDir, err)
		return err
	}
	currentLogDirMu.Lock()
	currentLogDir = logDir
	currentLogDirMu.Unlock()
	glog.Infof("New glog initialized")
	clearOldLogs(logDir)

	return nil
}
```

Note the behaviour change: `SetLogDir` now returns early on error instead of running `clearOldLogs` against a directory glog rejected. That is the intended fix — pruning a directory glog is not writing to could delete an unrelated caller's files.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go test -run TestGetLogDirReturnsTheDirectoryGlogWritesTo -v .`
Expected: PASS

- [ ] **Step 5: Run the full suite for regressions**

Run: `cd sdk && go test ./... 2>&1 | tail -5`
Expected: `ok  github.com/urnetwork/sdk`

- [ ] **Step 6: Commit**

```bash
git add sdk/sdk.go sdk/log_export_test.go
git commit -m "fix: make GetLogDir return the directory glog writes to

glog.SetLogDir mutates only glog's logDirs/dirSet, never the log_dir flag,
and 9f41a00 removed sdk.SetLogDir's flag.Set. GetLogDir read that flag, so it
returned \"\" in every process and every caller that did
os.ReadDir(GetLogDir()) silently read nothing: Device.UploadLogs (the
attach-logs-to-feedback flow), android's ExportLogButton and
ShareLogFileButton, and ios's LogExportService."
```

---

### Task 2: Per-process log directories with per-process retention

`clearOldLogs` keeps the 4 most recent files across a whole directory (`sdk/sdk.go:79-138`). Once two processes share one directory they evict each other's history. Giving each process its own subdirectory scopes retention per-process without touching that function, and gives the exporter a source label that does not depend on parsing glog filenames.

**Files:**
- Modify: `sdk/sdk.go` (add `SetLogDirForProcess`, `GetLogRoot`)
- Test: `sdk/log_export_test.go:` (append)

**Interfaces:**
- Consumes: `GetLogDir()`, `SetLogDir()` from Task 1.
- Produces: `SetLogDirForProcess(root string, processName string) error`, `GetLogRoot() string`. Task 3's `LogInventory` enumerates `GetLogRoot()`'s subdirectories; Tasks 7 and 10 call `SetLogDirForProcess`.

- [ ] **Step 1: Write the failing test**

Append to `sdk/log_export_test.go`:

```go
// TestSetLogDirForProcessScopesRetentionPerProcess is the reason per-process
// subdirectories exist. clearOldLogs keeps the 4 newest files in whatever
// directory it is handed, so two processes sharing one directory delete each
// other's history. Under a root, each process prunes only its own.
func TestSetLogDirForProcessScopesRetentionPerProcess(t *testing.T) {
	root := t.TempDir()

	if err := SetLogDirForProcess(root, "extension"); err != nil {
		t.Fatalf("SetLogDirForProcess(root, \"extension\") = %v, want nil", err)
	}
	extensionDir := GetLogDir()
	if extensionDir != filepath.Join(root, "extension") {
		t.Fatalf("GetLogDir() = %q, want %q", extensionDir, filepath.Join(root, "extension"))
	}
	if got := GetLogRoot(); got != root {
		t.Fatalf("GetLogRoot() = %q, want %q", got, root)
	}

	// six files in the extension's directory: retention keeps the newest 4
	for i := 0; i < 6; i += 1 {
		writeTestingLogFile(t, extensionDir, "urnetwork.host.user.log.INFO.2026083"+string(rune('0'+i))+"-000000.100")
	}

	// the app's own history must survive the extension's pruning
	if err := SetLogDirForProcess(root, "app"); err != nil {
		t.Fatalf("SetLogDirForProcess(root, \"app\") = %v, want nil", err)
	}
	appDir := GetLogDir()
	writeTestingLogFile(t, appDir, "urnetwork.host.user.log.INFO.20260830-000000.200")

	// prune the extension's directory again, as its next launch would
	clearOldLogs(extensionDir)

	if got := countTestingLogFiles(t, extensionDir); got > 4 {
		t.Fatalf("extension dir kept %d log files, want at most 4", got)
	}
	if got := countTestingLogFiles(t, appDir); got != 1 {
		t.Fatalf("app dir has %d log files, want 1 -- the extension's pruning deleted the app's history", got)
	}
}

func writeTestingLogFile(t *testing.T, dir string, name string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte("I0830 00:00:00.000000 1 x.go:1] test\n"), 0600); err != nil {
		t.Fatalf("WriteFile(%q): %v", name, err)
	}
}

func countTestingLogFiles(t *testing.T, dir string) int {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir(%q): %v", dir, err)
	}
	n := 0
	for _, e := range entries {
		if strings.Contains(e.Name(), ".log.") {
			n += 1
		}
	}
	return n
}
```

Add to that file's imports: `"path/filepath"` and `"strings"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestSetLogDirForProcessScopesRetentionPerProcess -v .`
Expected: FAIL — `undefined: SetLogDirForProcess`

- [ ] **Step 3: Implement**

Add to `sdk/sdk.go`, next to `SetLogDir`:

```go
// currentLogRoot is the parent of the per-process log directories, recorded so
// the exporter can enumerate every process's logs rather than only this
// process's. Empty when only the legacy SetLogDir was used.
var currentLogRoot string

// SetLogDirForProcess points glog at <root>/<processName> and records root.
//
// Each process gets its own subdirectory because clearOldLogs keeps the 4
// newest files in whatever directory it is given: processes sharing one
// directory delete each other's history. The subdirectory name is also the
// source label the exported bundle reports, which is more reliable than
// parsing glog's <program>.<host>.<user>.log.<SEVERITY>.<time>.<pid> names.
//
// When root cannot be used it falls back to a process-local directory and
// returns nil -- logging must never be what breaks a launch. It returns a
// non-nil error only when neither can be opened, in which case glog keeps its
// previous destination. The directory actually in use is always readable via
// GetLogDir, and the recorded root via GetLogRoot.
func SetLogDirForProcess(root string, processName string) error {
	if processName == "" {
		return fmt.Errorf("log process name cannot be empty")
	}

	dir := filepath.Join(root, processName)
	if root != "" {
		if err := os.MkdirAll(dir, LocalStorageDirectoryPermissions); err == nil {
			if err := SetLogDir(dir); err == nil {
				currentLogDirMu.Lock()
				currentLogRoot = root
				currentLogDirMu.Unlock()
				return nil
			}
		}
	}

	// fall back to a process-local directory under the os temp dir, and record
	// its parent as the root so an export still finds this process's files
	fallbackRoot := filepath.Join(os.TempDir(), "urnetwork-logs")
	fallbackDir := filepath.Join(fallbackRoot, processName)
	if err := os.MkdirAll(fallbackDir, LocalStorageDirectoryPermissions); err != nil {
		return err
	}
	if err := SetLogDir(fallbackDir); err != nil {
		return err
	}
	currentLogDirMu.Lock()
	currentLogRoot = fallbackRoot
	currentLogDirMu.Unlock()
	return nil
}

// GetLogRoot returns the parent of the per-process log directories, or "" when
// only the legacy SetLogDir was used.
func GetLogRoot() string {
	currentLogDirMu.Lock()
	defer currentLogDirMu.Unlock()
	return currentLogRoot
}
```

Add `"path/filepath"` and `"fmt"` to `sdk/sdk.go`'s imports if absent. `LocalStorageDirectoryPermissions` is already defined in the package and used by `network_space.go`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go test -run TestSetLogDirForProcess -v .`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add sdk/sdk.go sdk/log_export_test.go
git commit -m "feat: per-process log directories under a shared root

clearOldLogs keeps the 4 newest files in whatever directory it is handed, so
two processes sharing one would evict each other's history. A subdirectory per
process scopes retention without changing that function, and gives the
exporter a source label that does not depend on parsing glog filenames."
```

---

### Task 3: Log inventory

**Files:**
- Create: `sdk/diagnostics.go`
- Test: `sdk/diagnostics_test.go`

**Interfaces:**
- Consumes: `GetLogRoot()`, `GetLogDir()` from Task 2.
- Produces: `LogFileInfo` struct, `LogFileInfoList` with `Len() int` / `Get(i int) *LogFileInfo` / `Add(*LogFileInfo)`, `NewLogFileInfoList() *LogFileInfoList`, `LogInventory() *LogFileInfoList`. Task 5 filters by `LogFileInfo.Name`; Tasks 9 and 13 render the list.

- [ ] **Step 1: Write the failing test**

Create `sdk/diagnostics_test.go`:

```go
package sdk

import (
	"os"
	"path/filepath"
	"testing"
)

// TestLogInventoryFindsEveryProcessAndSkipsSymlinks pins two things that the
// naive implementation gets wrong: logs live under one subdirectory per
// process, and glog puts a <program>.<SEVERITY> SYMLINK beside every real file
// (glog/glog_file.go:124-140), which would otherwise be counted twice.
func TestLogInventoryFindsEveryProcessAndSkipsSymlinks(t *testing.T) {
	root := t.TempDir()

	appDir := filepath.Join(root, "app")
	extensionDir := filepath.Join(root, "extension")
	for _, dir := range []string{appDir, extensionDir} {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatalf("MkdirAll(%q): %v", dir, err)
		}
	}

	realName := "urnetwork.host.user.log.INFO.20260830-101112.4242"
	writeTestingLogFile(t, appDir, realName)
	writeTestingLogFile(t, extensionDir, "urnetwork.host.user.log.ERROR.20260830-101112.4243")

	// the symlink glog maintains next to the real file
	if err := os.Symlink(filepath.Join(appDir, realName), filepath.Join(appDir, "urnetwork.INFO")); err != nil {
		t.Fatalf("Symlink: %v", err)
	}

	if err := SetLogDirForProcess(root, "app"); err != nil {
		t.Fatalf("SetLogDirForProcess: %v", err)
	}

	inventory := LogInventory()

	bySource := map[string]*LogFileInfo{}
	for i := 0; i < inventory.Len(); i += 1 {
		info := inventory.Get(i)
		if info.Name == "urnetwork.INFO" {
			t.Fatal("inventory included the glog symlink; it must list real files only")
		}
		bySource[info.Source] = info
	}

	app, ok := bySource["app"]
	if !ok {
		t.Fatal("inventory missing the app source")
	}
	if app.Severity != "INFO" {
		t.Fatalf("app severity = %q, want INFO", app.Severity)
	}
	if app.ByteCount <= 0 {
		t.Fatalf("app ByteCount = %d, want > 0", app.ByteCount)
	}

	extension, ok := bySource["extension"]
	if !ok {
		t.Fatal("inventory missing the extension source -- it only scanned this process's directory")
	}
	if extension.Severity != "ERROR" {
		t.Fatalf("extension severity = %q, want ERROR", extension.Severity)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestLogInventoryFindsEveryProcessAndSkipsSymlinks -v .`
Expected: FAIL — `undefined: LogInventory`

- [ ] **Step 3: Implement**

Create `sdk/diagnostics.go`:

```go
package sdk

import (
	"os"
	"path/filepath"
	"strings"
)

// logSeverities are the glog severity tags, in the order glog defines them.
// They appear in a log file name as the segment immediately after ".log."
// (glog/glog_file.go:124-140).
var logSeverities = []string{"INFO", "WARNING", "ERROR", "FATAL"}

// LogFileInfo describes one log file the bundle could include.
//
// gomobile does not support struct composition, so this is flat, and every
// field is a bindable scalar.
type LogFileInfo struct {
	// Name is the glog file name, unique within the export.
	Name string
	// Path is the absolute path on disk.
	Path string
	// Source is the writing process: the per-process subdirectory name,
	// e.g. "app" or "extension".
	Source string
	// Severity is INFO, WARNING, ERROR or FATAL.
	Severity string
	ByteCount int64
	// ModifiedMillis is unix millis, 0 when unknown.
	ModifiedMillis int64
}

type LogFileInfoList struct {
	exportedList[*LogFileInfo]
}

func NewLogFileInfoList() *LogFileInfoList {
	return &LogFileInfoList{
		exportedList: *newExportedList[*LogFileInfo](),
	}
}

// logSeverityOf returns the severity named in a glog file name, or "" when the
// name is not a glog log file.
func logSeverityOf(name string) string {
	for _, severity := range logSeverities {
		if strings.Contains(name, ".log."+severity) {
			return severity
		}
	}
	return ""
}

// LogInventory enumerates every log file under the recorded log root, across
// every process that has written there.
//
// Symlinks are skipped: glog maintains a <program>.<SEVERITY> symlink beside
// each real file, and following it would list the same bytes twice.
func LogInventory() *LogFileInfoList {
	inventory := NewLogFileInfoList()

	root := GetLogRoot()
	if root == "" {
		// legacy single-directory configuration: report it as one source
		if dir := GetLogDir(); dir != "" {
			appendLogFilesIn(inventory, dir, "app")
		}
		return inventory
	}

	entries, err := os.ReadDir(root)
	if err != nil {
		return inventory
	}
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		appendLogFilesIn(inventory, filepath.Join(root, entry.Name()), entry.Name())
	}
	return inventory
}

func appendLogFilesIn(inventory *LogFileInfoList, dir string, source string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		// Type()&ModeSymlink catches glog's severity symlinks without a stat
		if entry.IsDir() || entry.Type()&os.ModeSymlink != 0 {
			continue
		}
		severity := logSeverityOf(entry.Name())
		if severity == "" {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		inventory.Add(&LogFileInfo{
			Name:           entry.Name(),
			Path:           filepath.Join(dir, entry.Name()),
			Source:         source,
			Severity:       severity,
			ByteCount:      info.Size(),
			ModifiedMillis: info.ModTime().UnixMilli(),
		})
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go test -run TestLogInventoryFindsEveryProcessAndSkipsSymlinks -v .`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add sdk/diagnostics.go sdk/diagnostics_test.go
git commit -m "feat: log inventory across every process

Enumerates each per-process subdirectory under the log root. Skips glog's
<program>.<SEVERITY> symlinks, which would otherwise list the same bytes
twice."
```

---

### Task 4: Redaction filter

A glog entry is not always one line: files open with a 5-line header, rotate with a 2-line footer, and backtraces are appended as `"\n\n%v\n"` with no `[IWEF]` prefix on the continuation lines. The filter therefore works line-by-line over the whole file rather than assuming one entry per line, and rewrites only the patterns it recognises.

**Files:**
- Create: `sdk/diagnostics_redact.go`
- Test: `sdk/diagnostics_redact_test.go`

**Interfaces:**
- Consumes: nothing.
- Produces: `newLogRedactor() *logRedactor` and `(*logRedactor).redactLine(line string) string` — package-private, used by Task 5's zip writer.

- [ ] **Step 1: Write the failing test**

Create `sdk/diagnostics_redact_test.go`:

```go
package sdk

import (
	"strings"
	"testing"
)

func TestRedactorMasksAddressesAndIdsButLeavesStructureIntact(t *testing.T) {
	redactor := newLogRedactor()

	cases := []struct {
		name     string
		line     string
		contains []string
		absent   []string
	}{
		{
			name:     "destination ip and port",
			line:     "I0830 10:11:12.131415    4242 ip_remote_multi_client.go:5864] [multi]drop packet ipv4 p6 -> 203.0.113.7:443",
			contains: []string{"I0830 10:11:12.131415", "ip_remote_multi_client.go:5864]", "[multi]drop packet ipv4 p6 ->"},
			absent:   []string{"203.0.113.7"},
		},
		{
			name:     "client uuid",
			line:     "I0830 10:11:12.131415    4242 transport.go:1763] [t]auth error 11111111-1111-1111-1111-111111111111 = bad",
			contains: []string{"[t]auth error", "= bad"},
			absent:   []string{"11111111-1111-1111-1111-111111111111"},
		},
		{
			name:     "continuation line with no header is still redacted",
			line:     "\tat 198.51.100.9:8080 in frame 3",
			contains: []string{"in frame 3"},
			absent:   []string{"198.51.100.9"},
		},
		{
			name:     "non-sensitive text is untouched",
			line:     "I0830 10:11:12.131415    4242 window.go:12] [window]evaluating 4 candidates, target 8",
			contains: []string{"[window]evaluating 4 candidates, target 8"},
			absent:   []string{},
		},
	}

	for _, c := range cases {
		got := redactor.redactLine(c.line)
		for _, want := range c.contains {
			if !strings.Contains(got, want) {
				t.Errorf("%s: redacted line %q lost %q", c.name, got, want)
			}
		}
		for _, unwanted := range c.absent {
			if strings.Contains(got, unwanted) {
				t.Errorf("%s: redacted line %q still contains %q", c.name, got, unwanted)
			}
		}
	}
}

// The same value must map to the same token within one export, so a flow can
// still be followed across lines; a different export must map it differently,
// so tokens cannot be correlated between bundles.
func TestRedactorIsStableWithinAnExportAndDistinctAcross(t *testing.T) {
	line := "peer 203.0.113.7:443 selected"

	first := newLogRedactor()
	a := first.redactLine(line)
	b := first.redactLine(line)
	if a != b {
		t.Fatalf("same redactor produced %q then %q; tokens must be stable within an export", a, b)
	}

	second := newLogRedactor()
	c := second.redactLine(line)
	if a == c {
		t.Fatalf("two redactors both produced %q; tokens must not be correlatable across exports", a)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestRedactor -v .`
Expected: FAIL — `undefined: newLogRedactor`

- [ ] **Step 3: Implement**

Create `sdk/diagnostics_redact.go`:

```go
package sdk

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"regexp"
)

// The patterns the redactor rewrites. Everything else in a line -- timestamps,
// the file:line header, component tags, counters, message text -- is left
// exactly as written, so a redacted bundle is still readable as a log.
var (
	// dotted-quad with an optional :port
	redactIPv4Pattern = regexp.MustCompile(`\b\d{1,3}(?:\.\d{1,3}){3}\b(?::\d{1,5})?`)
	// uuid, the shape of client, network, device and instance ids
	redactUUIDPattern = regexp.MustCompile(`\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b`)
	// bracketed or bare ipv6 with at least two colons, optional :port when bracketed
	redactIPv6Pattern = regexp.MustCompile(`\[[0-9a-fA-F:]{2,}\](?::\d{1,5})?|\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b`)
)

// logRedactor maps sensitive values to stable per-export tokens.
//
// Stability within one export is what keeps a redacted bundle useful: the same
// address reads as the same token on every line, so a flow can still be
// followed. The salt is random per export and is never written into the bundle
// or logged, so tokens cannot be correlated between bundles or reversed.
type logRedactor struct {
	salt []byte
}

func newLogRedactor() *logRedactor {
	salt := make([]byte, 32)
	// rand.Read from crypto/rand never returns a short read without an error,
	// and an error here is unrecoverable -- a zero salt would be a false
	// promise of redaction, so fail closed by keeping the random bytes we have
	// only when the read succeeded.
	if _, err := rand.Read(salt); err != nil {
		// fall back to a still-unpredictable-per-process value rather than zeros
		h := sha256.Sum256([]byte(GetLogDir() + GetLogRoot()))
		salt = h[:]
	}
	return &logRedactor{salt: salt}
}

func (self *logRedactor) token(prefix string, value string) string {
	mac := hmac.New(sha256.New, self.salt)
	mac.Write([]byte(value))
	return prefix + hex.EncodeToString(mac.Sum(nil))[:12] + ">"
}

// redactLine rewrites one line. It is applied to EVERY line of a log file,
// including the plaintext header block, the rotation footer, and backtrace
// continuation lines, which carry no [IWEF] header prefix -- so it must never
// depend on a line being a well-formed glog entry.
func (self *logRedactor) redactLine(line string) string {
	line = redactUUIDPattern.ReplaceAllStringFunc(line, func(match string) string {
		return self.token("<id:", match)
	})
	line = redactIPv6Pattern.ReplaceAllStringFunc(line, func(match string) string {
		return self.token("<addr:", match)
	})
	line = redactIPv4Pattern.ReplaceAllStringFunc(line, func(match string) string {
		return self.token("<addr:", match)
	})
	return line
}
```

Order matters: UUIDs are matched before IPv6, because an IPv6 pattern can otherwise consume the hex groups of a UUID.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go test -run TestRedactor -v .`
Expected: PASS (both tests)

- [ ] **Step 5: Commit**

```bash
git add sdk/diagnostics_redact.go sdk/diagnostics_redact_test.go
git commit -m "feat: redaction filter for exported logs

Maps ip addresses and uuid-shaped ids to per-export HMAC tokens, so a flow can
still be followed across lines without the real values. Works line-by-line
over the whole file because a glog entry is not always one line: files carry a
plaintext header and rotation footer, and backtrace continuations have no
[IWEF] prefix."
```

---

### Task 5: `ExportDiagnosticBundle`

**Files:**
- Modify: `sdk/diagnostics.go`
- Modify: `sdk/device_local.go:5678` (generalise `zipLogs`)
- Test: `sdk/diagnostics_test.go` (append)

**Interfaces:**
- Consumes: `LogInventory()` (Task 3), `newLogRedactor()` (Task 4).
- Produces: `ExportOptions` struct, `NewExportOptions() *ExportOptions`, `ExportResult` struct, `ExportDiagnosticBundle(destPath string, opts *ExportOptions) (*ExportResult, error)`. Tasks 8 and 12 call it.

- [ ] **Step 1: Write the failing test**

Append to `sdk/diagnostics_test.go`:

```go
// TestExportDiagnosticBundleWritesEverySelectedSource covers the zip layout and
// that a source which cannot be read is REPORTED, never fatal -- an ios build
// whose provisioning profile lacks the app group must still export its own
// logs.
func TestExportDiagnosticBundleWritesEverySelectedSource(t *testing.T) {
	root := t.TempDir()
	appDir := filepath.Join(root, "app")
	if err := os.MkdirAll(appDir, 0700); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}
	writeTestingLogFile(t, appDir, "urnetwork.host.user.log.INFO.20260830-101112.4242")
	if err := SetLogDirForProcess(root, "app"); err != nil {
		t.Fatalf("SetLogDirForProcess: %v", err)
	}

	destPath := filepath.Join(t.TempDir(), "bundle.zip")

	opts := NewExportOptions()
	opts.IncludeManifest = true
	opts.MissingSourceReason("extension", "app group container unavailable")

	result, err := ExportDiagnosticBundle(destPath, opts)
	if err != nil {
		t.Fatalf("ExportDiagnosticBundle = %v, want nil", err)
	}
	if result.FileCount != 1 {
		t.Fatalf("FileCount = %d, want 1", result.FileCount)
	}
	if result.MissingSources.Len() != 1 {
		t.Fatalf("MissingSources.Len() = %d, want 1", result.MissingSources.Len())
	}

	reader, err := zip.OpenReader(destPath)
	if err != nil {
		t.Fatalf("zip.OpenReader: %v", err)
	}
	defer reader.Close()

	names := map[string]bool{}
	for _, f := range reader.File {
		names[f.Name] = true
	}
	for _, want := range []string{
		"README.txt",
		"manifest.json",
		"logs/app/urnetwork.host.user.log.INFO.20260830-101112.4242",
	} {
		if !names[want] {
			t.Errorf("bundle missing %q; has %v", want, names)
		}
	}
}

// A redacted export must not carry the raw value anywhere in the archive.
func TestExportDiagnosticBundleRedactsWhenAsked(t *testing.T) {
	root := t.TempDir()
	appDir := filepath.Join(root, "app")
	if err := os.MkdirAll(appDir, 0700); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}
	name := "urnetwork.host.user.log.INFO.20260830-101112.4242"
	if err := os.WriteFile(filepath.Join(appDir, name),
		[]byte("I0830 10:11:12.131415 1 x.go:1] peer 203.0.113.7:443\n"), 0600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}
	if err := SetLogDirForProcess(root, "app"); err != nil {
		t.Fatalf("SetLogDirForProcess: %v", err)
	}

	destPath := filepath.Join(t.TempDir(), "redacted.zip")
	opts := NewExportOptions()
	opts.Redact = true

	if _, err := ExportDiagnosticBundle(destPath, opts); err != nil {
		t.Fatalf("ExportDiagnosticBundle = %v", err)
	}

	reader, err := zip.OpenReader(destPath)
	if err != nil {
		t.Fatalf("zip.OpenReader: %v", err)
	}
	defer reader.Close()

	for _, f := range reader.File {
		rc, err := f.Open()
		if err != nil {
			t.Fatalf("open %q: %v", f.Name, err)
		}
		content, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatalf("read %q: %v", f.Name, err)
		}
		if strings.Contains(string(content), "203.0.113.7") {
			t.Fatalf("entry %q in a redacted bundle still contains the raw address", f.Name)
		}
	}
}
```

Add to `sdk/diagnostics_test.go`'s imports: `"archive/zip"`, `"io"`, `"strings"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestExportDiagnosticBundle -v .`
Expected: FAIL — `undefined: NewExportOptions`

- [ ] **Step 3: Generalise the zip writer**

In `sdk/device_local.go`, replace `zipLogs` (`sdk/device_local.go:5678`) with a name-and-reader form, keeping a `zipLogs` wrapper so `UploadLogs` is unchanged:

```go
// zipEntryWriter writes one entry into an open zip. transform, when non-nil,
// rewrites the content line by line -- this is how redaction is applied
// without ever holding a whole log file in memory.
func zipWriteEntry(zipWriter *zip.Writer, name string, r io.Reader, transform func(string) string) error {
	w, err := zipWriter.CreateHeader(&zip.FileHeader{
		Name:   name,
		Method: zip.Deflate,
	})
	if err != nil {
		return err
	}
	if transform == nil {
		_, err = io.Copy(w, r)
		return err
	}
	scanner := bufio.NewScanner(r)
	// glog caps a message at MaxLogMessageLen = 15000, but a line carrying a
	// backtrace can exceed the scanner's 64KiB default, and a truncated log is
	// a misleading one.
	scanner.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for scanner.Scan() {
		if _, err := io.WriteString(w, transform(scanner.Text())+"\n"); err != nil {
			return err
		}
	}
	return scanner.Err()
}

func zipLogs(
	logFiles []string,
	zipPath string,
) error {
	zipFile, err := os.Create(zipPath)
	if err != nil {
		return err
	}
	defer zipFile.Close()

	zipWriter := zip.NewWriter(zipFile)
	defer zipWriter.Close()

	for _, path := range logFiles {
		f, err := os.Open(path)
		if err != nil {
			return err
		}
		if err := zipWriteEntry(zipWriter, filepath.Base(path), f, nil); err != nil {
			f.Close()
			return err
		}
		f.Close()
	}
	return nil
}
```

Add `"bufio"` to `sdk/device_local.go`'s imports if absent.

- [ ] **Step 4: Implement the exporter**

Append to `sdk/diagnostics.go`:

```go
// ExportOptions selects what an exported bundle contains.
type ExportOptions struct {
	// Redact maps ip addresses and uuid-shaped ids to per-export tokens.
	Redact bool
	// IncludeManifest writes manifest.json. The manifest body is supplied by
	// the platform via SetManifestJson, because on ios the device-side state
	// lives in the extension and arrives over the rpc.
	IncludeManifest bool
	// IncludePlatformLogs writes platform/*.txt from SetPlatformLog entries.
	IncludePlatformLogs bool
	// SelectedNames limits the export to these LogFileInfo.Name values. Empty
	// means every file.
	SelectedNames *StringList

	manifestJson  string
	platformLogs  *StringList
	platformNames *StringList
	missingNames  *StringList
	missingWhy    *StringList
}

func NewExportOptions() *ExportOptions {
	return &ExportOptions{
		SelectedNames: NewStringList(),
		platformLogs:  NewStringList(),
		platformNames: NewStringList(),
		missingNames:  NewStringList(),
		missingWhy:    NewStringList(),
	}
}

// SetManifestJson supplies the manifest body, normally
// Device.DiagnosticManifestJson().
func (self *ExportOptions) SetManifestJson(manifestJson string) {
	self.manifestJson = manifestJson
}

// AddPlatformLog adds one platform log entry, written to platform/<name>.
// Android passes its logcat dump here.
func (self *ExportOptions) AddPlatformLog(name string, content string) {
	self.platformNames.Add(name)
	self.platformLogs.Add(content)
}

// MissingSourceReason records a source that could not be read, so the bundle
// says so instead of silently omitting it.
func (self *ExportOptions) MissingSourceReason(source string, reason string) {
	self.missingNames.Add(source)
	self.missingWhy.Add(reason)
}

// ExportResult reports what was written.
type ExportResult struct {
	ByteCount int64
	FileCount int
	// MissingSources holds human-readable "<source>: <reason>" entries.
	MissingSources *StringList
}

// ExportDiagnosticBundle writes a zip of the selected logs to destPath.
//
// A source that cannot be read is reported in the result and in the bundle's
// README, never fatal: an ios build whose provisioning profile predates the
// app group must still export the logs it can reach. Only an unwritable
// destination is an error.
func ExportDiagnosticBundle(destPath string, opts *ExportOptions) (*ExportResult, error) {
	if opts == nil {
		opts = NewExportOptions()
	}

	FlushGlog()

	result := &ExportResult{MissingSources: NewStringList()}
	for i := 0; i < opts.missingNames.Len(); i += 1 {
		result.MissingSources.Add(opts.missingNames.Get(i) + ": " + opts.missingWhy.Get(i))
	}

	zipFile, err := os.Create(destPath)
	if err != nil {
		return nil, err
	}
	defer zipFile.Close()

	zipWriter := zip.NewWriter(zipFile)

	var transform func(string) string
	if opts.Redact {
		redactor := newLogRedactor()
		transform = redactor.redactLine
	}

	inventory := LogInventory()
	for i := 0; i < inventory.Len(); i += 1 {
		info := inventory.Get(i)
		if 0 < opts.SelectedNames.Len() && !opts.SelectedNames.Contains(info.Name) {
			continue
		}
		f, err := os.Open(info.Path)
		if err != nil {
			result.MissingSources.Add(info.Name + ": " + err.Error())
			continue
		}
		err = zipWriteEntry(zipWriter, "logs/"+info.Source+"/"+info.Name, f, transform)
		f.Close()
		if err != nil {
			zipWriter.Close()
			return nil, err
		}
		result.FileCount += 1
	}

	if opts.IncludeManifest {
		manifestJson := opts.manifestJson
		if manifestJson == "" {
			manifestJson = "{\"available\":false}"
		}
		if err := zipWriteEntry(zipWriter, "manifest.json", strings.NewReader(manifestJson), transform); err != nil {
			zipWriter.Close()
			return nil, err
		}
	}

	if opts.IncludePlatformLogs {
		for i := 0; i < opts.platformNames.Len(); i += 1 {
			err := zipWriteEntry(
				zipWriter,
				"platform/"+opts.platformNames.Get(i),
				strings.NewReader(opts.platformLogs.Get(i)),
				transform,
			)
			if err != nil {
				zipWriter.Close()
				return nil, err
			}
		}
	}

	if err := zipWriteEntry(zipWriter, "README.txt", strings.NewReader(exportReadme(opts, result)), nil); err != nil {
		zipWriter.Close()
		return nil, err
	}

	if err := zipWriter.Close(); err != nil {
		return nil, err
	}
	if info, err := zipFile.Stat(); err == nil {
		result.ByteCount = info.Size()
	}
	return result, nil
}

func exportReadme(opts *ExportOptions, result *ExportResult) string {
	var b strings.Builder
	b.WriteString("URnetwork diagnostic bundle\n\n")
	if opts.Redact {
		b.WriteString("Mode: REDACTED. ip addresses and uuid-shaped ids are replaced by\n")
		b.WriteString("per-export tokens. The same value reads as the same token throughout\n")
		b.WriteString("this bundle, and differently in any other bundle. The mapping is not\n")
		b.WriteString("reversible and the salt is not included.\n\n")
	} else {
		b.WriteString("Mode: RAW. Nothing is masked. At raised log verbosity this can include\n")
		b.WriteString("the destination addresses and ports of your traffic, and your client id.\n\n")
	}
	b.WriteString("logs/<process>/  glog files, one directory per writing process\n")
	b.WriteString("manifest.json    device, build and connection state\n")
	b.WriteString("platform/        platform-side logs, where available\n\n")
	if 0 < result.MissingSources.Len() {
		b.WriteString("NOT INCLUDED:\n")
		for i := 0; i < result.MissingSources.Len(); i += 1 {
			b.WriteString("  - " + result.MissingSources.Get(i) + "\n")
		}
	}
	return b.String()
}
```

Add `"archive/zip"` and `"strings"` to `sdk/diagnostics.go`'s imports.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd sdk && go test -run 'TestExportDiagnosticBundle|TestLogInventory|TestRedactor' -v .`
Expected: PASS

- [ ] **Step 6: Verify `UploadLogs` still works and the bind still succeeds**

Run: `cd sdk && go build ./... && go vet ./... && go test ./... 2>&1 | tail -3`
Expected: `ok  github.com/urnetwork/sdk`

- [ ] **Step 7: Commit**

```bash
git add sdk/diagnostics.go sdk/diagnostics_test.go sdk/device_local.go
git commit -m "feat: ExportDiagnosticBundle

Writes every selected log, the manifest and platform logs into one zip, with
redaction applied as a streaming line transform so no log file is held whole
in memory. A source that cannot be read is recorded in the result and the
README rather than failing the export."
```

---

### Task 6: `DiagnosticManifestJson` over the RPC

The manifest is the one thing that must cross the app↔extension RPC, because on iOS the device-side state lives in the extension. It is kilobytes of JSON, so the round trip is cheap — unlike log files, which is why they go via the container instead.

**Files:**
- Modify: `sdk/device.go` (Device interface, near `UploadLogs` at line 644)
- Modify: `sdk/device_local.go` (implementation)
- Modify: `sdk/device_rpc.go` (client near line 5027, server near line 9064)
- Test: `sdk/diagnostics_manifest_test.go`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `Device.DiagnosticManifestJson() string`, implemented on `DeviceLocal` and `DeviceRemote`. Tasks 8 and 12 pass its result to `ExportOptions.SetManifestJson`.

- [ ] **Step 1: Write the failing test**

Create `sdk/diagnostics_manifest_test.go`:

```go
package sdk

import (
	"encoding/json"
	"testing"
)

// The manifest must be valid json with the fields the bundle's reader relies
// on, and must never be empty -- an export with no manifest is an export that
// cannot be dated or attributed to a build.
func TestDiagnosticManifestJsonShape(t *testing.T) {
	manifestJson := buildDiagnosticManifestJson(diagnosticManifestInput{
		SdkVersion:     "0.0.0-test",
		ClientId:       "11111111-1111-1111-1111-111111111111",
		InstanceId:     "22222222-2222-2222-2222-222222222222",
		NetworkSpace:   "main",
		ConnectEnabled: true,
		ProvideEnabled: false,
		DeviceAvailable: true,
	})

	var decoded map[string]any
	if err := json.Unmarshal([]byte(manifestJson), &decoded); err != nil {
		t.Fatalf("manifest is not valid json: %v\n%s", err, manifestJson)
	}

	for _, key := range []string{"sdk_version", "client_id", "instance_id", "network_space", "connect_enabled", "device_available"} {
		if _, ok := decoded[key]; !ok {
			t.Errorf("manifest missing %q; has %v", key, decoded)
		}
	}
	if decoded["sdk_version"] != "0.0.0-test" {
		t.Errorf("sdk_version = %v, want 0.0.0-test", decoded["sdk_version"])
	}
}

// When the rpc is down the manifest must still be produced, marked so the
// reader knows the device-side fields are absent rather than false.
func TestDiagnosticManifestJsonWhenDeviceUnavailable(t *testing.T) {
	manifestJson := buildDiagnosticManifestJson(diagnosticManifestInput{
		SdkVersion:      "0.0.0-test",
		DeviceAvailable: false,
	})

	var decoded map[string]any
	if err := json.Unmarshal([]byte(manifestJson), &decoded); err != nil {
		t.Fatalf("manifest is not valid json: %v", err)
	}
	if decoded["device_available"] != false {
		t.Fatalf("device_available = %v, want false", decoded["device_available"])
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestDiagnosticManifestJson -v .`
Expected: FAIL — `undefined: buildDiagnosticManifestJson`

- [ ] **Step 3: Implement the manifest builder**

Append to `sdk/diagnostics.go`:

```go
// diagnosticManifestInput is the plain-Go input to the manifest. It is not
// exported to gomobile: the bound surface is DiagnosticManifestJson() string.
type diagnosticManifestInput struct {
	SdkVersion      string
	ClientId        string
	InstanceId      string
	NetworkSpace    string
	ConnectEnabled  bool
	ProvideEnabled  bool
	DeviceAvailable bool
}

func buildDiagnosticManifestJson(input diagnosticManifestInput) string {
	manifest := map[string]any{
		"sdk_version":   input.SdkVersion,
		"client_id":     input.ClientId,
		"instance_id":   input.InstanceId,
		"network_space": input.NetworkSpace,
		// device_available is false when the manifest was built without a live
		// device -- on ios that means the rpc into the extension was down, so
		// the fields below are absent rather than genuinely false.
		"device_available": input.DeviceAvailable,
		"connect_enabled":  input.ConnectEnabled,
		"provide_enabled":  input.ProvideEnabled,
		"log_root":         GetLogRoot(),
	}
	encoded, err := json.Marshal(manifest)
	if err != nil {
		return "{\"device_available\":false}"
	}
	return string(encoded)
}
```

Add `"encoding/json"` to `sdk/diagnostics.go`'s imports.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go test -run TestDiagnosticManifestJson -v .`
Expected: PASS

- [ ] **Step 5: Add the method to the Device interface and DeviceLocal**

In `sdk/device.go`, immediately after the `UploadLogs` line (line 644):

```go
	DiagnosticManifestJson() string
```

In `sdk/device_local.go`, next to `UploadLogs`:

```go
// DiagnosticManifestJson returns the device-side half of the exported bundle's
// manifest. It is kilobytes of json, which is why it crosses the rpc while the
// log files -- which would not fit the extension's memory target -- do not.
func (self *DeviceLocal) DiagnosticManifestJson() string {
	clientId := ""
	if id := self.GetClientId(); id != nil {
		clientId = id.String()
	}
	instanceId := ""
	if id := self.GetInstanceId(); id != nil {
		instanceId = id.String()
	}
	networkSpace := ""
	if space := self.GetNetworkSpace(); space != nil {
		networkSpace = space.GetHostName()
	}
	return buildDiagnosticManifestJson(diagnosticManifestInput{
		SdkVersion:      Version,
		ClientId:        clientId,
		InstanceId:      instanceId,
		NetworkSpace:    networkSpace,
		ConnectEnabled:  self.GetConnectEnabled(),
		ProvideEnabled:  self.GetProvideEnabled(),
		DeviceAvailable: true,
	})
}
```

- [ ] **Step 6: Bridge it over the RPC**

The bridge uses `rpcCallNoArg[string]` on the client and a `(_ RpcNoArg, out *string) error` handler on the server — the shape `GetTunnelStarted` uses (`sdk/device_rpc.go:1155-1180` and `:8804-8807`). `net/rpc` reflects handler methods, so no registration is needed anywhere.

In `sdk/device_rpc.go`, next to `DeviceRemote.UploadLogs` (line 5027):

```go
func (self *DeviceRemote) DiagnosticManifestJson() string {
	self.stateLock.Lock()
	defer self.stateLock.Unlock()

	if self.service == nil {
		// the tunnel is not running: the app still exports, with the
		// device-side fields marked absent rather than reported as false
		return buildDiagnosticManifestJson(diagnosticManifestInput{
			SdkVersion:      Version,
			DeviceAvailable: false,
		})
	}

	manifestJson, err := rpcCallNoArg[string](self.service, "DeviceLocalRpc.DiagnosticManifestJson", self.closeService)
	if err != nil {
		return buildDiagnosticManifestJson(diagnosticManifestInput{
			SdkVersion:      Version,
			DeviceAvailable: false,
		})
	}
	return manifestJson
}
```

And next to `DeviceLocalRpc.UploadLogs` (line 9064):

```go
func (self *DeviceLocalRpc) DiagnosticManifestJson(_ RpcNoArg, manifestJson *string) error {
	*manifestJson = self.deviceLocal.DiagnosticManifestJson()
	return nil
}
```

- [ ] **Step 7: Verify the whole SDK builds, vets and passes**

Run: `cd sdk && go build ./... && go vet ./... && go test ./... 2>&1 | tail -3`
Expected: `ok  github.com/urnetwork/sdk`

- [ ] **Step 8: Regenerate the cgo ABI**

The new exports change the C surface. Run: `cd sdk/cgo && go run ./gen`
Expected: `✅ generated N c functions ...`

- [ ] **Step 9: Commit**

```bash
git add sdk/device.go sdk/device_local.go sdk/device_rpc.go sdk/diagnostics.go sdk/diagnostics_manifest_test.go sdk/cgo
git commit -m "feat: DiagnosticManifestJson on the Device interface

Bridged over the device rpc with rpcCallNoArg[string], the shape
GetTunnelStarted uses. The manifest is the ONE thing that crosses the rpc for
the export: it is kilobytes of json, where the log files it describes would
not fit the ios extension's 20MB memory target."
```

---

### Task 7: Android — point logging at a per-process root

**Files:**
- Modify: `android/app/app/src/main/java/com/bringyour/network/MainApplication.kt:159`

**Interfaces:**
- Consumes: `SetLogDirForProcess` (Task 2).
- Produces: Android logs under `<filesDir>/logs/app/`, discoverable by `LogInventory()`.

- [ ] **Step 1: Change the call**

In `MainApplication.onCreate`, replace:

```kotlin
        val path: String = applicationContext.filesDir.absolutePath
        Sdk.setLogDir(path)
```

with:

```kotlin
        // One subdirectory per writing process, under a shared root. Android is
        // single-process (no android:process in the manifest), so there is only
        // ever "app" here -- but the layout is what the exporter enumerates, and
        // it keeps the sdk call identical across platforms.
        val logRoot: String = File(applicationContext.filesDir, "logs").absolutePath
        Sdk.setLogDirForProcess(logRoot, "app")
```

Add `import java.io.File` to the imports if absent.

- [ ] **Step 2: Verify it compiles**

Run: `cd android/app && ./gradlew --no-daemon :app:compileGithubDebugKotlin`
Expected: BUILD SUCCESSFUL

This requires the SDK `.aar` from Tasks 1–6; build it first with `cd sdk/build && make build_android` (needs `ANDROID_NDK_HOME` set and Go 1.26.5).

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/MainApplication.kt
git commit -m "feat: write android logs under a per-process root"
```

---

### Task 8: Android — Diagnostics section in the developer screen

The developer menu already exists on `beta/custom-server` (`DeveloperScreen.kt`, `Route.Developer` at `MainNavViewModel.kt:105`, entry at `SettingsScreen.kt:1346-1370`). This adds a section to it. `DeveloperAction` (`DeveloperScreen.kt:795-808`) is the existing tappable-row helper, and `URTextInputLabel` is the section-label pattern.

**Files:**
- Modify: `android/app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperViewModel.kt`
- Modify: `android/app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperScreen.kt`
- Modify: `android/app/app/src/main/res/values/strings.xml`
- Test: `android/app/app/src/test/java/com/bringyour/network/ui/settings/DiagnosticExportTest.kt`

**Interfaces:**
- Consumes: `ExportDiagnosticBundle`, `NewExportOptions`, `LogInventory` (Tasks 3–5), `Device.DiagnosticManifestJson` (Task 6).
- Produces: `DeveloperViewModel.exportDiagnostics(destDir: File, redact: Boolean, selected: List<String>): File?`; the Diagnostics UI.

- [ ] **Step 1: Write the failing test**

Create `android/app/app/src/test/java/com/bringyour/network/ui/settings/DiagnosticExportTest.kt`:

```kotlin
package com.bringyour.network.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExportTest {

    @Test
    fun bundleFileNameIsSortableAndCarriesTheMode() {
        val raw = diagnosticBundleFileName(millis = 1767225600000L, redacted = false)
        val redacted = diagnosticBundleFileName(millis = 1767225600000L, redacted = true)

        assertTrue("raw name should end in .zip, was $raw", raw.endsWith(".zip"))
        assertTrue("redacted name should say so, was $redacted", redacted.contains("redacted"))
        assertTrue("raw name should not claim redaction, was $raw", !raw.contains("redacted"))
        // lexical sort must match chronological sort
        val earlier = diagnosticBundleFileName(millis = 1767225500000L, redacted = false)
        assertTrue("$earlier should sort before $raw", earlier < raw)
    }

    @Test
    fun logcatCommandReadsOnlyThisAppsOwnBuffer() {
        assertEquals(listOf("logcat", "-d", "-v", "threadtime"), logcatDumpCommand())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android/app && ./gradlew --no-daemon :app:testGithubDebugUnitTest --tests '*DiagnosticExportTest*'`
Expected: FAIL — unresolved reference `diagnosticBundleFileName`

- [ ] **Step 3: Implement the view-model support**

Append to `DeveloperViewModel.kt` (outside the class, at file scope):

```kotlin
/**
 * Bundle names sort lexically in the same order they were made, so a support
 * thread with several attachments reads in order, and carry the mode so a
 * redacted bundle is never mistaken for a complete one.
 */
fun diagnosticBundleFileName(millis: Long, redacted: Boolean): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(millis))
    val suffix = if (redacted) "-redacted" else ""
    return "urnetwork-diagnostics-$stamp$suffix.zip"
}

/**
 * `logcat -d` dumps and exits. Since android 4.1 an app reads only its OWN
 * buffer, which is exactly the wanted scope -- no permission is involved and
 * no other app's entries are reachable.
 */
fun logcatDumpCommand(): List<String> = listOf("logcat", "-d", "-v", "threadtime")
```

Add inside the `DeveloperViewModel` class:

```kotlin
    var lastExport by mutableStateOf<String?>(null)
        private set

    /**
     * Writes a diagnostic bundle into destDir and returns it, or null if the
     * export failed outright. A source that could not be read is NOT a
     * failure: it is reported inside the bundle and surfaced in lastExport.
     */
    fun exportDiagnostics(destDir: File, redact: Boolean, selected: List<String>, nowMillis: Long): File? {
        val dest = File(destDir, diagnosticBundleFileName(nowMillis, redact))
        return try {
            val options = Sdk.newExportOptions()
            options.redact = redact
            options.includeManifest = true
            options.includePlatformLogs = true
            selected.forEach { options.selectedNames.add(it) }

            deviceManager.device?.let { options.setManifestJson(it.diagnosticManifestJson()) }

            options.addPlatformLog("logcat.txt", readLogcat())

            val result = Sdk.exportDiagnosticBundle(dest.absolutePath, options)
            lastExport = buildString {
                append("Exported ${result.fileCount} log files (${result.byteCount / 1024} KiB)")
                for (i in 0 until result.missingSources.len()) {
                    append("\nNot included: ${result.missingSources.get(i)}")
                }
            }
            dest
        } catch (e: Exception) {
            lastExport = "Export failed: ${e.message}"
            null
        }
    }

    private fun readLogcat(): String = try {
        val process = ProcessBuilder(logcatDumpCommand()).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "logcat unavailable: ${e.message}"
    }
```

Add imports: `java.io.File`, `com.bringyour.sdk.Sdk`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android/app && ./gradlew --no-daemon :app:testGithubDebugUnitTest --tests '*DiagnosticExportTest*'`
Expected: PASS

- [ ] **Step 5: Add the strings**

In `android/app/app/src/main/res/values/strings.xml`, before the closing `</resources>`:

```xml
    <string name="dev_section_diagnostics">Diagnostics</string>
    <string name="dev_export_all_logs">Export all logs (raw)</string>
    <string name="dev_export_redacted_logs">Export redacted logs</string>
    <string name="dev_choose_logs">Choose logs…</string>
    <string name="dev_export_share">Share diagnostic bundle</string>
```

- [ ] **Step 6: Add the section to the screen**

In `DeveloperScreen.kt`, inside `DeveloperContent`, immediately before the closing `developerViewModel.lastAction?.let { ... }` block:

```kotlin
    URTextInputLabel(text = stringResource(id = R.string.dev_section_diagnostics))

    val context = LocalContext.current
    val shareBundle: (java.io.File) -> Unit = { file ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.dev_export_share)))
    }

    // cacheDir/share is already declared to the FileProvider as "share"
    // (res/xml/file_paths.xml), so a bundle written there is shareable with no
    // manifest change.
    val shareDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }

    DeveloperAction(label = stringResource(id = R.string.dev_export_all_logs)) {
        developerViewModel.exportDiagnostics(shareDir, redact = false, selected = emptyList(), nowMillis = System.currentTimeMillis())
            ?.let(shareBundle)
    }

    DeveloperAction(label = stringResource(id = R.string.dev_export_redacted_logs)) {
        developerViewModel.exportDiagnostics(shareDir, redact = true, selected = emptyList(), nowMillis = System.currentTimeMillis())
            ?.let(shareBundle)
    }

    developerViewModel.lastExport?.let { lastExport ->
        Text(lastExport, style = MaterialTheme.typography.bodySmall, color = TextMuted)
    }

    Spacer(modifier = Modifier.height(32.dp))
```

Add imports to `DeveloperScreen.kt`: `android.content.Intent`, `androidx.compose.ui.platform.LocalContext`, `androidx.core.content.FileProvider`.

- [ ] **Step 7: Verify it compiles**

Run: `cd android/app && ./gradlew --no-daemon :app:assembleGithubDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperViewModel.kt \
        app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperScreen.kt \
        app/app/src/main/res/values/strings.xml \
        app/app/src/test/java/com/bringyour/network/ui/settings/DiagnosticExportTest.kt
git commit -m "feat: export diagnostic bundles from the android developer screen"
```

---

### Task 9: Android — the selective log picker

**Files:**
- Modify: `android/app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperViewModel.kt`
- Modify: `android/app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperScreen.kt`
- Modify: `android/app/app/src/main/res/values/strings.xml`
- Test: `android/app/app/src/test/java/com/bringyour/network/ui/settings/DiagnosticExportTest.kt` (append)

**Interfaces:**
- Consumes: `LogInventory()` (Task 3), `exportDiagnostics` (Task 8).
- Produces: `DeveloperViewModel.inventory: List<LogFileInfo>`, `refreshInventory()`, and the picker UI.

- [ ] **Step 1: Write the failing test**

Append to `DiagnosticExportTest.kt`:

```kotlin
    @Test
    fun inventoryRowLabelNamesTheSourceSeverityAndSize() {
        val label = logFileRowLabel(source = "extension", severity = "ERROR", byteCount = 2048L)
        assertTrue("should name the source, was $label", label.contains("extension"))
        assertTrue("should name the severity, was $label", label.contains("ERROR"))
        assertTrue("should show KiB, was $label", label.contains("2 KiB"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android/app && ./gradlew --no-daemon :app:testGithubDebugUnitTest --tests '*DiagnosticExportTest*'`
Expected: FAIL — unresolved reference `logFileRowLabel`

- [ ] **Step 3: Implement**

Append to `DeveloperViewModel.kt` at file scope:

```kotlin
fun logFileRowLabel(source: String, severity: String, byteCount: Long): String =
    "$source · $severity · ${byteCount / 1024} KiB"
```

And inside the class:

```kotlin
    var inventory by mutableStateOf<List<com.bringyour.sdk.LogFileInfo>>(listOf())
        private set

    var selectedLogNames by mutableStateOf<Set<String>>(setOf())
        private set

    fun refreshInventory() {
        val list = Sdk.logInventory()
        inventory = (0 until list.len()).map { list.get(it) }
    }

    fun toggleLogSelection(name: String) {
        selectedLogNames = if (selectedLogNames.contains(name)) {
            selectedLogNames - name
        } else {
            selectedLogNames + name
        }
    }
```

- [ ] **Step 4: Add the picker UI**

In `DeveloperScreen.kt`, after the two export actions added in Task 8:

```kotlin
    var showPicker by remember { mutableStateOf(false) }

    DeveloperAction(label = stringResource(id = R.string.dev_choose_logs)) {
        developerViewModel.refreshInventory()
        showPicker = !showPicker
    }

    if (showPicker) {
        developerViewModel.inventory.forEach { info ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { developerViewModel.toggleLogSelection(info.name) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    logFileRowLabel(info.source, info.severity, info.byteCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (developerViewModel.selectedLogNames.contains(info.name)) BlueMedium else TextMuted,
                )
            }
        }

        DeveloperAction(label = stringResource(id = R.string.dev_export_selected)) {
            developerViewModel.exportDiagnostics(
                shareDir,
                redact = false,
                selected = developerViewModel.selectedLogNames.toList(),
                nowMillis = System.currentTimeMillis(),
            )?.let(shareBundle)
        }
    }
```

Add to `strings.xml`:

```xml
    <string name="dev_export_selected">Export selected</string>
```

Add imports to `DeveloperScreen.kt` if absent: `androidx.compose.runtime.remember`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.setValue`.

- [ ] **Step 5: Run tests and build**

Run: `cd android/app && ./gradlew --no-daemon :app:testGithubDebugUnitTest :app:assembleGithubDebug`
Expected: BUILD SUCCESSFUL, tests PASS

- [ ] **Step 6: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/ app/app/src/main/res/values/strings.xml
git commit -m "feat: selective log picker in the android developer screen"
```

---

### Task 10: iOS — App Group entitlements and per-process log directories

**Files:**
- Modify: `ios/app/network/network.entitlements`
- Modify: `ios/app/extension/extension.entitlements`
- Create: `ios/app/network/Shared/DiagnosticsLogLocation.swift`
- Modify: `ios/app/extension/PacketTunnelProvider.swift:594-608`
- Modify: `ios/app/network/NetworkApp.swift` (app-process startup)
- Test: `ios/app/networkTests/DiagnosticsLogLocationTests.swift`

**Interfaces:**
- Consumes: `SetLogDirForProcess` (Task 2).
- Produces: `DiagnosticsLogLocation.appGroupIdentifier`, `DiagnosticsLogLocation.logRoot() -> (url: URL, isShared: Bool)`, `DiagnosticsLogLocation.configure(processName: String) -> Bool` returning whether the shared container was reached. Tasks 12–13 read `isShared` to report a missing extension source.

- [ ] **Step 1: Write the failing test**

Create `ios/app/networkTests/DiagnosticsLogLocationTests.swift`:

```swift
//
//  DiagnosticsLogLocationTests.swift
//  networkTests
//
//  Covers where each process writes its logs. The app and the extension are
//  separate processes with separate containers; only an App Group lets the app
//  read what the extension wrote. When the container is unavailable -- a build
//  whose provisioning profile predates the group -- each process must fall
//  back to its own directory rather than failing, so an export still produces
//  the logs it can reach.
//

import Testing
import Foundation
@testable import URnetwork

struct DiagnosticsLogLocationTests {

    @Test func fallsBackToALocalRootWhenTheContainerIsUnavailable() {
        let location = DiagnosticsLogLocation.logRoot(
            containerURL: nil,
            fallbackURL: URL(fileURLWithPath: "/tmp/urnetwork-test")
        )
        #expect(location.isShared == false)
        #expect(location.url.path == "/tmp/urnetwork-test/Logs")
    }

    @Test func usesTheSharedContainerWhenAvailable() {
        let location = DiagnosticsLogLocation.logRoot(
            containerURL: URL(fileURLWithPath: "/private/group/network.ur"),
            fallbackURL: URL(fileURLWithPath: "/tmp/urnetwork-test")
        )
        #expect(location.isShared == true)
        #expect(location.url.path == "/private/group/network.ur/Logs")
    }

    @Test func processNamesAreDistinctSoRetentionDoesNotCollide() {
        #expect(DiagnosticsLogLocation.appProcessName != DiagnosticsLogLocation.extensionProcessName)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run in Xcode, or:
`cd ios/app && xcodebuild test -project app.xcodeproj -scheme URnetwork -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:networkTests/DiagnosticsLogLocationTests`
Expected: FAIL — cannot find `DiagnosticsLogLocation` in scope

- [ ] **Step 3: Add the App Group to both entitlements**

In `ios/app/network/network.entitlements` and `ios/app/extension/extension.entitlements`, add inside the top-level `<dict>`:

```xml
	<key>com.apple.security.application-groups</key>
	<array>
		<string>group.network.ur</string>
	</array>
```

Do not add it to `control.entitlements` — the control widget does not read logs.

- [ ] **Step 4: Implement**

Create `ios/app/network/Shared/DiagnosticsLogLocation.swift`:

```swift
//
//  DiagnosticsLogLocation.swift
//  URnetwork
//
//  Where each process writes its logs.
//
//  The app and the packet tunnel extension are separate processes with
//  separate containers, and the real DeviceLocal runs in the extension. An App
//  Group container is the only way the app can read what the extension wrote,
//  and it is the right way rather than shipping the files over the device rpc:
//  the extension runs on a 20MB device memory target, and log files are up to
//  16MB each.
//

import Foundation
import URnetworkSdk

enum DiagnosticsLogLocation {

    static let appGroupIdentifier = "group.network.ur"

    static let appProcessName = "app"
    static let extensionProcessName = "extension"

    /// The log root, and whether it is the shared container.
    ///
    /// `isShared == false` means this build cannot see the other process's
    /// logs -- normally a provisioning profile without the App Group. The
    /// export reports that as a missing source rather than failing.
    static func logRoot(containerURL: URL?, fallbackURL: URL) -> (url: URL, isShared: Bool) {
        if let containerURL {
            return (containerURL.appendingPathComponent("Logs"), true)
        }
        return (fallbackURL.appendingPathComponent("Logs"), false)
    }

    /// Whether the shared container was reached, recorded by `configure`.
    ///
    /// Read this from the ui rather than calling `configure` again: configure
    /// re-points glog and must run exactly once per process, at startup.
    private(set) static var isSharedContainerAvailable = false

    /// Points this process's glog at its own subdirectory of the log root.
    /// Call once per process, at startup. Returns whether the shared container
    /// was reached.
    @discardableResult
    static func configure(processName: String) -> Bool {
        let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        )
        let fallback = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory

        let location = logRoot(containerURL: container, fallbackURL: fallback)

        try? FileManager.default.createDirectory(at: location.url, withIntermediateDirectories: true)

        var err: NSError?
        SdkSetLogDirForProcess(location.url.path, processName, &err)

        isSharedContainerAvailable = location.isShared
        return location.isShared
    }
}
```

- [ ] **Step 5: Call it from the extension**

In `ios/app/extension/PacketTunnelProvider.swift`, replace the log-directory block at lines 594-608 (from `// set glog dir` through `SdkSetLogDir(logsURL.path, nil)`) with:

```swift
        // set glog dir: the extension's own subdirectory of the shared log
        // root, so the app can read these files for a diagnostic export and so
        // retention prunes only this process's history
        DiagnosticsLogLocation.configure(processName: DiagnosticsLogLocation.extensionProcessName)
```

`DiagnosticsLogLocation.swift` must be a member of both the `URnetwork` app target and the extension target. In Xcode, select the file and check both targets in the File Inspector's Target Membership.

- [ ] **Step 6: Call it from the app**

In `ios/app/network/NetworkApp.swift`, in the app's initializer, before any SDK use:

```swift
        // the app process writes its own logs too: glog writes NOTHING until a
        // log dir is set, so without this the app half of a diagnostic bundle
        // would be empty
        DiagnosticsLogLocation.configure(processName: DiagnosticsLogLocation.appProcessName)
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd ios/app && xcodebuild test -project app.xcodeproj -scheme URnetwork -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:networkTests/DiagnosticsLogLocationTests`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add app/network/network.entitlements app/extension/extension.entitlements \
        app/network/Shared/DiagnosticsLogLocation.swift \
        app/extension/PacketTunnelProvider.swift app/network/NetworkApp.swift \
        app/networkTests/DiagnosticsLogLocationTests.swift app/app.xcodeproj
git commit -m "feat: shared app group log root for the ios app and extension

The real DeviceLocal runs in the extension, whose logs the app could not read
at all: no app group, and the app never set a log dir, so glog wrote nothing
for it. Both processes now write to their own subdirectory of a shared root,
falling back to a local one when the container is unavailable."
```

**Ops note:** signed builds need `group.network.ur` registered in the Apple Developer portal and the provisioning profiles regenerated. Unsigned beta CI builds (`CODE_SIGNING_ALLOWED=NO`) are unaffected, and the fallback keeps the feature working without it.

---

### Task 11: iOS — delete the dead log export

`LogExportService` reads `SdkGetLogDir()` from the app process and, before Task 1, always got `""`. Its `ExportLogsButton` is commented out at `FeedbackView.swift:60`. Task 12 replaces it; leaving it would give two export paths with different behaviour.

**Files:**
- Delete: `ios/app/network/Shared/Views/ExportLogsButton/ExportLogsButton.swift`
- Delete: `ios/app/network/Shared/Views/ExportLogsButton/LogExportDocument.swift`
- Delete: `ios/app/network/Shared/Views/ExportLogsButton/LogExportService.swift`
- Delete: `ios/app/network/Shared/Views/ExportLogsButton/LogExportTransferrable.swift`
- Modify: `ios/app/network/Main/Feedback/FeedbackView.swift:60`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Removal only.

- [ ] **Step 1: Delete the files and the dead call site**

```bash
cd ios
git rm app/network/Shared/Views/ExportLogsButton/ExportLogsButton.swift \
       app/network/Shared/Views/ExportLogsButton/LogExportDocument.swift \
       app/network/Shared/Views/ExportLogsButton/LogExportService.swift \
       app/network/Shared/Views/ExportLogsButton/LogExportTransferrable.swift
```

In `ios/app/network/Main/Feedback/FeedbackView.swift`, delete the commented line 60:

```swift
    //                    ExportLogsButton()
```

Remove the four file references from the `URnetwork` target in `app.xcodeproj`.

- [ ] **Step 2: Verify nothing else referenced them**

Run: `cd ios && grep -rn "ExportLogsButton\|LogExportService\|LogExportDocument\|LogExportTransferrable" app --include="*.swift"`
Expected: no output

- [ ] **Step 3: Verify the app still builds**

Run: `cd ios/app && xcodebuild build -project app.xcodeproj -scheme URnetwork -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO`
Expected: BUILD SUCCEEDED

- [ ] **Step 4: Commit**

```bash
git commit -am "refactor: remove the non-functional ios log export

LogExportService read SdkGetLogDir() from the app process, which returned \"\"
in every process, and only ever copied the single newest file. Its button was
commented out. The diagnostics section replaces it."
```

---

### Task 12: iOS — Diagnostics section in `DeveloperView`

`DeveloperView` already exists and is routed (`SettingsForm-iOS.swift:467` → `AccountNavStackView.swift:220`). `actionRow` and `sectionHeader` are its existing helpers.

**Files:**
- Create: `ios/app/network/Main/Account/Settings/Developer/DiagnosticExportService.swift`
- Modify: `ios/app/network/Main/Account/Settings/Developer/DeveloperView.swift`
- Test: `ios/app/networkTests/DiagnosticExportServiceTests.swift`

**Interfaces:**
- Consumes: `ExportDiagnosticBundle`/`NewExportOptions` (Task 5), `DiagnosticManifestJson` (Task 6), `DiagnosticsLogLocation` (Task 10).
- Produces: `DiagnosticExportService.bundleFileName(date:redacted:) -> String`, the `DiagnosticExportService.Export` struct (`url: URL`, `summary: String`), and `DiagnosticExportService.export(redacted:selectedNames:device:isShared:date:) throws -> Export`. Task 13 reuses `export` unchanged.

- [ ] **Step 1: Write the failing test**

Create `ios/app/networkTests/DiagnosticExportServiceTests.swift`:

```swift
//
//  DiagnosticExportServiceTests.swift
//  networkTests
//
//  Covers the exported bundle's file name: sortable, and honest about whether
//  it was redacted, so a redacted bundle is never mistaken for a complete one.
//

import Testing
import Foundation
@testable import URnetwork

struct DiagnosticExportServiceTests {

    @Test func bundleNameIsSortableAndCarriesTheMode() {
        let date = Date(timeIntervalSince1970: 1767225600)
        let raw = DiagnosticExportService.bundleFileName(date: date, redacted: false)
        let redacted = DiagnosticExportService.bundleFileName(date: date, redacted: true)

        #expect(raw.hasSuffix(".zip"))
        #expect(redacted.contains("redacted"))
        #expect(!raw.contains("redacted"))

        let earlier = DiagnosticExportService.bundleFileName(
            date: Date(timeIntervalSince1970: 1767225500), redacted: false)
        #expect(earlier < raw)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ios/app && xcodebuild test -project app.xcodeproj -scheme URnetwork -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:networkTests/DiagnosticExportServiceTests`
Expected: FAIL — cannot find `DiagnosticExportService` in scope

- [ ] **Step 3: Implement**

Create `ios/app/network/Main/Account/Settings/Developer/DiagnosticExportService.swift`:

```swift
//
//  DiagnosticExportService.swift
//  URnetwork
//
//  Builds a diagnostic bundle in the app process. The app holds the
//  increased-memory-limit entitlement; the extension does not, which is why
//  the zip is assembled here from files in the shared container rather than
//  inside the extension or over the device rpc.
//

import Foundation
import URnetworkSdk

enum DiagnosticExportService {

    static func bundleFileName(date: Date, redacted: Bool) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        let suffix = redacted ? "-redacted" : ""
        return "urnetwork-diagnostics-\(formatter.string(from: date))\(suffix).zip"
    }

    /// Writes a bundle into the temporary directory and returns it.
    ///
    /// `isShared` false means this build could not reach the App Group
    /// container, so the extension's logs are not present -- recorded in the
    /// bundle rather than treated as a failure.
    /// The written bundle and a one-line summary of what it contains, including
    /// any source that could not be read.
    struct Export {
        let url: URL
        let summary: String
    }

    static func export(
        redacted: Bool,
        selectedNames: [String],
        device: SdkDeviceRemote?,
        isShared: Bool,
        date: Date = Date()
    ) throws -> Export {
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent(bundleFileName(date: date, redacted: redacted))

        guard let options = SdkNewExportOptions() else {
            throw NSError(domain: "network.ur.diagnostics", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "could not create export options"])
        }
        options.redact = redacted
        options.includeManifest = true
        for name in selectedNames {
            options.selectedNames?.add(name)
        }
        if let device {
            options.setManifestJson(device.diagnosticManifestJson())
        }
        if !isShared {
            options.missingSourceReason(
                DiagnosticsLogLocation.extensionProcessName,
                reason: "app group container unavailable in this build"
            )
        }

        var err: NSError?
        let result = SdkExportDiagnosticBundle(destination.path, options, &err)
        if let err {
            throw err
        }

        var summary = ""
        if let result {
            summary = "Exported \(result.fileCount) log files (\(result.byteCount / 1024) KiB)"
            if let missing = result.missingSources {
                for i in 0..<missing.len() {
                    summary += "\nNot included: \(missing.get(i))"
                }
            }
        }
        return Export(url: destination, summary: summary)
    }
}
```

- [ ] **Step 4: Add the section to `DeveloperView`**

In `DeveloperView.swift`, add to the stored properties:

```swift
    @State private var exportedBundle: URL?
    @State private var exportError: String?
    @State private var exportSummary: String?

    // read the flag recorded at startup; do NOT call configure() here, which
    // would re-point glog every time this view is constructed
    private var logRootIsShared: Bool { DiagnosticsLogLocation.isSharedContainerAvailable }
```

Add the section, and reference it from `body`'s `Form` after `introSection`:

```swift
    /** Diagnostics: everything the client knows, as one file the user can send. */
    private var diagnosticsSection: some View {
        Section {
            actionRow("Export all logs (raw)") {
                exportBundle(redacted: false)
            }
            actionRow("Export redacted logs") {
                exportBundle(redacted: true)
            }
            if let exportedBundle {
                ShareLink(item: exportedBundle) {
                    Text("Share \(exportedBundle.lastPathComponent)")
                        .font(themeManager.currentTheme.bodyFont)
                }
            }
            if let exportSummary {
                Text(exportSummary)
                    .font(themeManager.currentTheme.secondaryBodyFont)
                    .foregroundColor(themeManager.currentTheme.textMutedColor)
            }
            if let exportError {
                Text(exportError)
                    .font(themeManager.currentTheme.secondaryBodyFont)
                    .foregroundColor(themeManager.currentTheme.textMutedColor)
            }
        } header: {
            sectionHeader("Diagnostics")
        }
    }

    private func exportBundle(redacted: Bool, selected: [String] = []) {
        do {
            let export = try DiagnosticExportService.export(
                redacted: redacted,
                selectedNames: selected,
                device: deviceManager.device,
                isShared: logRootIsShared
            )
            exportedBundle = export.url
            exportSummary = export.summary
            exportError = nil
        } catch {
            exportedBundle = nil
            exportSummary = nil
            exportError = "Export failed: \(error.localizedDescription)"
        }
    }
```

Add `@EnvironmentObject var deviceManager: DeviceManager` to `DeveloperView` if not already present.

In `body`, add `diagnosticsSection` immediately after `introSection`, outside the `if reliabilityStore.connected` block — an export must work whether or not the tunnel is up.

- [ ] **Step 5: Run tests and build**

Run: `cd ios/app && xcodebuild test -project app.xcodeproj -scheme URnetwork -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:networkTests/DiagnosticExportServiceTests`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/network/Main/Account/Settings/Developer/ app/networkTests/DiagnosticExportServiceTests.swift app/app.xcodeproj
git commit -m "feat: diagnostics export in the ios developer menu"
```

---

### Task 13: iOS — the selective log picker

**Files:**
- Modify: `ios/app/network/Main/Account/Settings/Developer/DeveloperView.swift`
- Modify: `ios/app/network/Main/Account/Settings/Developer/DiagnosticExportService.swift`
- Test: `ios/app/networkTests/DiagnosticExportServiceTests.swift` (append)

**Interfaces:**
- Consumes: `LogInventory()` (Task 3), `DiagnosticExportService.export` (Task 12).
- Produces: `DiagnosticExportService.inventory() -> [SdkLogFileInfo]` and `DiagnosticExportService.rowLabel(source:severity:byteCount:) -> String`.

- [ ] **Step 1: Write the failing test**

Append to `DiagnosticExportServiceTests.swift`, inside the struct:

```swift
    @Test func rowLabelNamesTheSourceSeverityAndSize() {
        let label = DiagnosticExportService.rowLabel(source: "extension", severity: "ERROR", byteCount: 2048)
        #expect(label.contains("extension"))
        #expect(label.contains("ERROR"))
        #expect(label.contains("2 KiB"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ios/app && xcodebuild test -project app.xcodeproj -scheme URnetwork -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:networkTests/DiagnosticExportServiceTests`
Expected: FAIL — no member `rowLabel`

- [ ] **Step 3: Implement**

Append to `DiagnosticExportService`:

```swift
    static func rowLabel(source: String, severity: String, byteCount: Int64) -> String {
        "\(source) · \(severity) · \(byteCount / 1024) KiB"
    }

    static func inventory() -> [SdkLogFileInfo] {
        guard let list = SdkLogInventory() else { return [] }
        var infos: [SdkLogFileInfo] = []
        infos.reserveCapacity(list.len())
        for i in 0..<list.len() {
            if let info = list.get(i) {
                infos.append(info)
            }
        }
        return infos
    }
```

- [ ] **Step 4: Add the picker to `DeveloperView`**

Add stored properties:

```swift
    @State private var showLogPicker = false
    @State private var logInventory: [SdkLogFileInfo] = []
    @State private var selectedLogNames: Set<String> = []
```

Add to `diagnosticsSection`, after the redacted action row:

```swift
            actionRow("Choose logs…") {
                logInventory = DiagnosticExportService.inventory()
                showLogPicker.toggle()
            }
            if showLogPicker {
                ForEach(logInventory, id: \.name) { info in
                    Button {
                        if selectedLogNames.contains(info.name) {
                            selectedLogNames.remove(info.name)
                        } else {
                            selectedLogNames.insert(info.name)
                        }
                    } label: {
                        HStack {
                            Text(DiagnosticExportService.rowLabel(
                                source: info.source, severity: info.severity, byteCount: info.byteCount))
                                .font(themeManager.currentTheme.secondaryBodyFont)
                                .foregroundColor(
                                    selectedLogNames.contains(info.name)
                                        ? themeManager.currentTheme.accentColor
                                        : themeManager.currentTheme.textMutedColor)
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                actionRow("Export selected") {
                    exportBundle(redacted: false, selected: Array(selectedLogNames))
                }
            }
```

`exportBundle` already takes `selected: [String] = []` from Task 12, so the
"Export selected" row above needs no further change to it.

- [ ] **Step 5: Run tests and build**

Run: `cd ios/app && xcodebuild test -project app.xcodeproj -scheme URnetwork -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:networkTests/DiagnosticExportServiceTests && xcodebuild build -project app.xcodeproj -scheme URnetwork -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO`
Expected: PASS, BUILD SUCCEEDED

- [ ] **Step 6: Commit**

```bash
git add app/network/Main/Account/Settings/Developer/ app/networkTests/DiagnosticExportServiceTests.swift
git commit -m "feat: selective log picker in the ios developer menu"
```

---

## Follow-up, deliberately not in this plan

**iOS Swift-side log capture.** `OSLogStore` reads only the current process, so the app cannot collect the extension's `os_log` entries, and the 249 `print()` calls are not in `OSLog` at all. The spec's approach is to route Swift logging into glog with a new `SdkLogPlatform(level, tag, message)` and a `URLog` wrapper that also calls `os_log`, then migrate the `print()` sites. That is a ~249-site mechanical diff and belongs in its own commit after this plan lands, so it cannot destabilise a reviewable feature. Until it does, iOS bundles carry Go-side logs and the manifest; Android bundles also carry logcat.
