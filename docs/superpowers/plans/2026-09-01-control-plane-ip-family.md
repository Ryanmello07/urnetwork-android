# Control-Plane IPv4/IPv6 Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the control plane route around an address family that is proven to fail after TCP connect, and give developers an explicit force override, so users whose ISP cannot reach the service's Hurricane Electric IPv6 address can still use the app.

**Architecture:** A process-global policy object in `connect` holds two independent pieces of state — the developer's force setting and a learned demotion ledger. One resolver function narrows the network string, and every control dial calls it. The primary mobile dialer currently bypasses the seam where family policy lives; closing that bypass is what makes any of this take effect, and it simultaneously exposes the TLS handshake as the point where a post-connect failure becomes observable.

**Tech Stack:** Go 1.26 (`connect`, `sdk` — gomobile/gobind bound), Swift + swift-testing (iOS), Kotlin + Compose + JUnit (Android).

**Spec:** `android/docs/superpowers/specs/2026-09-01-control-plane-ip-family-design.md`

## Global Constraints

- **Repo order is load-bearing.** `connect` → `sdk` → `ios`/`android`. `sdk/go.mod:53` has `replace github.com/urnetwork/connect => ../connect`, so `connect` edits are visible to `sdk` immediately with no version bump. Upstream PRs must merge in the same order.
- **Go tests: stdlib `testing` only.** No testify anywhere in `connect` or `sdk`. Table-driven tests carry a leading `name` field. `connect.AssertEqual` has FailNow semantics.
- **iOS tests: swift-testing**, not XCTest — `import Testing`, plain `struct`, `@Test`, `#expect`. 11 of 12 existing test files use it.
- **gomobile/gobind constraints on exported `sdk` symbols:** no `context.Context`, no maps, no `[]byte`, no slices of structs, no variadics, no struct embedding. Exported ints are plain `int`, not named types — match the `LogVerbosity*` constants.
- **gobind doc-comment trap:** a nested `/*` inside a Go doc comment on an exported `sdk` symbol breaks the Apple build with `error: '/*' within block comment`. Never write `platform/*` or similar in an exported doc comment.
- **Android cannot be compiled on the development machine** (no JDK, Android SDK or NDK). Android tasks are verified by reading and by upstream CI. Say so plainly in task reports; never claim a gradle run that did not happen.
- **Never report a piped `xcodebuild` as passing without `set -o pipefail` and a real exit code** or an explicit `** BUILD SUCCEEDED **` / `** TEST SUCCEEDED **` line. A pipe hides the exit status.
- **Simulator name is `iPhone 17 Pro`.** There is no plain "iPhone 17" simulator.
- **Commit after every task.** Do not push. Do not open PRs — the controller does that.

### Deliberate simplification against the spec

The spec's §1 describes a `generation uint64` bumped on `NetworkChanged()` and used to scope the ledger. This plan collapses that to **clearing the ledger on `NetworkChanged()`**, which is observationally identical (every entry is invalidated at the same instant either way) and removes a concept. If a future requirement needs to distinguish "demoted on the current network" from "demoted earlier", reintroduce the counter then.

---

## File Structure

**`connect/` — new**

| File | Responsibility |
|---|---|
| `control_family.go` | The policy setting, the demotion ledger, the capability probe, the network-string resolver, and the error classifier. All of the judgement, none of the dialing. |
| `control_family_dial.go` | The one shared "dial then handshake, retry the other family once on a proven path timeout" helper. |
| `control_family_test.go` | Tests for the resolver, ledger, backoff, guard and classifier. No network. |
| `control_family_dial_test.go` | Tests for the retry helper against injected dials. No network. |

**`connect/` — modified**

| File | Change |
|---|---|
| `net.go:59` | Delete `DisableIpv4`/`DisableIpv6`. |
| `net.go:67` | `ConnectSettings.DialContext` calls the resolver. |
| `net_http.go:218-224` | Delete the fast-path branch. |
| `net_http.go:1380-1389` | HTTP/2 health check; unguard the `HTTP2Config` block. |
| `net_http.go:213` | `newNormalDialTlsContext` uses the retry helper. |
| `egress_dial.go:193` | Family evidence line, ungated on mobile. |
| `egress_dial.go:79` | `resolveEgressUDPAddr` honours policy and demotion. |
| `net_http_seam_test.go` | Extend for the mobile configuration. |

**`sdk/` — modified**

| File | Change |
|---|---|
| `sdk.go` | `SetControlIpFamilyPolicy` / `GetControlIpFamilyPolicy` / `GetControlIpFamilyStatus`, constants, clamp. |
| `network_space.go:186-191` | `NetworkSpace.SetControlIpFamilyPolicy`, and the restore at construction. |
| `local_state.go` | Persisted field + accessors. |
| `device.go:759` area | Two interface methods. |
| `device_local.go` | `DeviceLocal` implementation. |
| `device_rpc.go` | `DeviceRemote` implementation, rpc handler, replay queue. |
| `diagnostics_redact_test.go` | `family=4` survives redaction. |

**`ios/` — new**

| File | Responsibility |
|---|---|
| `.../Developer/IpFamily.swift` | Pure vocabulary: labels, details, clamp. Testable with no device. Mirrors `LogVerbosity.swift`. |
| `.../Developer/IpFamilyState.swift` | The observable and the one write path. **Unlike `LogVerbosityState`, works with no device.** |
| `app/networkTests/IpFamilyTests.swift` | Tests for the pure layer. |

**`ios/` — modified:** `.../Developer/DeveloperView.swift` (the row).

**`android/` — modified:** `.../ui/settings/DeveloperScreen.kt` (row + composable), `.../ui/settings/DeveloperViewModel.kt` (property + setter), `app/app/src/main/res/values/strings.xml`. **New:** `app/app/src/test/java/com/bringyour/network/ui/settings/IpFamilyTest.kt`.

---

## Task 1: The policy setting and the network-string resolver

The pure decision layer, with no ledger yet and no dial changes. Nothing observes it until Task 3, so this is safe to land alone.

**Files:**
- Create: `connect/control_family.go`
- Test: `connect/control_family_test.go`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `type IpFamilyPolicy int32`, constants `IpFamilyAuto = 0`, `IpFamilyForce4 = 1`, `IpFamilyForce6 = 2`
  - `func SetControlIpFamilyPolicy(policy IpFamilyPolicy)` — clamps anything unrecognised to `IpFamilyAuto`
  - `func ControlIpFamilyPolicy() IpFamilyPolicy`
  - `func controlDialNetwork(network string) (string, error)`

- [ ] **Step 1: Write the failing test**

Create `connect/control_family_test.go`:

```go
package connect

import "testing"

func TestControlDialNetworkForce(t *testing.T) {
	tests := []struct {
		name    string
		policy  IpFamilyPolicy
		network string
		want    string
		wantErr bool
	}{
		{"auto leaves tcp alone", IpFamilyAuto, "tcp", "tcp", false},
		{"auto leaves udp alone", IpFamilyAuto, "udp", "udp", false},
		{"force4 narrows tcp", IpFamilyForce4, "tcp", "tcp4", false},
		{"force6 narrows tcp", IpFamilyForce6, "tcp", "tcp6", false},
		{"force4 narrows udp", IpFamilyForce4, "udp", "udp4", false},
		{"force6 narrows udp", IpFamilyForce6, "udp", "udp6", false},
		{"force4 passes matching explicit", IpFamilyForce4, "tcp4", "tcp4", false},
		{"force4 rejects conflicting explicit", IpFamilyForce4, "tcp6", "", true},
		{"force6 rejects conflicting explicit", IpFamilyForce6, "udp4", "", true},
		{"auto passes explicit through", IpFamilyAuto, "tcp6", "tcp6", false},
		{"unknown network is untouched", IpFamilyForce4, "unix", "unix", false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			SetControlIpFamilyPolicy(test.policy)
			defer SetControlIpFamilyPolicy(IpFamilyAuto)
			got, err := controlDialNetwork(test.network)
			if test.wantErr {
				if err == nil {
					t.Fatalf("expected an error for %s under %d", test.network, test.policy)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if got != test.want {
				t.Fatalf("got %q, want %q", got, test.want)
			}
		})
	}
}

func TestSetControlIpFamilyPolicyClampsUnknown(t *testing.T) {
	defer SetControlIpFamilyPolicy(IpFamilyAuto)
	SetControlIpFamilyPolicy(IpFamilyPolicy(99))
	if got := ControlIpFamilyPolicy(); got != IpFamilyAuto {
		t.Fatalf("got %d, want IpFamilyAuto", got)
	}
	SetControlIpFamilyPolicy(IpFamilyPolicy(-3))
	if got := ControlIpFamilyPolicy(); got != IpFamilyAuto {
		t.Fatalf("got %d, want IpFamilyAuto", got)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd connect && go test -run 'TestControlDialNetwork|TestSetControlIpFamilyPolicyClamps' ./...`
Expected: FAIL — `undefined: IpFamilyPolicy`, `undefined: controlDialNetwork`.

- [ ] **Step 3: Write minimal implementation**

Create `connect/control_family.go`:

```go
package connect

import (
	"fmt"
	"sync/atomic"
)

// Address-family policy for this process's CONTROL-PLANE dials: the api
// https client, the platform control websocket, and the h3/quic transport's
// name path. The tunnelled user data plane is not affected and is IPv4-only by
// its own design (see Tun.dialContext).
//
// Two independent pieces of state live here. The POLICY is what a developer
// set and is never changed by anything else. The DEMOTION LEDGER (below) is
// what this process has learned about a family that connects and then fails.
// Keeping them apart is what lets the ui round-trip "Auto" as "Auto" while a
// demotion is quietly in force.
//
// Process-global, like the egress interface binding in egress.go, and read
// INSIDE each dial rather than captured when a dialer is built -- a client
// strategy memoizes its http client and its tls dialer for the life of the
// process, so a value read at construction could never be changed at runtime.
type IpFamilyPolicy int32

const (
	// Happy Eyeballs as the platform provides it, plus reactive demotion.
	IpFamilyAuto IpFamilyPolicy = 0
	// Control dials use IPv4 only, whatever the ledger has learned.
	IpFamilyForce4 IpFamilyPolicy = 1
	// Control dials use IPv6 only, whatever the ledger has learned.
	IpFamilyForce6 IpFamilyPolicy = 2
)

var controlIpFamilyPolicy atomic.Int32

// SetControlIpFamilyPolicy sets the control-plane family policy for this
// process. An unrecognised value is Auto rather than an error: this is fed
// from a persisted file and across a gomobile boundary where an older or
// newer peer may carry a value this build does not know, and the safe
// interpretation of "something I do not understand" is "do what you would
// have done anyway".
func SetControlIpFamilyPolicy(policy IpFamilyPolicy) {
	switch policy {
	case IpFamilyForce4, IpFamilyForce6:
	default:
		policy = IpFamilyAuto
	}
	controlIpFamilyPolicy.Store(int32(policy))
}

// ControlIpFamilyPolicy returns the policy alone. It never reflects a learned
// demotion -- see controlFamilyStatus for that.
func ControlIpFamilyPolicy() IpFamilyPolicy {
	return IpFamilyPolicy(controlIpFamilyPolicy.Load())
}

// controlDialNetwork narrows a family-agnostic network string ("tcp", "udp")
// to a family-specific one when a policy or a demotion says so, and returns it
// unchanged otherwise.
//
// A FORCE conflicting with an explicitly requested family is an error, which
// preserves the semantics the dead DisableIpv4/DisableIpv6 pair had: a caller
// that asked for tcp6 by name must not silently be given tcp4. A DEMOTION
// never errors -- it is a heuristic, and a heuristic must not fail a caller's
// explicit request.
func controlDialNetwork(network string) (string, error) {
	policy := ControlIpFamilyPolicy()

	switch network {
	case "tcp4", "udp4":
		if policy == IpFamilyForce6 {
			return "", fmt.Errorf("ipv4 is disabled by the control family policy")
		}
		return network, nil
	case "tcp6", "udp6":
		if policy == IpFamilyForce4 {
			return "", fmt.Errorf("ipv6 is disabled by the control family policy")
		}
		return network, nil
	case "tcp", "udp":
		// narrowed below
	default:
		// unix sockets and anything else are not ours to reinterpret
		return network, nil
	}

	switch policy {
	case IpFamilyForce4:
		return network + "4", nil
	case IpFamilyForce6:
		return network + "6", nil
	}
	return network, nil
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd connect && go test -run 'TestControlDialNetwork|TestSetControlIpFamilyPolicyClamps' ./... && go vet ./... && gofmt -l control_family.go control_family_test.go`
Expected: PASS, clean vet, no gofmt output.

- [ ] **Step 5: Commit**

```bash
cd connect
git add control_family.go control_family_test.go
git commit -m "feat: control-plane ip family policy and network-string resolver"
```

---

## Task 2: The demotion ledger, backoff, capability guard and error classifier

The learned half. Still nothing calls it — Task 4 does.

**Files:**
- Modify: `connect/control_family.go`
- Modify: `connect/control_family_test.go`

**Interfaces:**
- Consumes: `controlDialNetwork` from Task 1 (extended here to consult the ledger).
- Produces:
  - `func controlFamilyDemote(family int) bool` — records a strike; returns whether the demotion took
  - `func controlFamilyClear()` — wired to `NetworkChanged` in this task
  - `func controlFamilyStatus() string` — `""` when nothing is demoted
  - `func isPathTimeout(err error) bool`
  - `func connFamily(conn net.Conn) int` — `4`, `6`, or `0`

- [ ] **Step 1: Write the failing test**

Append to `connect/control_family_test.go`:

```go
Extend the import block at the top of the file to:

```go
import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"testing"
	"time"
)
```

(`fmt` is used by the wrapped-deadline case, `strings` by the evidence-line tests added in Task 6.)

// Only a post-connect TIMEOUT proves a path is blackholed. Everything else is
// a server or configuration fault, and demoting a family for one would steer
// every user off a healthy path.
func TestIsPathTimeoutIsNarrow(t *testing.T) {
	tests := []struct {
		name string
		err  error
		want bool
	}{
		{"context deadline", context.DeadlineExceeded, true},
		{"os deadline", os.ErrDeadlineExceeded, true},
		{"wrapped deadline", fmt.Errorf("tls: %w", context.DeadlineExceeded), true},
		{"net timeout", &net.OpError{Err: &timeoutError{}}, true},
		{"certificate", &tls.CertificateVerificationError{}, false},
		{"connection refused", &net.OpError{Err: errors.New("connection refused")}, false},
		{"reset", errors.New("read: connection reset by peer"), false},
		{"alpn", errors.New("tls: no application protocol"), false},
		{"nil", nil, false},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := isPathTimeout(test.err); got != test.want {
				t.Fatalf("isPathTimeout(%v) = %v, want %v", test.err, got, test.want)
			}
		})
	}
}

type timeoutError struct{}

func (self *timeoutError) Error() string   { return "i/o timeout" }
func (self *timeoutError) Timeout() bool   { return true }
func (self *timeoutError) Temporary() bool { return true }

// A demotion must never take the user offline. With no IPv4 on the device,
// demoting IPv6 is refused.
func TestControlFamilyDemoteRefusedWhenOtherFamilyUnusable(t *testing.T) {
	restore := swapControlFamilyProbe(func(family int) bool { return family == 6 })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	if controlFamilyDemote(6) {
		t.Fatal("demoted ipv6 with no ipv4 available")
	}
	network, err := controlDialNetwork("tcp")
	if err != nil {
		t.Fatal(err)
	}
	if network != "tcp" {
		t.Fatalf("got %q, want tcp -- a refused demotion must not narrow", network)
	}
}

func TestControlFamilyDemoteNarrowsToTheOtherFamily(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	if !controlFamilyDemote(6) {
		t.Fatal("expected the demotion to take")
	}
	network, err := controlDialNetwork("tcp")
	if err != nil {
		t.Fatal(err)
	}
	if network != "tcp4" {
		t.Fatalf("got %q, want tcp4", network)
	}
	if controlFamilyStatus() == "" {
		t.Fatal("expected a non-empty status while a demotion is live")
	}
}

// A force beats the ledger in both directions -- it is an explicit override,
// which is the whole point of the setting.
func TestForceBeatsDemotion(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()
	controlFamilyDemote(6)

	SetControlIpFamilyPolicy(IpFamilyForce6)
	defer SetControlIpFamilyPolicy(IpFamilyAuto)
	network, err := controlDialNetwork("tcp")
	if err != nil {
		t.Fatal(err)
	}
	if network != "tcp6" {
		t.Fatalf("got %q, want tcp6 -- an explicit force outranks a demotion", network)
	}
}

func TestControlFamilyBackoffDoublesAndCaps(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	base := time.Unix(1750000000, 0)
	now := base
	restoreClock := swapControlFamilyClock(func() time.Time { return now })
	defer restoreClock()
	controlFamilyClear()
	defer controlFamilyClear()

	controlFamilyDemote(6)
	if got := controlFamilyDemotedUntil(6).Sub(base); got != controlFamilyDemotionBase {
		t.Fatalf("first demotion lasts %s, want %s", got, controlFamilyDemotionBase)
	}
	controlFamilyDemote(6)
	if got := controlFamilyDemotedUntil(6).Sub(base); got != 2*controlFamilyDemotionBase {
		t.Fatalf("second demotion lasts %s, want %s", got, 2*controlFamilyDemotionBase)
	}
	for i := 0; i < 20; i += 1 {
		controlFamilyDemote(6)
	}
	if got := controlFamilyDemotedUntil(6).Sub(base); got != controlFamilyDemotionMax {
		t.Fatalf("demotion lasts %s, want the %s cap", got, controlFamilyDemotionMax)
	}
}

func TestControlFamilyDemotionExpires(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	now := time.Unix(1750000000, 0)
	restoreClock := swapControlFamilyClock(func() time.Time { return now })
	defer restoreClock()
	controlFamilyClear()
	defer controlFamilyClear()

	controlFamilyDemote(6)
	now = now.Add(controlFamilyDemotionBase + time.Second)
	network, err := controlDialNetwork("tcp")
	if err != nil {
		t.Fatal(err)
	}
	if network != "tcp" {
		t.Fatalf("got %q, want tcp once the demotion expired", network)
	}
	if controlFamilyStatus() != "" {
		t.Fatal("expected an empty status once the demotion expired")
	}
}

// A network change invalidates everything learned about the old path.
func TestNetworkChangedClearsTheLedger(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	controlFamilyDemote(6)
	NetworkChanged()
	network, err := controlDialNetwork("tcp")
	if err != nil {
		t.Fatal(err)
	}
	if network != "tcp" {
		t.Fatalf("got %q, want tcp after a network change", network)
	}
}

func TestConnFamily(t *testing.T) {
	tests := []struct {
		name string
		addr net.Addr
		want int
	}{
		{"ipv4", &net.TCPAddr{IP: net.ParseIP("192.0.2.1"), Port: 443}, 4},
		{"ipv4 in ipv6 form", &net.TCPAddr{IP: net.ParseIP("::ffff:192.0.2.1"), Port: 443}, 4},
		{"ipv6", &net.TCPAddr{IP: net.ParseIP("2001:db8::1"), Port: 443}, 6},
		{"nil", nil, 0},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := connFamily(&stubConn{remote: test.addr}); got != test.want {
				t.Fatalf("got %d, want %d", got, test.want)
			}
		})
	}
}

type stubConn struct {
	net.Conn
	remote net.Addr
}

func (self *stubConn) RemoteAddr() net.Addr { return self.remote }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd connect && go test -run 'TestIsPathTimeout|TestControlFamily|TestForceBeats|TestNetworkChangedClears|TestConnFamily' ./...`
Expected: FAIL — `undefined: isPathTimeout`, `undefined: controlFamilyDemote`, `undefined: swapControlFamilyProbe`.

- [ ] **Step 3: Write minimal implementation**

Append to `connect/control_family.go` (and add `context`, `errors`, `net`, `os`, `strings`, `sync`, `time` to the imports):

```go
const (
	// A demotion has to outlast the reconnect storm that follows a failure,
	// and a persistent path problem has to stop costing the user anything
	// within a couple of strikes. Five minutes doubling to six hours does
	// both: the second strike already covers a ten-minute session, and a
	// genuinely broken tunnel settles at the cap.
	controlFamilyDemotionBase = 5 * time.Minute
	controlFamilyDemotionMax  = 6 * time.Hour
)

type controlFamilyDemotion struct {
	until   time.Time
	strikes int
}

// the learned half of the policy. Guarded by its own mutex rather than folded
// into an atomic: an entry is three fields and every read is off the hot path
// of an already-blocking dial.
var controlFamilyLedger = struct {
	mu      sync.Mutex
	demoted map[int]controlFamilyDemotion
	now     func() time.Time
	probe   func(family int) bool
}{
	demoted: map[int]controlFamilyDemotion{},
	now:     time.Now,
	probe:   probeFamilySupport,
}

func init() {
	// a path change invalidates everything learned about the old path
	AddNetworkChangeListener(controlFamilyClear)
}

// controlFamilyDemote records that `family` connected and then failed. It
// reports whether the demotion took.
//
// It is REFUSED when the other family is not usable on this device. On an
// IPv6-only network with no CLAT there is no IPv4 to fall back to, and
// demoting IPv6 there would take the user from a slow control plane to no
// control plane at all.
func controlFamilyDemote(family int) bool {
	other := 4
	if family == 4 {
		other = 6
	}

	controlFamilyLedger.mu.Lock()
	defer controlFamilyLedger.mu.Unlock()

	if !controlFamilyLedger.probe(other) {
		return false
	}

	now := controlFamilyLedger.now()
	entry := controlFamilyLedger.demoted[family]
	entry.strikes += 1
	backoff := controlFamilyDemotionBase << (entry.strikes - 1)
	if backoff > controlFamilyDemotionMax || backoff <= 0 {
		backoff = controlFamilyDemotionMax
	}
	entry.until = now.Add(backoff)
	controlFamilyLedger.demoted[family] = entry
	return true
}

// controlFamilyClear drops everything learned. Wired to NetworkChanged.
func controlFamilyClear() {
	controlFamilyLedger.mu.Lock()
	defer controlFamilyLedger.mu.Unlock()
	clear(controlFamilyLedger.demoted)
}

// controlFamilyDemotedFamily returns the family currently demoted, or 0.
// A demotion of BOTH families is impossible by construction -- demoting one
// requires the other to be usable -- but if it somehow occurred, neither is
// reported, because narrowing to a family we also believe is broken is worse
// than letting the platform race them.
func controlFamilyDemotedFamily() int {
	controlFamilyLedger.mu.Lock()
	defer controlFamilyLedger.mu.Unlock()
	now := controlFamilyLedger.now()
	live := 0
	for family, entry := range controlFamilyLedger.demoted {
		if now.Before(entry.until) {
			if live != 0 {
				return 0
			}
			live = family
		}
	}
	return live
}

// controlFamilyDemotedUntil is the expiry for a family, zero when not demoted.
// Exists for the tests and for the status line.
func controlFamilyDemotedUntil(family int) time.Time {
	controlFamilyLedger.mu.Lock()
	defer controlFamilyLedger.mu.Unlock()
	return controlFamilyLedger.demoted[family].until
}

// controlFamilyStatus describes any live demotion for the developer ui, and is
// empty when there is none. The ui shows this BESIDE the policy, never in
// place of it: a row that read "Force IPv4" because the heuristic fired could
// not be set back to Auto.
func controlFamilyStatus() string {
	controlFamilyLedger.mu.Lock()
	defer controlFamilyLedger.mu.Unlock()
	now := controlFamilyLedger.now()
	parts := []string{}
	for _, family := range []int{4, 6} {
		entry, ok := controlFamilyLedger.demoted[family]
		if !ok || !now.Before(entry.until) {
			continue
		}
		parts = append(parts, fmt.Sprintf(
			"IPv%d demoted for %s (%d strikes)",
			family,
			entry.until.Sub(now).Round(time.Minute),
			entry.strikes,
		))
	}
	return strings.Join(parts, ", ")
}

// probeFamilySupport reports whether this device has a usable global address
// of the family.
//
// NOT nettest.SupportsIPv4/SupportsIPv6: those memoize inside x/net behind a
// sync.Once, so they answer for whatever network the process started on and
// never re-evaluate across a wifi/cellular switch. A stale "yes, IPv4 works"
// is exactly the wrong answer for the guard that keeps a demotion from taking
// an IPv6-only user offline.
func probeFamilySupport(family int) bool {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		// unknown: assume the family is available rather than blocking a
		// demotion that may be the user's only way onto a working path
		return true
	}
	for _, addr := range addrs {
		ipNet, ok := addr.(*net.IPNet)
		if !ok || ipNet.IP == nil || !ipNet.IP.IsGlobalUnicast() {
			continue
		}
		if (ipNet.IP.To4() != nil) == (family == 4) {
			return true
		}
	}
	return false
}

// isPathTimeout reports whether err is the post-connect timeout that proves a
// path is blackholed.
//
// Deliberately narrow. A certificate failure, an ALPN mismatch, a refusal or a
// reset all mean the packets ARRIVED and something at the far end objected --
// which says nothing about the family. Demoting on those would blame IPv6 for
// a server misconfiguration and steer every user off a healthy path, which is
// worse than the bug this exists to fix.
func isPathTimeout(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, os.ErrDeadlineExceeded) {
		return true
	}
	var netErr net.Error
	if errors.As(err, &netErr) {
		return netErr.Timeout()
	}
	return false
}

// connFamily is 4, 6, or 0 when the connection has no usable remote address.
func connFamily(conn net.Conn) int {
	if conn == nil {
		return 0
	}
	addr := conn.RemoteAddr()
	if addr == nil {
		return 0
	}
	host, _, err := net.SplitHostPort(addr.String())
	if err != nil {
		host = addr.String()
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return 0
	}
	if ip.To4() != nil {
		return 4
	}
	return 6
}

// test seams. Package-private and restored by the caller.
func swapControlFamilyClock(now func() time.Time) func() {
	controlFamilyLedger.mu.Lock()
	defer controlFamilyLedger.mu.Unlock()
	prev := controlFamilyLedger.now
	controlFamilyLedger.now = now
	return func() {
		controlFamilyLedger.mu.Lock()
		defer controlFamilyLedger.mu.Unlock()
		controlFamilyLedger.now = prev
	}
}

func swapControlFamilyProbe(probe func(family int) bool) func() {
	controlFamilyLedger.mu.Lock()
	defer controlFamilyLedger.mu.Unlock()
	prev := controlFamilyLedger.probe
	controlFamilyLedger.probe = probe
	return func() {
		controlFamilyLedger.mu.Lock()
		defer controlFamilyLedger.mu.Unlock()
		controlFamilyLedger.probe = prev
	}
}
```

Then extend `controlDialNetwork`'s final block from Task 1 so a demotion narrows when the policy is Auto. Replace:

```go
	switch policy {
	case IpFamilyForce4:
		return network + "4", nil
	case IpFamilyForce6:
		return network + "6", nil
	}
	return network, nil
```

with:

```go
	switch policy {
	case IpFamilyForce4:
		return network + "4", nil
	case IpFamilyForce6:
		return network + "6", nil
	}
	// auto: a live demotion narrows to the family that is not demoted
	switch controlFamilyDemotedFamily() {
	case 6:
		return network + "4", nil
	case 4:
		return network + "6", nil
	}
	return network, nil
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd connect && go test -run 'TestControlDialNetwork|TestSetControlIpFamilyPolicyClamps|TestIsPathTimeout|TestControlFamily|TestForceBeats|TestNetworkChangedClears|TestConnFamily' ./... && go vet ./... && gofmt -l control_family.go control_family_test.go`
Expected: PASS, clean vet, no gofmt output.

- [ ] **Step 5: Commit**

```bash
cd connect
git add control_family.go control_family_test.go
git commit -m "feat: reactive ip family demotion with backoff and an ipv6-only guard"
```

---

## Task 3: Close the dialer bypass and route every control dial through the resolver

The change that makes Tasks 1-2 take effect. Nothing above this line is observable without it.

**Files:**
- Modify: `connect/net.go:59` (delete fields), `connect/net.go:67` (use resolver)
- Modify: `connect/net_http.go:218-224` (delete fast path)
- Modify: `connect/net_http_seam_test.go`

**Interfaces:**
- Consumes: `controlDialNetwork` (Task 1).
- Produces: no new symbols. Removes `ConnectSettings.DisableIpv4` and `ConnectSettings.DisableIpv6`.

- [ ] **Step 1: Write the failing test**

Append to `connect/net_http_seam_test.go`:

```go
// The MOBILE configuration -- no proxy, no injected dial context -- is the one
// that took the old fast path and bypassed ConnectSettings.DialContext
// entirely. That bypass is why DisableIpv4/DisableIpv6 were dead: the flags
// were honored by the fragment and reorder dialers and ignored by the default
// one, so the strategy raced a forced dialer against an unforced one.
//
// This test pins the mobile shape specifically. A family policy that is not
// consulted here is a developer setting that reads back correctly and changes
// nothing.
func TestNormalTlsDialHonorsFamilyPolicyWithNoInjectedDialContext(t *testing.T) {
	SetControlIpFamilyPolicy(IpFamilyForce4)
	defer SetControlIpFamilyPolicy(IpFamilyAuto)

	settings := DefaultClientStrategySettings()
	if settings.ProxySettings != nil || settings.DialContextSettings != nil {
		t.Fatal("the default settings are no longer the mobile shape this test pins")
	}

	var networks []string
	var mutex sync.Mutex
	settings.NetDialerHook = func(network string, addr string) {
		mutex.Lock()
		defer mutex.Unlock()
		networks = append(networks, network)
	}

	dialTls := newNormalDialTlsContext(settings, clientHttpNextProtos)
	// the address is never reached: 198.51.100.0/24 is TEST-NET-2 and the dial
	// fails. What is under test is the NETWORK STRING the dialer was handed.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	conn, err := dialTls(ctx, "tcp", "198.51.100.1:443")
	if err == nil {
		conn.Close()
	}

	mutex.Lock()
	defer mutex.Unlock()
	if len(networks) == 0 {
		t.Fatal("the dialer was never called -- the seam is still bypassed")
	}
	for _, network := range networks {
		if network != "tcp4" {
			t.Fatalf("dialed %q, want tcp4 under IpFamilyForce4", network)
		}
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd connect && go test -run TestNormalTlsDialHonorsFamilyPolicy ./...`
Expected: FAIL — `settings.NetDialerHook undefined`, and once that exists, the dialer is handed `"tcp"` rather than `"tcp4"`.

- [ ] **Step 3: Write minimal implementation**

Three edits.

**(a)** `connect/net.go` — delete the two dead fields at `:59`:

```go
	DialContextSettings *DialContextSettings
```

(i.e. remove the `DisableIpv4 bool` and `DisableIpv6 bool` lines entirely).

**(b)** `connect/net.go` — replace the opening of `ConnectSettings.DialContext`. Replace everything from `if self.DisableIpv4 && self.DisableIpv6 {` down to the closing brace of the `switch network {` block with:

```go
	network, networkErr := controlDialNetwork(network)
	if networkErr != nil {
		return nil, networkErr
	}
```

**(c)** `connect/net_http.go` — in `newNormalDialTlsContext`, delete the fast-path branch:

```go
	if settings.ProxySettings == nil && settings.DialContextSettings == nil {
		netDialer := settings.NetDialer()
		tlsDialer := &tls.Dialer{
			NetDialer: netDialer,
			Config:    tlsConfig,
		}
		return tlsDialer.DialContext
	}
```

and add a comment in its place explaining why it is gone:

```go
	// Every dial takes the explicit path below, including the mobile shape
	// (no proxy, no injected dial context) that used to shortcut to a raw
	// tls.Dialer here. That shortcut bypassed ConnectSettings.DialContext, so
	// the address-family policy expressed there was honored by the fragment
	// and reorder dialers and ignored by this one -- a strategy that raced a
	// forced dialer against an unforced one. It also hid the tls handshake,
	// which is where a blackholed path actually fails.
	//
	// The cost is that ConnectTimeout and TlsTimeout are now separate budgets
	// rather than one deadline shared across connect and handshake. That is
	// the intended behavior, and it applies to every dial, not only forced
	// ones.
```

**(d)** `connect/net.go` — add the test hook to `ConnectSettings` (beside `DialContextSettings`) and call it in `NetDialer()`:

```go
	// NetDialerHook, when set, is called with the network and address of every
	// dial the net dialer makes. Test seam only: it exists so a test can pin
	// the NETWORK STRING a dial was handed without a network.
	NetDialerHook func(network string, addr string)
```

and in `NetDialer()`, wrap the returned dialer's `Control`:

```go
func (self *ConnectSettings) NetDialer() *net.Dialer {
	// ... existing comment ...
	dialer := egressDialer(&net.Dialer{
		Timeout:         self.ConnectTimeout,
		KeepAlive:       self.KeepAliveTimeout,
		KeepAliveConfig: self.KeepAliveConfig,
		Resolver:        egressAwareResolver(self.Resolver),
	})
	if hook := self.NetDialerHook; hook != nil {
		prev := dialer.Control
		dialer.Control = func(network string, addr string, c syscall.RawConn) error {
			hook(network, addr)
			if prev != nil {
				return prev(network, addr, c)
			}
			return nil
		}
	}
	return dialer
}
```

Add `"syscall"` to `net.go`'s imports.

Note: `Control` receives the network as `"tcp4"`/`"tcp6"` (already resolved to a family by the time a socket is created), which is exactly what the test asserts.

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd connect && go build ./... && go vet ./... && \
  go test -run 'TestNormalTlsDial|TestControlDialNetwork|TestControlFamily|TestClientStrategy' ./...
```
Expected: PASS. `TestNormalTlsDialUsesInjectedDialContext` must still pass — it covers the surviving branch.

- [ ] **Step 5: Verify nothing still references the deleted fields**

Run: `cd .. && grep -rn "DisableIpv4\|DisableIpv6" connect sdk ios android --include=*.go --include=*.swift --include=*.kt`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
cd connect
git add net.go net_http.go net_http_seam_test.go
git commit -m "fix: route every control dial through the family seam

The default dialer took a fast path to a raw tls.Dialer whenever no proxy
and no injected dial context were configured -- precisely the mobile
production shape. That bypassed ConnectSettings.DialContext, so the
address-family policy expressed there applied to the fragment and reorder
dialers and not to the default one.

Also deletes DisableIpv4/DisableIpv6, which were a complete implementation
that nothing in any repo ever assigned."
```

---

## Task 4: Retry the other family once on a proven post-connect timeout

**Files:**
- Create: `connect/control_family_dial.go`
- Create: `connect/control_family_dial_test.go`
- Modify: `connect/net_http.go` (`newNormalDialTlsContext` uses the helper)

**Interfaces:**
- Consumes: `controlDialNetwork`, `controlFamilyDemote`, `isPathTimeout`, `connFamily` (Tasks 1-2).
- Produces: `func dialControlTlsWithFamilyFallback(ctx context.Context, network string, addr string, dial DialContextFunction, handshake func(context.Context, net.Conn) (net.Conn, error)) (net.Conn, error)`

- [ ] **Step 1: Write the failing test**

Create `connect/control_family_dial_test.go`:

```go
package connect

import (
	"context"
	"errors"
	"net"
	"sync"
	"testing"
)

// The shape of the reported bug: the tcp connect succeeds over IPv6 (small
// packets pass an HE tunnel), then the tls handshake times out (the large
// ServerHello is dropped). The caller must still get a working connection.
func TestFamilyFallbackRecoversFromAPostConnectTimeout(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	var mutex sync.Mutex
	var dialed []string
	dial := func(ctx context.Context, network string, addr string) (net.Conn, error) {
		mutex.Lock()
		dialed = append(dialed, network)
		mutex.Unlock()
		remote := &net.TCPAddr{IP: net.ParseIP("2001:db8::1"), Port: 443}
		if network == "tcp4" {
			remote = &net.TCPAddr{IP: net.ParseIP("192.0.2.1"), Port: 443}
		}
		return &stubConn{remote: remote}, nil
	}
	handshake := func(ctx context.Context, conn net.Conn) (net.Conn, error) {
		if connFamily(conn) == 6 {
			return nil, &timeoutError{}
		}
		return conn, nil
	}

	conn, err := dialControlTlsWithFamilyFallback(
		context.Background(), "tcp", "api.example:443", dial, handshake)
	if err != nil {
		t.Fatal(err)
	}
	if got := connFamily(conn); got != 4 {
		t.Fatalf("returned an IPv%d connection, want IPv4", got)
	}
	mutex.Lock()
	defer mutex.Unlock()
	if len(dialed) != 2 || dialed[0] != "tcp" || dialed[1] != "tcp4" {
		t.Fatalf("dialed %v, want [tcp tcp4]", dialed)
	}
	if controlFamilyDemotedFamily() != 6 {
		t.Fatal("expected ipv6 to be demoted by the failure")
	}
}

// Exactly one retry. The dial already sits inside the strategy's own dialer
// evaluation under a 15s request budget, so a helper that retried repeatedly
// could consume the whole budget alone and starve the other dialers.
func TestFamilyFallbackRetriesOnlyOnce(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	var mutex sync.Mutex
	attempts := 0
	dial := func(ctx context.Context, network string, addr string) (net.Conn, error) {
		mutex.Lock()
		attempts += 1
		mutex.Unlock()
		return &stubConn{remote: &net.TCPAddr{IP: net.ParseIP("2001:db8::1"), Port: 443}}, nil
	}
	handshake := func(ctx context.Context, conn net.Conn) (net.Conn, error) {
		return nil, &timeoutError{}
	}

	_, err := dialControlTlsWithFamilyFallback(
		context.Background(), "tcp", "api.example:443", dial, handshake)
	if err == nil {
		t.Fatal("expected the second failure to be returned")
	}
	mutex.Lock()
	defer mutex.Unlock()
	if attempts != 2 {
		t.Fatalf("dialed %d times, want exactly 2", attempts)
	}
}

// A non-timeout failure is not a path problem and must not demote or retry.
func TestFamilyFallbackDoesNotRetryANonTimeout(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	attempts := 0
	dial := func(ctx context.Context, network string, addr string) (net.Conn, error) {
		attempts += 1
		return &stubConn{remote: &net.TCPAddr{IP: net.ParseIP("2001:db8::1"), Port: 443}}, nil
	}
	certErr := errors.New("x509: certificate signed by unknown authority")
	handshake := func(ctx context.Context, conn net.Conn) (net.Conn, error) {
		return nil, certErr
	}

	_, err := dialControlTlsWithFamilyFallback(
		context.Background(), "tcp", "api.example:443", dial, handshake)
	if !errors.Is(err, certErr) {
		t.Fatalf("got %v, want the certificate error unwrapped", err)
	}
	if attempts != 1 {
		t.Fatalf("dialed %d times, want 1 -- a certificate failure is not a path failure", attempts)
	}
	if controlFamilyDemotedFamily() != 0 {
		t.Fatal("a certificate failure must not demote a family")
	}
}

// An explicitly family-specific dial has nowhere to fall back to.
func TestFamilyFallbackDoesNotRetryAnExplicitFamily(t *testing.T) {
	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	attempts := 0
	dial := func(ctx context.Context, network string, addr string) (net.Conn, error) {
		attempts += 1
		return &stubConn{remote: &net.TCPAddr{IP: net.ParseIP("2001:db8::1"), Port: 443}}, nil
	}
	handshake := func(ctx context.Context, conn net.Conn) (net.Conn, error) {
		return nil, &timeoutError{}
	}

	_, _ = dialControlTlsWithFamilyFallback(
		context.Background(), "tcp6", "api.example:443", dial, handshake)
	if attempts != 1 {
		t.Fatalf("dialed %d times, want 1 for an explicit tcp6", attempts)
	}
}
```

Add a `Close()` to `stubConn` in `control_family_test.go` so it satisfies the helper's use:

```go
func (self *stubConn) Close() error { return nil }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd connect && go test -run TestFamilyFallback ./...`
Expected: FAIL — `undefined: dialControlTlsWithFamilyFallback`.

- [ ] **Step 3: Write minimal implementation**

Create `connect/control_family_dial.go`:

```go
package connect

import (
	"context"
	"net"
)

// dialControlTlsWithFamilyFallback performs a control-plane dial and its
// handshake, and retries ONCE over the other address family when the handshake
// fails with a timeout after the connect succeeded.
//
// That sequence -- connect succeeds, handshake times out -- is the signature of
// a path that carries small packets and drops large ones, which is what a
// tunnel with a reduced MTU and filtered ICMP Packet-Too-Big does. Happy
// Eyeballs cannot see it: it races only the tcp handshake, so the broken family
// WINS the race and then stalls.
//
// Exactly one retry, and only to the other family. The caller already sits
// inside the client strategy's serial and parallel dialer evaluation under a
// shared request budget, so a helper that retried repeatedly could consume the
// whole budget alone and starve the other dialers -- which is the failure this
// exists to prevent, not to reproduce. A second failure over the second family
// is also not a family problem, and the original error is returned unwrapped.
func dialControlTlsWithFamilyFallback(
	ctx context.Context,
	network string,
	addr string,
	dial DialContextFunction,
	handshake func(ctx context.Context, conn net.Conn) (net.Conn, error),
) (net.Conn, error) {
	conn, err := dial(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	// BEFORE the handshake, and before any Close: a closed net.TCPConn is not
	// required to keep answering RemoteAddr, and the family of the connection
	// we are about to lose is the whole point of the exercise.
	failed := connFamily(conn)

	tlsConn, err := handshake(ctx, conn)
	if err == nil {
		return tlsConn, nil
	}
	conn.Close()

	// only a family-agnostic dial has somewhere else to go
	if network != "tcp" && network != "udp" {
		return nil, err
	}
	if !isPathTimeout(err) {
		return nil, err
	}
	if failed == 0 {
		return nil, err
	}
	if !controlFamilyDemote(failed) {
		// refused: the other family is not usable on this device, so there is
		// nothing to retry onto
		return nil, err
	}

	retryNetwork := network + "4"
	if failed == 4 {
		retryNetwork = network + "6"
	}
	retryConn, retryErr := dial(ctx, retryNetwork, addr)
	if retryErr != nil {
		return nil, err
	}
	retryTlsConn, retryErr := handshake(ctx, retryConn)
	if retryErr != nil {
		retryConn.Close()
		return nil, err
	}
	return retryTlsConn, nil
}
```

Then rewrite the body of `newNormalDialTlsContext`'s returned closure in `net_http.go` to use it. The existing body already does dial-then-handshake; restructure it so the dial and the handshake are the two callbacks:

```go
	return func(ctx context.Context, network string, addr string) (net.Conn, error) {
		handshake := func(ctx context.Context, conn net.Conn) (net.Conn, error) {
			netDialer := settings.NetDialer()
			if netDialer.Timeout != 0 {
				var cancel context.CancelFunc
				ctx, cancel = context.WithTimeout(ctx, netDialer.Timeout)
				defer cancel()
			}
			if !netDialer.Deadline.IsZero() {
				var cancel context.CancelFunc
				ctx, cancel = context.WithDeadline(ctx, netDialer.Deadline)
				defer cancel()
			}
			host, _, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			config := tlsConfig.Clone()
			if config.ServerName == "" {
				config.ServerName = host
			}
			tlsConn := tls.Client(conn, config)
			tlsCtx, tlsCancel := context.WithTimeout(ctx, settings.TlsTimeout)
			defer tlsCancel()
			if err := tlsConn.HandshakeContext(tlsCtx); err != nil {
				tlsConn.Close()
				return nil, err
			}
			return tlsConn, nil
		}
		return dialControlTlsWithFamilyFallback(
			ctx, network, addr, settings.DialContext, handshake)
	}
```

Note the one behavioural change inside the handshake callback: it no longer calls `conn.Close()` itself on the `SplitHostPort` error, because the helper owns closing the connection it was handed.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd connect && go build ./... && go vet ./... && go test -run 'TestFamilyFallback|TestNormalTlsDial|TestControlFamily' ./... && gofmt -l control_family_dial.go control_family_dial_test.go net_http.go`
Expected: PASS, clean vet, no gofmt output.

- [ ] **Step 5: Commit**

```bash
cd connect
git add control_family_dial.go control_family_dial_test.go net_http.go control_family_test.go
git commit -m "feat: retry the other address family once on a post-connect timeout"
```

---

## Task 5: HTTP/2 health check for pooled control-plane connections

**Files:**
- Modify: `connect/net_http.go:1380-1389`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing. Behaviour change only.

- [ ] **Step 1: Write the failing test**

Append to `connect/net_http_seam_test.go`:

```go
// A pooled connection that connected cleanly and later went dark is invisible
// to any dial-time logic: http/2 multiplexes every later request onto it, and
// with no health check each one hangs to the request timeout. Go's default for
// HTTP2Config.SendPingTimeout is zero, which its own doc defines as "no health
// check is performed".
//
// This also pins that the config is built on EVERY platform. It used to be
// built only under the mobile memory guard, so desktop had no HTTP2Config at
// all and therefore no health check either.
func TestHttpClientConfiguresHttp2HealthCheck(t *testing.T) {
	settings := DefaultClientStrategySettings()
	dialer := &clientDialer{
		dialTlsContext:     newNormalDialTlsContext(settings, clientWebSocketNextProtos),
		httpDialTlsContext: newNormalDialTlsContext(settings, clientHttpNextProtos),
		settings:           settings,
	}
	client := dialer.HttpClient()
	defer client.CloseIdleConnections()

	transport, ok := client.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("unexpected transport type %T", client.Transport)
	}
	if transport.HTTP2 == nil {
		t.Fatal("no HTTP2Config: a pooled dead connection is never detected")
	}
	if transport.HTTP2.SendPingTimeout <= 0 {
		t.Fatal("SendPingTimeout is zero, which disables the health check")
	}
	if transport.HTTP2.PingTimeout <= 0 {
		t.Fatal("PingTimeout is zero")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd connect && go test -run TestHttpClientConfiguresHttp2HealthCheck ./...`
Expected: FAIL — `SendPingTimeout is zero, which disables the health check`.

- [ ] **Step 3: Write minimal implementation**

In `connect/net_http.go`, move the `transport.HTTP2 = &http.HTTP2Config{...}` assignment out from under the mobile-memory guard so it is always built, keep the existing memory-budget fields under that guard, and add the two health-check fields unconditionally:

Replace the existing conditional block at `net_http.go:1380-1390`:

```go
		if 0 < self.settings.Http2MaxDecoderHeaderTableSize ||
			0 < self.settings.Http2MaxEncoderHeaderTableSize ||
			0 < self.settings.Http2MaxReceiveBufferPerConnection ||
			0 < self.settings.Http2MaxReceiveBufferPerStream {
			transport.HTTP2 = &http.HTTP2Config{
				MaxDecoderHeaderTableSize:     self.settings.Http2MaxDecoderHeaderTableSize,
				MaxEncoderHeaderTableSize:     self.settings.Http2MaxEncoderHeaderTableSize,
				MaxReceiveBufferPerConnection: self.settings.Http2MaxReceiveBufferPerConnection,
				MaxReceiveBufferPerStream:     self.settings.Http2MaxReceiveBufferPerStream,
			}
		}
```

with:

```go
		// A control-plane connection that went dark AFTER connecting is
		// invisible to any dial-time policy: http/2 multiplexes every later
		// request onto it and each one hangs to the request timeout, for as
		// long as the idle pool holds it. Go performs NO health check when
		// SendPingTimeout is zero (net/http.HTTP2Config: "If zero, no health
		// check is performed"), which is what leaves that connection in the
		// pool. The ping is what turns a silent pool poisoning into an
		// eviction.
		//
		// Built unconditionally. The memory-budget fields below are set only
		// when an embedder asked for them -- that guard is about the mobile
		// heap -- but it used to gate the whole config, so a desktop build had
		// no HTTP2Config at all and therefore no health check either.
		transport.HTTP2 = &http.HTTP2Config{
			SendPingTimeout: 10 * time.Second,
			PingTimeout:     5 * time.Second,
		}
		if 0 < self.settings.Http2MaxDecoderHeaderTableSize ||
			0 < self.settings.Http2MaxEncoderHeaderTableSize ||
			0 < self.settings.Http2MaxReceiveBufferPerConnection ||
			0 < self.settings.Http2MaxReceiveBufferPerStream {
			transport.HTTP2.MaxDecoderHeaderTableSize = self.settings.Http2MaxDecoderHeaderTableSize
			transport.HTTP2.MaxEncoderHeaderTableSize = self.settings.Http2MaxEncoderHeaderTableSize
			transport.HTTP2.MaxReceiveBufferPerConnection = self.settings.Http2MaxReceiveBufferPerConnection
			transport.HTTP2.MaxReceiveBufferPerStream = self.settings.Http2MaxReceiveBufferPerStream
		}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd connect && go build ./... && go vet ./... && go test -run 'TestHttpClient|TestNormalTlsDial|TestClientStrategy' ./...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd connect
git add net_http.go net_http_seam_test.go
git commit -m "fix: health-check pooled control-plane http/2 connections"
```

---

## Task 6: The evidence trail

Without this, no bundle from an affected user can confirm or refute the diagnosis the whole feature rests on.

**Files:**
- Modify: `connect/egress_dial.go:193` (`logControlDialResult`)
- Modify: `connect/control_family.go` (log on demote and on clear)
- Test: `connect/control_family_test.go`

**Interfaces:**
- Consumes: `connFamily`, `controlFamilyStatus` (Task 2).
- Produces: `func controlDialFamilyLine(tag string, network string, addr string, conn net.Conn, err error) string` — the formatted line, returned rather than logged so it is testable.

- [ ] **Step 1: Write the failing test**

Append to `connect/control_family_test.go`:

```go
// The family must be a LITERAL token, never derived from the address. The sdk
// log redactor rewrites both IPv4 and IPv6 literals to the same opaque token
// shape, so a redacted bundle -- the mode users are asked to send -- cannot
// tell a v4 dial from a v6 dial by its address.
func TestControlDialFamilyLineCarriesALiteralFamilyToken(t *testing.T) {
	conn := &stubConn{remote: &net.TCPAddr{IP: net.ParseIP("2001:db8::1"), Port: 443}}
	line := controlDialFamilyLine("api", "tcp", "api.example:443", conn, nil)
	if !strings.Contains(line, "family=6") {
		t.Fatalf("line %q does not carry a literal family token", line)
	}
	if !strings.Contains(line, "tag=api") {
		t.Fatalf("line %q does not name the dial tag", line)
	}

	conn4 := &stubConn{remote: &net.TCPAddr{IP: net.ParseIP("192.0.2.1"), Port: 443}}
	line4 := controlDialFamilyLine("platform", "tcp", "connect.example:443", conn4, nil)
	if !strings.Contains(line4, "family=4") {
		t.Fatalf("line %q does not carry a literal family token", line4)
	}
}

// A failed dial has no connection to read a family from, and must say so
// rather than claim one.
func TestControlDialFamilyLineOnFailure(t *testing.T) {
	line := controlDialFamilyLine("api", "tcp4", "api.example:443", nil, errors.New("i/o timeout"))
	if !strings.Contains(line, "family=?") {
		t.Fatalf("line %q should report an unknown family on failure", line)
	}
	if !strings.Contains(line, "err=") {
		t.Fatalf("line %q should carry the error", line)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd connect && go test -run TestControlDialFamilyLine ./...`
Expected: FAIL — `undefined: controlDialFamilyLine`.

- [ ] **Step 3: Write minimal implementation**

Add to `connect/control_family.go`:

```go
// controlDialFamilyLine formats the per-dial family evidence.
//
// `family=4` / `family=6` is a LITERAL token, deliberately not derived from an
// address in the rendered line. The sdk's log redactor rewrites both IPv4 and
// IPv6 literals to the same opaque <addr:hex> shape, including the brackets
// that would otherwise give an IPv6 address away -- so in a REDACTED bundle,
// which is the mode a user is asked to send, an address cannot tell a support
// engineer which family was dialed. This token can.
func controlDialFamilyLine(
	tag string,
	network string,
	addr string,
	conn net.Conn,
	err error,
) string {
	family := "?"
	if f := connFamily(conn); f != 0 {
		family = fmt.Sprintf("%d", f)
	}
	policy := "auto"
	switch ControlIpFamilyPolicy() {
	case IpFamilyForce4:
		policy = "force4"
	case IpFamilyForce6:
		policy = "force6"
	}
	demoted := controlFamilyStatus()
	if demoted == "" {
		demoted = "none"
	}
	if err != nil {
		return fmt.Sprintf(
			"[family]dial tag=%s net=%s family=%s policy=%s demoted=%s err=%s",
			tag, network, family, policy, demoted, err)
	}
	return fmt.Sprintf(
		"[family]dial tag=%s net=%s family=%s policy=%s demoted=%s",
		tag, network, family, policy, demoted)
}
```

Then in `connect/egress_dial.go`, emit it from `logControlDialResult` **before** the `if !egressBound() { return }` gate, so it fires on mobile:

```go
func logControlDialResult(log Logger, tag string, bound bool, network string, addr string, conn net.Conn, err error) {
	l := loggerOrDefault(log)

	// The family line is NOT gated on egressBound(). The [egress]dial line
	// below is desktop-only by design -- it reports an interface binding that
	// only Windows and macOS ever force -- but Android and iOS are the two
	// platforms where a broken address family is actually reported, and until
	// this line existed no mobile bundle at any verbosity could say which
	// family a control dial used.
	if ok, _ := controlDialThrottle("family|" + tag + "|" + addr).Allow(time.Now()); ok {
		l.Infof("%s\n", controlDialFamilyLine(tag, network, addr, conn, err))
	}

	if !egressBound() {
		return
	}
	// ... existing body unchanged, using l ...
}
```

And log the ledger transitions in `controlFamilyDemote` / `controlFamilyClear`. Because `controlFamilyLedger` has no `Logger`, use the default: add at the end of a successful `controlFamilyDemote`, outside the lock,

```go
	loggerOrDefault(nil).Infof(
		"[family]demote family=%d strikes=%d for=%s\n", family, strikes, backoff)
```

capturing `strikes` and `backoff` into locals inside the lock first. Do the same in `controlFamilyClear` when the map was non-empty:

```go
	loggerOrDefault(nil).Infof("[family]clear (network changed)\n")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd connect && go build ./... && go vet ./... && go test -run 'TestControlDialFamilyLine|TestControlFamily|TestFamilyFallback' ./...`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd connect
git add control_family.go egress_dial.go control_family_test.go
git commit -m "feat: per-dial address-family evidence, ungated on mobile"
```

---

## Task 7: Make the H3/QUIC name path family-aware

**Files:**
- Modify: `connect/egress_dial.go:79` (`resolveEgressUDPAddr`)
- Test: `connect/control_family_test.go`

**Interfaces:**
- Consumes: `ControlIpFamilyPolicy`, `controlFamilyDemotedFamily` (Tasks 1-2).
- Produces: no new exported symbols.

- [ ] **Step 1: Write the failing test**

Append to `connect/control_family_test.go`:

```go
// The h3/quic transport resolves a name to exactly ONE udp address and dials
// it, with no family race of any kind. It is the fallback carrier that is
// supposed to rescue a stalled h1, so if it picks the same broken family the
// fallback is lost too.
func TestPickControlUDPAddrHonorsPolicyAndDemotion(t *testing.T) {
	v6 := net.IPAddr{IP: net.ParseIP("2001:db8::1")}
	v4 := net.IPAddr{IP: net.ParseIP("192.0.2.1")}
	addrs := []net.IPAddr{v6, v4}

	restore := swapControlFamilyProbe(func(int) bool { return true })
	defer restore()
	controlFamilyClear()
	defer controlFamilyClear()

	// auto with nothing learned keeps the resolver's own order
	if got := pickControlIPAddr(addrs); !got.IP.Equal(v6.IP) {
		t.Fatalf("got %v, want the resolver's first address", got.IP)
	}

	// a demotion moves the pick off the demoted family
	controlFamilyDemote(6)
	if got := pickControlIPAddr(addrs); !got.IP.Equal(v4.IP) {
		t.Fatalf("got %v, want the IPv4 address once IPv6 is demoted", got.IP)
	}
	controlFamilyClear()

	// a force wins outright
	SetControlIpFamilyPolicy(IpFamilyForce4)
	defer SetControlIpFamilyPolicy(IpFamilyAuto)
	if got := pickControlIPAddr(addrs); !got.IP.Equal(v4.IP) {
		t.Fatalf("got %v, want the IPv4 address under force4", got.IP)
	}
}

// With no address of the preferred family, the pick falls back rather than
// failing: a forced family that the name does not publish must not make the
// transport unusable.
func TestPickControlUDPAddrFallsBackWhenNoAddressMatches(t *testing.T) {
	v6 := net.IPAddr{IP: net.ParseIP("2001:db8::1")}
	SetControlIpFamilyPolicy(IpFamilyForce4)
	defer SetControlIpFamilyPolicy(IpFamilyAuto)
	if got := pickControlIPAddr([]net.IPAddr{v6}); !got.IP.Equal(v6.IP) {
		t.Fatalf("got %v, want the only available address", got.IP)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd connect && go test -run TestPickControlUDPAddr ./...`
Expected: FAIL — `undefined: pickControlIPAddr`.

- [ ] **Step 3: Write minimal implementation**

Add to `connect/control_family.go`:

```go
// pickControlIPAddr chooses which resolved address a single-address control
// dial should use, honoring a force and then a demotion.
//
// Falls back to the first address when nothing matches the preference: a
// forced family the name does not publish must degrade to "dial what exists"
// rather than make the transport unusable.
func pickControlIPAddr(addrs []net.IPAddr) net.IPAddr {
	if len(addrs) == 0 {
		return net.IPAddr{}
	}
	want := 0
	switch ControlIpFamilyPolicy() {
	case IpFamilyForce4:
		want = 4
	case IpFamilyForce6:
		want = 6
	default:
		switch controlFamilyDemotedFamily() {
		case 6:
			want = 4
		case 4:
			want = 6
		}
	}
	if want == 0 {
		return addrs[0]
	}
	for _, addr := range addrs {
		if (addr.IP.To4() != nil) == (want == 4) {
			return addr
		}
	}
	return addrs[0]
}
```

Then in `resolveEgressUDPAddr` (`egress_dial.go:79`), apply it on both branches.

For the unbound branch, replace `return net.ResolveUDPAddr("udp", addr)` with a resolve-then-pick so the family preference applies on mobile, where `egressBound()` is always false and this branch is the only one taken:

```go
	if resolver == nil || !egressBound() {
		host, portStr, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}
		port, err := strconv.Atoi(portStr)
		if err != nil {
			return nil, fmt.Errorf("resolve %s: non-numeric port: %w", addr, err)
		}
		// an ip literal has no family choice to make
		if ip, ipErr := netip.ParseAddr(host); ipErr == nil {
			return &net.UDPAddr{IP: net.IP(ip.AsSlice()), Port: port, Zone: ip.Zone()}, nil
		}
		addrs, err := net.DefaultResolver.LookupIPAddr(ctx, host)
		if err != nil {
			return nil, err
		}
		if len(addrs) == 0 {
			return nil, fmt.Errorf("resolve %s: no addresses", host)
		}
		pick := pickControlIPAddr(addrs)
		return &net.UDPAddr{IP: pick.IP, Port: port, Zone: pick.Zone}, nil
	}
```

For the egress-bound branch, leave the existing interface-index loop as the first preference (that binding is a hard constraint, not a preference) and use `pickControlIPAddr` only when no index is forced for either family — i.e. replace the `pick := addrs[0]` initialiser with `pick := pickControlIPAddr(addrs)` and leave the loop below it untouched.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd connect && go build ./... && go vet ./... && go test -run 'TestPickControl|TestResolveEgressUDPAddr|TestControlFamily' ./...`
Expected: PASS. The existing `TestResolveEgressUDPAddrIpLiteral` and `TestResolveEgressUDPAddrUnboundMatchesNet` must still pass.

- [ ] **Step 5: Run the full connect suite**

Run: `cd connect && go test ./... 2>&1 | tail -30`
Expected: PASS. Record the actual output — do not summarise a run that was not made.

- [ ] **Step 6: Commit**

```bash
cd connect
git add control_family.go egress_dial.go control_family_test.go
git commit -m "feat: make the h3/quic name path honor the control family policy"
```

---

## Task 8: SDK process-global surface

**Files:**
- Modify: `sdk/sdk.go`
- Create: `sdk/control_ip_family_test.go`

**Interfaces:**
- Consumes: `connect.SetControlIpFamilyPolicy`, `connect.ControlIpFamilyPolicy`, and a new exported `connect.ControlFamilyStatus()` wrapper (add it in this task as a one-line export of `controlFamilyStatus`).
- Produces:
  - `const IpFamilyPolicyAuto = 0`, `IpFamilyPolicyForce4 = 1`, `IpFamilyPolicyForce6 = 2`
  - `func SetControlIpFamilyPolicy(policy int)`
  - `func GetControlIpFamilyPolicy() int`
  - `func GetControlIpFamilyStatus() string`
  - `func clampIpFamilyPolicy(policy int) int`

- [ ] **Step 1: Write the failing test**

Create `sdk/control_ip_family_test.go`:

```go
package sdk

import "testing"

func TestControlIpFamilyPolicyRoundTrips(t *testing.T) {
	defer SetControlIpFamilyPolicy(IpFamilyPolicyAuto)
	tests := []struct {
		name string
		set  int
		want int
	}{
		{"auto", IpFamilyPolicyAuto, IpFamilyPolicyAuto},
		{"force4", IpFamilyPolicyForce4, IpFamilyPolicyForce4},
		{"force6", IpFamilyPolicyForce6, IpFamilyPolicyForce6},
		{"above range clamps to auto", 7, IpFamilyPolicyAuto},
		{"below range clamps to auto", -1, IpFamilyPolicyAuto},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			SetControlIpFamilyPolicy(test.set)
			if got := GetControlIpFamilyPolicy(); got != test.want {
				t.Fatalf("got %d, want %d", got, test.want)
			}
		})
	}
}

// The policy accessor must NEVER reflect a learned demotion. A ui row that
// read back "Force IPv4" because the heuristic fired could not be set to Auto,
// because it would already appear not to be Auto.
func TestGetControlIpFamilyPolicyIgnoresDemotion(t *testing.T) {
	defer SetControlIpFamilyPolicy(IpFamilyPolicyAuto)
	SetControlIpFamilyPolicy(IpFamilyPolicyAuto)
	if got := GetControlIpFamilyPolicy(); got != IpFamilyPolicyAuto {
		t.Fatalf("got %d, want auto", got)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestControlIpFamily .`
Expected: FAIL — `undefined: SetControlIpFamilyPolicy`.

- [ ] **Step 3: Write minimal implementation**

First add the exported wrapper to `connect/control_family.go`:

```go
// ControlFamilyStatus describes any live demotion, and is empty when there is
// none. For a developer ui that shows what auto has learned.
func ControlFamilyStatus() string {
	return controlFamilyStatus()
}
```

Then add to `sdk/sdk.go`, near the `LogVerbosity` block:

```go
// Control-plane address family policy.
//
// Plain ints rather than a named type: gomobile binds these as constants that
// Swift and Kotlin read directly, and a named Go int type crosses the binding
// as an opaque wrapper the ui cannot compare against a literal.
const (
	// Use whatever the platform's dual-stack resolution and Happy Eyeballs
	// choose, and route around a family this process has proven fails after
	// connecting.
	IpFamilyPolicyAuto = 0
	// Control-plane dials use IPv4 only.
	IpFamilyPolicyForce4 = 1
	// Control-plane dials use IPv6 only.
	IpFamilyPolicyForce6 = 2
)

// SetControlIpFamilyPolicy sets the address family THIS process uses for
// control-plane dials: the api, the platform control websocket, and the h3
// transport's name resolution. It does not affect tunnelled user traffic,
// which is IPv4-only by its own design.
//
// This process only. On ios the api dial happens in the packet tunnel
// extension whenever the tunnel is up, so a value set here reaches that
// process through Device.SetControlIpFamilyPolicy -- see device_rpc.go. It is
// not persisted here either; NetworkSpace.SetControlIpFamilyPolicy is the
// entry point that both sets and records.
//
// An out-of-range value is Auto rather than an error, so a value written by a
// newer build and read by an older one degrades to the default behavior.
func SetControlIpFamilyPolicy(policy int) {
	connect.SetControlIpFamilyPolicy(connect.IpFamilyPolicy(clampIpFamilyPolicy(policy)))
}

// GetControlIpFamilyPolicy returns the policy THIS process is dialing under.
//
// The policy ALONE: a family this process demoted on its own after a proven
// failure is reported by GetControlIpFamilyStatus and never here, so a ui row
// round-trips exactly what was set.
func GetControlIpFamilyPolicy() int {
	return int(connect.ControlIpFamilyPolicy())
}

// GetControlIpFamilyStatus describes any family this process has demoted, and
// is empty when there is none. For the developer ui's detail line: without it
// Auto looks identical whether the heuristic has fired or not.
func GetControlIpFamilyStatus() string {
	return connect.ControlFamilyStatus()
}

func clampIpFamilyPolicy(policy int) int {
	switch policy {
	case IpFamilyPolicyForce4, IpFamilyPolicyForce6:
		return policy
	}
	return IpFamilyPolicyAuto
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go build ./... && go vet ./... && go test -run TestControlIpFamily . && gofmt -l sdk.go control_ip_family_test.go`
Expected: PASS, clean vet, no gofmt output.

- [ ] **Step 5: Verify the gomobile binding still generates**

Run: `cd sdk/cgo && go run ./gen && cd .. && git diff --stat cgo/include/`
Expected: the generated headers pick up the three new functions and three new constants. Stage the regenerated headers.

- [ ] **Step 6: Commit**

```bash
cd connect && git add control_family.go && git commit -m "feat: export ControlFamilyStatus for the sdk"
cd ../sdk && git add sdk.go control_ip_family_test.go cgo/include/
git commit -m "feat: sdk surface for the control-plane ip family policy"
```

---

## Task 9: Persistence and the restore at NetworkSpace construction

The task where the spec's one deliberate departure from the log-verbosity template lands.

**Files:**
- Modify: `sdk/local_state.go`
- Modify: `sdk/network_space.go`
- Modify: `sdk/control_ip_family_test.go`

**Interfaces:**
- Consumes: `SetControlIpFamilyPolicy`, `clampIpFamilyPolicy` (Task 8).
- Produces:
  - `func (self *LocalState) GetControlIpFamilyPolicy() (int, error)` and `SetControlIpFamilyPolicy(policy int) error`
  - `func (self *LocalState) controlIpFamilyPolicyIfSet() (int, bool)`
  - `func (self *NetworkSpace) SetControlIpFamilyPolicy(policy int)`
  - `func applyPersistedControlIpFamilyPolicy(localState *LocalState, log connect.Logger) (int, bool)`

- [ ] **Step 1: Write the failing test**

Append to `sdk/control_ip_family_test.go`:

```go
// THE departure from the log-verbosity template, and the reason for it.
//
// A user who forces IPv4, kills the app and relaunches hits the LOGIN api call
// before any Device exists. That is precisely the call they are stuck on. The
// log verbosity is restored from the two Device constructors, which would
// leave this setting inert during the one request that matters -- while the
// developer menu read back the correct value the whole time.
func TestPolicyIsInForceAfterNetworkSpaceConstructionWithNoDevice(t *testing.T) {
	defer SetControlIpFamilyPolicy(IpFamilyPolicyAuto)
	SetControlIpFamilyPolicy(IpFamilyPolicyAuto)

	storagePath := t.TempDir()
	localState := NewLocalState(storagePath)
	if err := localState.SetControlIpFamilyPolicy(IpFamilyPolicyForce4); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	networkSpace := newNetworkSpace(
		ctx,
		storagePath,
		NetworkSpaceKey{HostName: "example.test", EnvName: "main"},
		&NetworkSpaceValues{},
	)
	defer networkSpace.Close()

	if got := GetControlIpFamilyPolicy(); got != IpFamilyPolicyForce4 {
		t.Fatalf("policy is %d after constructing a network space, want force4 -- "+
			"the restore did not happen before the first api call could be made", got)
	}
}

func TestNetworkSpaceSetControlIpFamilyPolicyPersists(t *testing.T) {
	defer SetControlIpFamilyPolicy(IpFamilyPolicyAuto)
	storagePath := t.TempDir()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	networkSpace := newNetworkSpace(
		ctx,
		storagePath,
		NetworkSpaceKey{HostName: "example.test", EnvName: "main"},
		&NetworkSpaceValues{},
	)
	defer networkSpace.Close()

	networkSpace.SetControlIpFamilyPolicy(IpFamilyPolicyForce6)
	if got := GetControlIpFamilyPolicy(); got != IpFamilyPolicyForce6 {
		t.Fatalf("process policy is %d, want force6", got)
	}

	got, ok := NewLocalState(storagePath).controlIpFamilyPolicyIfSet()
	if !ok {
		t.Fatal("nothing was persisted")
	}
	if got != IpFamilyPolicyForce6 {
		t.Fatalf("persisted %d, want force6", got)
	}
}

func TestUnsetPolicyDoesNotOverrideTheProcessValue(t *testing.T) {
	defer SetControlIpFamilyPolicy(IpFamilyPolicyAuto)
	localState := NewLocalState(t.TempDir())
	if _, ok := localState.controlIpFamilyPolicyIfSet(); ok {
		t.Fatal("a fresh local state reports a policy it was never given")
	}
	SetControlIpFamilyPolicy(IpFamilyPolicyForce4)
	if _, applied := applyPersistedControlIpFamilyPolicy(localState, nil); applied {
		t.Fatal("an unset policy must not be applied")
	}
	if got := GetControlIpFamilyPolicy(); got != IpFamilyPolicyForce4 {
		t.Fatalf("policy is %d, want the process value left alone", got)
	}
}
```

Adjust the `newNetworkSpace` call signature to match the real one — read `sdk/network_space.go:140` for its exact parameters before writing the test, and use them verbatim.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run 'TestPolicyIsInForce|TestNetworkSpaceSetControlIpFamily|TestUnsetPolicy' .`
Expected: FAIL — `undefined: applyPersistedControlIpFamilyPolicy`.

- [ ] **Step 3: Write minimal implementation**

**(a)** `sdk/local_state.go` — add beside the `LogVerbosity` accessors at `:358-406`:

```go
// SetControlIpFamilyPolicy persists the control-plane address family policy
// the user chose, so a relaunch comes back up under it.
//
// PER PROCESS, and not a channel between processes -- the same as
// SetLogVerbosity, and for the same reason: this lives under the network
// space's local storage, and on ios each process passes its own Documents
// container. What carries a newly chosen policy across is the device rpc.
//
// Unlike the log verbosity, this one has to be restored BEFORE any device
// exists: the login api call is made from the app process with no device, and
// for the user this setting exists for that is the call that hangs. See
// applyPersistedControlIpFamilyPolicy, which newNetworkSpace calls.
func (self *LocalState) SetControlIpFamilyPolicy(policy int) error {
	path := filepath.Join(self.localStorageDir, ".control_ip_family_policy")
	policyBytes := []byte(fmt.Sprintf("%d", clampIpFamilyPolicy(policy)))
	return os.WriteFile(path, policyBytes, LocalStorageFilePermissions)
}

// GetControlIpFamilyPolicy reads back the persisted policy. Unset or
// unreadable both read as Auto, which is what a process dials under anyway --
// restoring must never be what forces a family nobody asked for.
func (self *LocalState) GetControlIpFamilyPolicy() int {
	policy, _ := self.controlIpFamilyPolicyIfSet()
	return policy
}

// controlIpFamilyPolicyIfSet is GetControlIpFamilyPolicy plus whether a policy
// was ever written.
//
// Restoring at construction needs the difference: a persisted Auto is a policy
// the user chose (they turned a force back off), while nothing persisted is no
// instruction at all. Applying Auto for the second case would clear a policy
// an embedder set some other way.
func (self *LocalState) controlIpFamilyPolicyIfSet() (int, bool) {
	path := filepath.Join(self.localStorageDir, ".control_ip_family_policy")
	if policyBytes, err := os.ReadFile(path); err == nil {
		var policy int
		if _, err := fmt.Sscanf(string(policyBytes), "%d", &policy); err == nil {
			return clampIpFamilyPolicy(policy), true
		}
	}
	return IpFamilyPolicyAuto, false
}
```

**(b)** `sdk/sdk.go` — add the restore helper next to `applyPersistedLogVerbosity`:

```go
// applyPersistedControlIpFamilyPolicy restores the policy the user last chose
// into THIS process, and reports whether there was one.
//
// Called from newNetworkSpace, NOT from the Device constructors where
// applyPersistedLogVerbosity is called. The login api call is made before any
// Device exists, and for a user whose ipv6 path is broken that is the call
// they are stuck on -- restoring at Device construction would leave the
// setting inert for exactly the request it was set to fix.
func applyPersistedControlIpFamilyPolicy(localState *LocalState, log connect.Logger) (int, bool) {
	if localState == nil {
		return IpFamilyPolicyAuto, false
	}
	policy, ok := localState.controlIpFamilyPolicyIfSet()
	if !ok {
		return IpFamilyPolicyAuto, false
	}
	SetControlIpFamilyPolicy(policy)
	if log != nil {
		log.Infof("[family]restore policy=%d\n", clampIpFamilyPolicy(policy))
	}
	return clampIpFamilyPolicy(policy), true
}
```

**(c)** `sdk/network_space.go` — in `newNetworkSpace`, after `asyncLocalState` is built and **before** `api := newApi(...)`:

```go
	// before the api client is built, and therefore before any request can be
	// made: on a relaunch the login call is the first thing out, and for the
	// user this setting exists for, it is the call that hangs
	if asyncLocalState != nil {
		applyPersistedControlIpFamilyPolicy(asyncLocalState.GetLocalState(), clientStrategySettings.ConnectSettings.Log)
	}
```

**(d)** `sdk/network_space.go` — add the setter:

```go
// SetControlIpFamilyPolicy sets the control-plane address family policy for
// this process and records it, so a relaunch comes back up under it.
//
// The entry point a developer ui uses when there is no Device -- signed out,
// or with the tunnel down. With a Device, use Device.SetControlIpFamilyPolicy
// instead: on ios that also carries the policy into the packet tunnel
// extension, which is the process that dials while the tunnel is up.
func (self *NetworkSpace) SetControlIpFamilyPolicy(policy int) {
	clamped := clampIpFamilyPolicy(policy)
	SetControlIpFamilyPolicy(clamped)
	if self.asyncLocalState != nil {
		self.asyncLocalState.serialAsync(func() error {
			return self.asyncLocalState.GetLocalState().SetControlIpFamilyPolicy(clamped)
		})
	}
}
```

Read `DeviceRemote.persistLogVerbosity` (`device_rpc.go`, near `:5753`) and match its `serialAsync` usage exactly.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go build ./... && go vet ./... && go test -run 'TestControlIpFamily|TestPolicyIsInForce|TestNetworkSpaceSetControlIpFamily|TestUnsetPolicy|TestLocalState' .`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd sdk
git add local_state.go network_space.go sdk.go control_ip_family_test.go
git commit -m "feat: persist the control ip family policy and restore it before the first api call"
```

---

## Task 10: Device interface, DeviceLocal, DeviceRemote and the rpc crossing

**Files:**
- Modify: `sdk/device.go`, `sdk/device_local.go`, `sdk/device_rpc.go`
- Modify: `sdk/control_ip_family_test.go`

**Interfaces:**
- Consumes: everything from Tasks 8-9.
- Produces: `Device.SetControlIpFamilyPolicy(policy int)` and `Device.GetControlIpFamilyPolicy() int`.

- [ ] **Step 1: Write the failing test**

Append to `sdk/control_ip_family_test.go`:

```go
// Both Device implementations are compile-time asserted, so a missing method
// is a build failure -- but the QUEUE behavior is not, and it is what covers
// the ios regime where the tunnel is down. A policy set with no rpc service
// must be replayed to the device when one appears, or the extension keeps
// dialing under the old policy while the menu reads back the new one.
func TestDeviceRemoteQueuesThePolicyWhenTheTunnelIsDown(t *testing.T) {
	defer SetControlIpFamilyPolicy(IpFamilyPolicyAuto)
	deviceRemote := newTestDeviceRemoteWithNoService(t)

	deviceRemote.SetControlIpFamilyPolicy(IpFamilyPolicyForce4)

	if got := GetControlIpFamilyPolicy(); got != IpFamilyPolicyForce4 {
		t.Fatalf("this process is at %d, want force4 -- the app process dials while the tunnel is down", got)
	}
	if !deviceRemote.state.ControlIpFamilyPolicy.IsSet() {
		t.Fatal("the policy was not queued for replay")
	}
	if got := deviceRemote.state.ControlIpFamilyPolicy.Get(); got != IpFamilyPolicyForce4 {
		t.Fatalf("queued %d, want force4", got)
	}
}
```

Build `newTestDeviceRemoteWithNoService` by copying the construction the existing `SetLogVerbosity` tests use — read `sdk/device_rpc_test.go` for the established helper and reuse it rather than inventing one. If the existing tests construct a `DeviceRemote` inline, extract that into a shared helper in this task.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd sdk && go test -run TestDeviceRemoteQueuesThePolicy .`
Expected: FAIL — `deviceRemote.SetControlIpFamilyPolicy undefined`.

- [ ] **Step 3: Write minimal implementation**

**(a)** `sdk/device.go` — add to the `Device` interface, next to `SetLogVerbosity` at `:759`:

```go
	// SetControlIpFamilyPolicy sets the address family used for control-plane
	// dials -- the api, the platform websocket, and the h3 name path -- in the
	// process this Device runs in, and records it there.
	//
	// One of IpFamilyPolicyAuto, IpFamilyPolicyForce4, IpFamilyPolicyForce6.
	// Anything else is Auto.
	//
	// On ios the tunnel process is the one that dials while the tunnel is up,
	// so DeviceRemote sets BOTH processes -- see its implementation.
	SetControlIpFamilyPolicy(policy int)

	// GetControlIpFamilyPolicy returns the policy in force in the process this
	// Device is answering from. The policy alone, never a learned demotion.
	GetControlIpFamilyPolicy() int
```

**(b)** `sdk/device_local.go` — mirror `SetLogVerbosity` at `:6573`, including the `hostedIncompatibleGuarded` check and the `serialAsync` persist:

```go
func (self *DeviceLocal) SetControlIpFamilyPolicy(policy int) {
	if self.hostedIncompatibleGuarded("SetControlIpFamilyPolicy") {
		return
	}
	clamped := clampIpFamilyPolicy(policy)
	SetControlIpFamilyPolicy(clamped)
	if asyncLocalState := self.networkSpace.GetAsyncLocalState(); asyncLocalState != nil {
		asyncLocalState.serialAsync(func() error {
			return asyncLocalState.GetLocalState().SetControlIpFamilyPolicy(clamped)
		})
	}
}

func (self *DeviceLocal) GetControlIpFamilyPolicy() int {
	return GetControlIpFamilyPolicy()
}
```

Read `device_local.go:6567-6601` and match its guard, its logging and its `serialAsync` shape exactly.

**(c)** `sdk/device_rpc.go` — `DeviceRemote.SetControlIpFamilyPolicy`, mirroring `SetLogVerbosity` at `:5706` line for line, with these substitutions: `SetLogVerbosity` → `SetControlIpFamilyPolicy`, `clampLogVerbosity` → `clampIpFamilyPolicy`, `self.state.LogVerbosity` → `self.state.ControlIpFamilyPolicy`, `"DeviceLocalRpc.SetLogVerbosity"` → `"DeviceLocalRpc.SetControlIpFamilyPolicy"`, and `persistLogVerbosity` → a `persistControlIpFamilyPolicy` written the same way.

Add the queued field to the remote state struct beside `LogVerbosity`, and add the replay to the same sync path `LogVerbosity` uses — find it by grepping `state.LogVerbosity` and covering every site.

Add the server handler beside the existing `DeviceLocalRpc.SetLogVerbosity`:

```go
func (self *DeviceLocalRpc) SetControlIpFamilyPolicy(policy int, _ *RpcVoid) error {
	self.deviceLocal.SetControlIpFamilyPolicy(policy)
	return nil
}
```

Match the exact handler signature the neighbouring `SetLogVerbosity` handler uses — read it rather than assuming, since the `net/rpc` argument shape is load-bearing.

**(d)** `sdk/device_rpc.go` — `DeviceRemote.GetControlIpFamilyPolicy` answers from this process, exactly as `GetLogVerbosity` does and for the same reason: both processes are set together, and it stays answerable while the tunnel is down.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd sdk && go build ./... && go vet ./... && go test -run 'TestControlIpFamily|TestDeviceRemote|TestPolicyIsInForce' .`
Expected: PASS.

- [ ] **Step 5: Verify both Device implementations compile**

Run: `cd sdk && go build ./... && grep -rn "var _ Device = " *.go`
Expected: the compile-time assertions both still hold. A missing method fails the build.

- [ ] **Step 6: Regenerate the cgo headers and run the full sdk suite**

Run:
```bash
cd sdk/cgo && go run ./gen && cd .. && go test ./... 2>&1 | tail -30
```
Expected: PASS. Record the real output.

- [ ] **Step 7: Commit**

```bash
cd sdk
git add device.go device_local.go device_rpc.go control_ip_family_test.go cgo/include/
git commit -m "feat: carry the control ip family policy across the device rpc"
```

---

## Task 11: The redaction survival test

Cheap, and it guards the one thing that makes this diagnosable at all.

**Files:**
- Modify: `sdk/diagnostics_redact_test.go`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

Append to `sdk/diagnostics_redact_test.go`:

```go
// The family token has to survive redaction, because redacted is the mode a
// user is asked to send. Both address patterns rewrite IPv4 and IPv6 literals
// to the SAME opaque token shape -- including the brackets that would
// otherwise give an IPv6 address away -- so in a redacted bundle the address
// cannot tell a support engineer which family was dialed. This token is the
// only thing that can, and nothing else in the suite would catch it being
// eaten.
func TestRedactorKeepsTheFamilyToken(t *testing.T) {
	redactor := newRedactor()
	line := "[family]dial tag=api net=tcp family=6 policy=auto demoted=none"
	got := redactor.redactLine(line)
	if got != line {
		t.Fatalf("redaction changed the family line:\n got %q\nwant %q", got, line)
	}

	withAddr := "[family]dial tag=api net=tcp family=4 policy=force4 addr=192.0.2.1:443"
	redacted := redactor.redactLine(withAddr)
	if !strings.Contains(redacted, "family=4") {
		t.Fatalf("the family token did not survive: %q", redacted)
	}
	if strings.Contains(redacted, "192.0.2.1") {
		t.Fatalf("the address was not redacted: %q", redacted)
	}
}
```

Read the existing tests in the file for the exact constructor and method names — use `newRedactor`/`redactLine` only if that is what the file already uses, otherwise match it.

- [ ] **Step 2: Run test to verify it fails or passes**

Run: `cd sdk && go test -run TestRedactorKeepsTheFamilyToken .`
Expected: PASS immediately — this is a characterisation test pinning behaviour that already holds. **If it FAILS, stop**: a redaction pattern is eating the token and the log format in Task 6 must change, not the redactor.

- [ ] **Step 3: Commit**

```bash
cd sdk
git add diagnostics_redact_test.go
git commit -m "test: pin that the family token survives log redaction"
```

---

## Task 12: iOS pure vocabulary layer

**Files:**
- Create: `ios/app/network/Main/Account/Settings/Developer/IpFamily.swift`
- Create: `ios/app/networkTests/IpFamilyTests.swift`

**Interfaces:**
- Consumes: the SDK constants from Task 8.
- Produces: `enum IpFamily` with `clamp(_:)`, `name(_:)`, `detail(_:)`, `next(_:)`, `valueLabel(_:)`.

- [ ] **Step 1: Write the failing test**

Create `ios/app/networkTests/IpFamilyTests.swift`:

```swift
import Testing
@testable import URnetwork

struct IpFamilyTests {

    @Test func clampsOutOfRangeToAuto() {
        #expect(IpFamily.clamp(-1) == IpFamily.auto)
        #expect(IpFamily.clamp(7) == IpFamily.auto)
        #expect(IpFamily.clamp(IpFamily.force4) == IpFamily.force4)
        #expect(IpFamily.clamp(IpFamily.force6) == IpFamily.force6)
    }

    @Test func cyclesAutoToForce4ToForce6AndBack() {
        #expect(IpFamily.next(IpFamily.auto) == IpFamily.force4)
        #expect(IpFamily.next(IpFamily.force4) == IpFamily.force6)
        #expect(IpFamily.next(IpFamily.force6) == IpFamily.auto)
    }

    @Test func namesEveryPolicy() {
        #expect(IpFamily.name(IpFamily.auto) == "Automatic")
        #expect(IpFamily.name(IpFamily.force4) == "Force IPv4")
        #expect(IpFamily.name(IpFamily.force6) == "Force IPv6")
    }

    // The detail line has to distinguish auto-with-nothing-learned from
    // auto-with-a-demotion, or the row looks identical whether the heuristic
    // fired or not.
    @Test func autoDetailReportsALearnedDemotion() {
        let quiet = IpFamily.detail(IpFamily.auto, status: "")
        let demoted = IpFamily.detail(IpFamily.auto, status: "IPv6 demoted for 4m (2 strikes)")
        #expect(quiet != demoted)
        #expect(demoted.contains("IPv6 demoted"))
    }

    // A force is a force: the status is irrelevant because the ledger is not
    // consulted while one is set.
    @Test func forceDetailIgnoresStatus() {
        let withStatus = IpFamily.detail(IpFamily.force4, status: "IPv6 demoted for 4m (2 strikes)")
        let without = IpFamily.detail(IpFamily.force4, status: "")
        #expect(withStatus == without)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd ios/app && set -o pipefail && xcodebuild test -project app.xcodeproj -scheme URnetwork \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:networkTests 2>&1 | tail -25
```
Expected: FAIL — `cannot find 'IpFamily' in scope`.

- [ ] **Step 3: Write minimal implementation**

Create `ios/app/network/Main/Account/Settings/Developer/IpFamily.swift`:

```swift
//
//  IpFamily.swift
//  URnetwork
//
//  Which address family the control plane dials over, and what each choice
//  costs.
//
//  The service publishes both an A and an AAAA record for its api and its
//  control websocket, and the AAAA is in a tunnel-brokered range some ISPs
//  route badly. Such a path completes the tcp handshake -- small packets pass
//  -- and then drops the larger tls handshake, so the platform's own Happy
//  Eyeballs race picks it, declares it the winner, and stalls. The sdk demotes
//  a family that fails that way on its own; this is the override for when it
//  does not, and the way to prove the diagnosis on a device that reproduces it.
//
//  Pure so it can be tested without a device: the policy-to-label mapping, the
//  clamp and the cycle order are the whole of the logic.
//

import Foundation
import URnetworkSdk

enum IpFamily {

    /// Mirrors the sdk's constants. Read the numbers from the sdk rather than
    /// redeclaring them, so a rename on the Go side is a compile error here
    /// rather than a silently wrong row.
    static let auto = Int(SdkIpFamilyPolicyAuto)
    static let force4 = Int(SdkIpFamilyPolicyForce4)
    static let force6 = Int(SdkIpFamilyPolicyForce6)

    /// Anything the sdk would not recognise is Automatic, matching what the
    /// sdk itself does with an out-of-range value.
    static func clamp(_ policy: Int) -> Int {
        switch policy {
        case force4: return force4
        case force6: return force6
        default: return auto
        }
    }

    /// Tap order. Automatic first so the row returns to the safe default
    /// without the user having to know which force is which.
    static func next(_ policy: Int) -> Int {
        switch clamp(policy) {
        case auto: return force4
        case force4: return force6
        default: return auto
        }
    }

    static func name(_ policy: Int) -> String {
        switch clamp(policy) {
        case force4: return "Force IPv4"
        case force6: return "Force IPv6"
        default: return "Automatic"
        }
    }

    /**
     * What this policy means right now, including anything the sdk has
     * learned.
     *
     * `status` is the sdk's demotion description and is empty when nothing is
     * demoted. It is reported only under Automatic: a force does not consult
     * the ledger, so naming a demotion beside one would describe state that is
     * not in effect.
     */
    static func detail(_ policy: Int, status: String) -> String {
        switch clamp(policy) {
        case force4:
            return "Control-plane connections use IPv4 only."
                + " Turn this off on an IPv6-only network."
        case force6:
            return "Control-plane connections use IPv6 only."
                + " Turn this off if the app cannot reach the server."
        default:
            if !status.isEmpty {
                return "Automatic. \(status)."
            }
            return "Uses whichever family connects first,"
                + " and routes around one that fails after connecting."
        }
    }

    /// Name alone. There is no number worth showing here -- unlike the log
    /// level, the policy's integer means nothing to a support thread that the
    /// word does not already say.
    static func valueLabel(_ policy: Int) -> String {
        name(policy)
    }
}
```

Confirm the generated Swift constant names by grepping the generated header after Task 8's `go run ./gen` — they may be `SdkIpFamilyPolicyAuto` or a variant. Use whatever the binding actually emits.

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
cd ios/app && set -o pipefail && xcodebuild test -project app.xcodeproj -scheme URnetwork \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:networkTests 2>&1 | tail -25
```
Expected: `** TEST SUCCEEDED **` and exit 0.

- [ ] **Step 5: Commit**

```bash
cd ios
git add app/network/Main/Account/Settings/Developer/IpFamily.swift app/networkTests/IpFamilyTests.swift
git commit -m "feat: ios vocabulary for the control-plane ip family policy"
```

---

## Task 13: iOS state holder and the developer row

**Files:**
- Create: `ios/app/network/Main/Account/Settings/Developer/IpFamilyState.swift`
- Modify: `ios/app/network/Main/Account/Settings/Developer/DeveloperView.swift`

**Interfaces:**
- Consumes: `IpFamily` (Task 12), the SDK surface (Tasks 8-10).
- Produces: `final class IpFamilyState: ObservableObject` with `shared`, `policy: Int`, `status: String`, `isApplying: Bool`, `refresh(device:)`, `cycle(device:networkSpace:)`.

- [ ] **Step 1: Write the implementation**

There is no pure logic left to test here — Task 12 holds it, and this class is I/O against the SDK. Create `IpFamilyState.swift`:

```swift
//
//  IpFamilyState.swift
//  URnetwork
//
//  The control-plane address family policy in force, and the one write path
//  that changes it.
//
//  DELIBERATELY UNLIKE LogVerbosityState, which routes everything through the
//  device and goes inert when there is none. This setting has to work with no
//  device and with the tunnel down, because those are the states a user is in
//  when the api is unreachable -- which is the whole reason to reach for it.
//  So the write goes to the device when there is one (on ios that is what
//  carries the policy into the packet tunnel extension, the process that dials
//  while the tunnel is up) and to the network space when there is not (which
//  sets this process and records the choice for the next launch).
//
//  Held outside the view so an in-flight change is not abandoned by navigating
//  away mid-rpc.
//

import Foundation
import URnetworkSdk

final class IpFamilyState: ObservableObject {

    static let shared = IpFamilyState()

    /// The policy this process reports. Never a learned demotion -- that is
    /// `status` -- so the row round-trips exactly what was set and Automatic
    /// always reads back as Automatic.
    @Published private(set) var policy: Int = IpFamily.auto

    /// What the sdk has learned on its own, empty when nothing is demoted.
    /// Rendered in the detail line so Automatic does not look identical
    /// whether the heuristic has fired or not.
    @Published private(set) var status: String = ""

    /// True across a write. With a device the write is an rpc round trip into
    /// the extension, so the row is held rather than allowed to queue a second
    /// tap behind it.
    @Published private(set) var isApplying = false

    @MainActor
    func refresh(device: SdkDeviceRemote?) async {
        let read = await Task.detached(priority: .userInitiated) {
            (
                policy: device?.getControlIpFamilyPolicy() ?? Int(SdkGetControlIpFamilyPolicy()),
                status: SdkGetControlIpFamilyStatus()
            )
        }.value
        policy = IpFamily.clamp(read.policy)
        status = read.status
    }

    /**
     * Advances to the next policy and republishes what the sdk then reports.
     *
     * `networkSpace` is the fallback write path and is what makes this work
     * signed out or with the tunnel down: it sets this process and persists
     * the choice. With a device, the device call does both of those AND
     * carries the policy across to the extension, so it is preferred.
     */
    @MainActor
    func cycle(device: SdkDeviceRemote?, networkSpace: SdkNetworkSpace?) async {
        guard !isApplying else { return }
        let next = IpFamily.next(policy)

        isApplying = true
        await Task.detached(priority: .userInitiated) {
            if let device {
                device.setControlIpFamilyPolicy(next)
            } else if let networkSpace {
                networkSpace.setControlIpFamilyPolicy(next)
            } else {
                // no device and no space: set this process so the choice is at
                // least in force for the session, even though nothing records it
                SdkSetControlIpFamilyPolicy(next)
            }
        }.value
        isApplying = false

        await refresh(device: device)
    }
}
```

- [ ] **Step 2: Add the row to `DeveloperView.swift`**

Place it in the Diagnostics section immediately after the log-verbosity row. Read that row's exact `actionRow` / `sectionHeader` usage and match it. The row must:

- render `IpFamily.valueLabel(state.policy)` as its value and `IpFamily.detail(state.policy, status: state.status)` as its detail
- be **enabled with no device** — do not copy the verbosity row's `disabled` condition
- call `await state.cycle(device: device, networkSpace: networkSpace)` on tap
- be disabled only while `state.isApplying`
- call `await state.refresh(device: device)` from the same `.task`/`onAppear` the verbosity row refreshes in

- [ ] **Step 3: Build and test**

Run:
```bash
cd ios/app && set -o pipefail && xcodebuild build -project app.xcodeproj -scheme URnetwork \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO 2>&1 | tail -25
cd ios/app && set -o pipefail && xcodebuild test -project app.xcodeproj -scheme URnetwork \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:networkTests 2>&1 | tail -25
```
Expected: `** BUILD SUCCEEDED **` and `** TEST SUCCEEDED **`, both exit 0.

- [ ] **Step 4: Commit**

```bash
cd ios
git add app/network/Main/Account/Settings/Developer/IpFamilyState.swift \
        app/network/Main/Account/Settings/Developer/DeveloperView.swift
git commit -m "feat: ios developer control for the control-plane ip family policy"
```

---

## Task 14: Android developer row

**Files:**
- Modify: `android/app/app/src/main/res/values/strings.xml`
- Modify: `android/.../ui/settings/DeveloperViewModel.kt`
- Modify: `android/.../ui/settings/DeveloperScreen.kt`
- Create: `android/app/app/src/test/java/com/bringyour/network/ui/settings/IpFamilyTest.kt`

**Interfaces:**
- Consumes: the SDK surface (Tasks 8-10).
- Produces: `IpFamilyPolicy` enum helpers in `DeveloperScreen.kt` mirroring `logVerbosityLevel`/`nextLogVerbosity`.

- [ ] **Step 1: Write the failing test**

Create `android/app/app/src/test/java/com/bringyour/network/ui/settings/IpFamilyTest.kt`:

```kotlin
package com.bringyour.network.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IpFamilyTest {

    @Test
    fun clampsOutOfRangeToAuto() {
        assertEquals(IP_FAMILY_AUTO, clampIpFamilyPolicy(-1L))
        assertEquals(IP_FAMILY_AUTO, clampIpFamilyPolicy(7L))
        assertEquals(IP_FAMILY_FORCE_4, clampIpFamilyPolicy(IP_FAMILY_FORCE_4))
        assertEquals(IP_FAMILY_FORCE_6, clampIpFamilyPolicy(IP_FAMILY_FORCE_6))
    }

    @Test
    fun cyclesAutoForce4Force6AndBack() {
        assertEquals(IP_FAMILY_FORCE_4, nextIpFamilyPolicy(IP_FAMILY_AUTO))
        assertEquals(IP_FAMILY_FORCE_6, nextIpFamilyPolicy(IP_FAMILY_FORCE_4))
        assertEquals(IP_FAMILY_AUTO, nextIpFamilyPolicy(IP_FAMILY_FORCE_6))
    }

    // Parity with ios IpFamilyTests.autoDetailReportsALearnedDemotion: the
    // detail must distinguish auto-with-nothing-learned from
    // auto-with-a-demotion, or the row looks the same either way.
    @Test
    fun autoDetailResourceDiffersWhenSomethingIsDemoted() {
        assertNotEquals(
            ipFamilyDetailResource(IP_FAMILY_AUTO, status = ""),
            ipFamilyDetailResource(IP_FAMILY_AUTO, status = "IPv6 demoted for 4m (2 strikes)"),
        )
    }
}
```

- [ ] **Step 2: State that the test cannot be run here**

There is no JDK, Android SDK or NDK on the development machine, so `./gradlew testGithubDebugUnitTest` cannot run. Say so in the task report. Upstream CI runs it — that is this test's first real execution.

- [ ] **Step 3: Write the implementation**

**(a)** `strings.xml` — add, matching the `dev_log_verbosity_*` naming:

```xml
<string name="dev_ip_family">Control connections</string>
<string name="dev_ip_family_auto">Automatic</string>
<string name="dev_ip_family_force4">Force IPv4</string>
<string name="dev_ip_family_force6">Force IPv6</string>
<string name="dev_ip_family_auto_detail">Uses whichever family connects first, and routes around one that fails after connecting.</string>
<string name="dev_ip_family_auto_demoted_detail">Automatic. %1$s.</string>
<string name="dev_ip_family_force4_detail">Control-plane connections use IPv4 only. Turn this off on an IPv6-only network.</string>
<string name="dev_ip_family_force6_detail">Control-plane connections use IPv6 only. Turn this off if the app cannot reach the server.</string>
```

**(b)** `DeveloperScreen.kt` — add the constants and pure helpers beside `logVerbosityLevel`/`nextLogVerbosity` (around `:880-920`), so the test can reach them:

```kotlin
const val IP_FAMILY_AUTO = 0L
const val IP_FAMILY_FORCE_4 = 1L
const val IP_FAMILY_FORCE_6 = 2L

fun clampIpFamilyPolicy(policy: Long): Long = when (policy) {
    IP_FAMILY_FORCE_4 -> IP_FAMILY_FORCE_4
    IP_FAMILY_FORCE_6 -> IP_FAMILY_FORCE_6
    else -> IP_FAMILY_AUTO
}

/** Automatic first, so a tap always returns to the safe default. */
fun nextIpFamilyPolicy(policy: Long): Long = when (clampIpFamilyPolicy(policy)) {
    IP_FAMILY_AUTO -> IP_FAMILY_FORCE_4
    IP_FAMILY_FORCE_4 -> IP_FAMILY_FORCE_6
    else -> IP_FAMILY_AUTO
}

fun ipFamilyNameResource(policy: Long): Int = when (clampIpFamilyPolicy(policy)) {
    IP_FAMILY_FORCE_4 -> R.string.dev_ip_family_force4
    IP_FAMILY_FORCE_6 -> R.string.dev_ip_family_force6
    else -> R.string.dev_ip_family_auto
}

/**
 * The detail resource for a policy. `status` is the sdk's demotion
 * description and is empty when nothing is demoted; it is reported only under
 * Automatic, because a force does not consult the ledger and naming a demotion
 * beside one would describe state that is not in effect.
 */
fun ipFamilyDetailResource(policy: Long, status: String): Int =
    when (clampIpFamilyPolicy(policy)) {
        IP_FAMILY_FORCE_4 -> R.string.dev_ip_family_force4_detail
        IP_FAMILY_FORCE_6 -> R.string.dev_ip_family_force6_detail
        else ->
            if (status.isEmpty()) R.string.dev_ip_family_auto_detail
            else R.string.dev_ip_family_auto_demoted_detail
    }
```

**(c)** `DeveloperViewModel.kt` — add the properties and the setter, mirroring `logVerbosity` at `:82`:

```kotlin
/**
 * The control-plane address family policy this process reports, and what the
 * sdk has learned on its own.
 *
 * NOT nullable, unlike [logVerbosity]. The policy is process-global sdk state
 * that is always answerable -- there is no device to ask and therefore no
 * "unavailable" -- and the row has to work signed out and with the tunnel
 * down, because those are the states a user is in when the api is unreachable.
 */
var ipFamilyPolicy by mutableStateOf(IP_FAMILY_AUTO)
    private set

var ipFamilyStatus by mutableStateOf("")
    private set
```

with a `setIpFamilyPolicy` that writes through the device when there is one and through the network space otherwise, then re-reads both properties — mirroring `setLogVerbosity`'s off-main-thread discipline. Read `setLogVerbosity` in this file and match its coroutine dispatcher usage exactly.

**(d)** `DeveloperScreen.kt` — add the composable beside `DeveloperVerbositySetting` (`:892`):

```kotlin
/**
 * Which address family the control plane dials over, cycling Automatic ->
 * Force IPv4 -> Force IPv6 on tap.
 *
 * Unlike [DeveloperVerbositySetting] this row is ALWAYS live. That row is
 * inert without a device because there is no process to set a log level on;
 * this policy is process-global sdk state that is always answerable, and the
 * row has to work signed out and with the tunnel down -- those are the states
 * a user is in when the api is unreachable, which is the only reason to reach
 * for it.
 *
 * The value shown is the policy the sdk reports and never a demotion the sdk
 * made on its own: a row that read "Force IPv4" because the heuristic fired
 * could not be set back to Automatic. The demotion is named in the detail
 * line instead, so Automatic does not look identical whether it has fired or
 * not.
 */
@Composable
private fun DeveloperIpFamilySetting(
    policy: Long,
    status: String,
    onSelect: (Long) -> Unit,
) {
    val name = ipFamilyNameResource(policy)
    val detail = ipFamilyDetailResource(policy, status)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(nextIpFamilyPolicy(policy)) }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.72f)) {
            Text(
                stringResource(id = R.string.dev_ip_family),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                // the demoted variant is a %1$s format string; the quiet one
                // ignores the argument
                stringResource(id = detail, status),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
        Text(
            stringResource(id = name),
            style = MaterialTheme.typography.bodyLarge,
            // a forced family is not an ordinary setting value: it overrides
            // the judgement that keeps a user on a working path, and it is
            // what will strand them on the next network that lacks it
            color = if (clampIpFamilyPolicy(policy) == IP_FAMILY_AUTO) BlueMedium else TextDanger,
        )
    }
}
```

Then call it in the Diagnostics block immediately after `DeveloperVerbositySetting` (that block sits at `:128-203`, **before** the `if (!developerViewModel.connected) { ...; return }` guard at `:205` — keep it there, or the row vanishes in exactly the disconnected state it exists to rescue):

```kotlin
    DeveloperIpFamilySetting(
        policy = developerViewModel.ipFamilyPolicy,
        status = developerViewModel.ipFamilyStatus,
        onSelect = developerViewModel.setIpFamilyPolicy,
    )
```

- [ ] **Step 4: Verify by reading**

Gradle cannot run here. Verify:

```bash
cd android
grep -rn "<<<<<<<\|=======\|>>>>>>>" app/app/src/main/java/com/bringyour/network/ui/settings/ || echo "no conflict markers"
# brace balance on each edited file
for f in app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperScreen.kt \
         app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperViewModel.kt; do
  echo "$f: open=$(grep -o '{' $f | wc -l) close=$(grep -o '}' $f | wc -l)"
done
# every new string resource resolves
for s in dev_ip_family dev_ip_family_auto dev_ip_family_force4 dev_ip_family_force6 \
         dev_ip_family_auto_detail dev_ip_family_auto_demoted_detail \
         dev_ip_family_force4_detail dev_ip_family_force6_detail; do
  grep -q "name=\"$s\"" app/app/src/main/res/values/strings.xml && echo "ok $s" || echo "MISSING $s"
done
```

Confirm every SDK method named in the Kotlin exists in the generated binding, by checking the Go source:

```bash
cd ../sdk && grep -n "func SetControlIpFamilyPolicy\|func GetControlIpFamilyPolicy\|func GetControlIpFamilyStatus\|SetControlIpFamilyPolicy(policy int)" sdk.go device.go network_space.go
```

- [ ] **Step 5: Commit**

```bash
cd android
git add app/app/src/main/res/values/strings.xml \
        app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperScreen.kt \
        app/app/src/main/java/com/bringyour/network/ui/settings/DeveloperViewModel.kt \
        app/app/src/test/java/com/bringyour/network/ui/settings/IpFamilyTest.kt
git commit -m "feat: android developer control for the control-plane ip family policy"
```

---

## Cross-platform parity check

Run this after Task 14. Three cross-platform guards shipped on one platform and not the other earlier in this project — one of them became a Critical — so parity is an explicit deliverable, not an assumption.

- [ ] Both platforms cycle in the same order: Automatic → Force IPv4 → Force IPv6 → Automatic.
- [ ] Both rows are enabled with no device. (This is the one most likely to be copied wrong, because the neighbouring verbosity row does the opposite.)
- [ ] Both detail lines report a learned demotion under Automatic and ignore it under a force.
- [ ] Both read the policy back from the SDK rather than remembering the tap.
- [ ] Both write through the device when one exists and the network space when it does not.
- [ ] The label wording matches between `IpFamily.name` and the `dev_ip_family_*` strings.

## Final verification

- [ ] `cd connect && go build ./... && go vet ./... && go test ./...`
- [ ] `cd sdk && go build ./... && go vet ./... && go test ./...`
- [ ] `cd sdk/cgo && go run ./gen && git diff --exit-code cgo/include/` — headers already regenerated and committed
- [ ] iOS builds for device and simulator, `networkTests` pass, with real exit codes
- [ ] Android: state plainly that gradle was not run and why
- [ ] `grep -rn "DisableIpv4\|DisableIpv6" connect sdk ios android` returns nothing
