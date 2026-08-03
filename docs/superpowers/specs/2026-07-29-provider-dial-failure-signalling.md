# Provider dial-failure signalling — design

**Date:** 2026-07-29
**Status:** in implementation
**Repos:** `connect` (beta/custom-server), `sdk` (beta/custom-server), `android` (feat/vpn-reliability-and-dev-menu)
**Local clones:** `C:\Users\ryanm\Downloads\claude_sandbox_dashboard\connect`, `...\sdk`, `C:\Users\ryanm\Downloads\claude_sandbox_android`
**Toolchain:** `source C:/Users/ryanm/Downloads/claude_sandbox_dashboard/.tools/goenv.sh` (enables CGO so `-race` works)

## The problem, from the operator

Many mainnet providers resell cheap proxies with concurrency limits we neither
impose nor detect. The proxy — not our software — silently throttles or
refuses upstream connections when the provider goes over. Our provider code
turns that into an **unattended blackout**: the flow dies with no signal to
anyone.

## Root cause, verified in code and on device

`connect/ip.go`, both dial sites (grep `"connect to upstream before sending
the syn+ack"` for TCP, `"[init]udp connect error"` for UDP): when
`DialContext` fails, the provider logs at V(1) — off in the field — and
**returns silently**. The SynAck is deliberately only sent after a successful
upstream dial, so the source's SYN goes unanswered and it sits in
syn-retransmit backoff.

Measured on device, every reported hang landed exactly on a SYN retransmit
boundary: 3s (2 retries), 7s (3), 15s (4), 31s (5), 63s (6). Meanwhile the
client sees acknowledged transport sends (the provider is alive) and zero
destination data, and after `BlackholeReceiveTimeout` (20s) removes the
provider — destroying its *working* flows too. 41 of 51 removals in a 2-hour
capture carried this exact signature (`Blackhole no-receive-ack`,
`sendAckCount` 2–602, never 0).

## Design: three layers

### Layer 0 — shared packet formats (DONE, already in `connect`, do not re-implement)

- `ipOosUnreachable(ipPath)` (`ip_packet.go`) now accepts **TCP** as well as
  UDP. UDP keeps port-unreachable (teardown, delivery semantics). TCP gets
  **host-unreachable v4 code 1 / v6 no-route code 0** — deliberately distinct
  from teardown's port-unreachable. Embedded datagram mirrors the original
  egress packet incl. the source's own TCP sequence number
  (`ipPath.SequenceNumber`).
- `ipParseIcmpUnreachable(packet) (*IpPath, bool)` (`ip_packet.go`) recognizes
  an ICMP dest-unreachable (v4 type 3 / v6 type 1, any code) and returns the
  **original egress-direction path** recovered from the embed — exactly the
  `ip4PathUpdates`/`ip6PathUpdates` map key. Round-trip property is pinned by
  `TestIcmpUnreachableRoundTripTcp` (`ip_packet_icmp_parse_test.go`).
- `ParseIpPath` still rejects ICMP ("No support for protocol 1"), pinned by
  `TestParseIpPathStillRejectsIcmp`. Old clients therefore DROP the new signal
  harmlessly at the channel parse — rollout is safe in both directions.
- Settings/metrics stubs already exist (do not re-add, only consume):
  - `MultiClientSettings.DialFailureRerace bool`, default **true**
  - `ReliabilitySettings.DialFailureRerace bool`, copied by
    `ReliabilitySettingsFrom`
  - `reliabilityMetrics.dialFailureIntercepted()`, `.flowReraced()` (nil-safe)
  - `ReliabilityMetricsSnapshot.DialFailuresIntercepted`, `.FlowsReraced`
    (uint64; reset() clears them)
  - `ExitInfo.DialFailureCount int` (currently always 0 — Package B populates)

### Layer 1 — provider answers instead of going silent (Package A)

In `connect/ip.go`, at BOTH dial-failure sites:

- **TCP** (`err` from `tcpBufferSettings.DialContext`):
  - `errors.Is(err, syscall.ECONNREFUSED)` → the destination itself refused;
    send `ConnectionState.RstAck()` (builder at ~`ip.go:3208`, same
    family/orientation as `SynAck`). This is honest TCP semantics and is
    forwarded to the app.
  - anything else (timeouts, `EMFILE`, `ENFILE`, `EADDRNOTAVAIL`, unreachable,
    unrecognized) → capacity-class; send
    `ipOosUnreachable(self.IpPath())` (TCP variant, host-unreachable).
    Unrecognized errors default to capacity-class deliberately — the signal is
    intercepted by new clients and dropped by old ones, so a misclassification
    is cheap; Windows errno mapping (`WSAECONNREFUSED`) is best-effort via
    `errors.Is`.
- **UDP**: always `ipOosUnreachable(self.IpPath())` (port-unreachable; UDP
  "dial" cannot produce a meaningful ECONNREFUSED at connect time).
- Deliver via the **same `receive(...)` callback that carries the SynAck** —
  both builders emit packets in the from-destination orientation the SynAck
  uses. Then `return` as before (no socket exists).
- Extract the decision into a small pure helper, e.g.
  `dialFailurePacket(ipPath *IpPath, err error) ([]byte, bool)`, so the
  classification is unit-testable without a live dial.
- Keep the existing V(1) log lines; add the signal unconditionally.

### Layer 2 — client intercepts, re-races silently, learns (Package B)

**Intercept** at the channel receive loop
(`ip_remote_multi_client.go` ~5344, the `// else not an ip packet, drop`
branch): when `ParseIpPath` fails, try `ipParseIcmpUnreachable`. On a match,
invoke a new callback plumbed into the channel (mirror
`clientReceivePacketCallback`): `dialFailureCallback(sourceClient, egressIpPath)`.

**HARD REQUIREMENT:** an intercepted ICMP must NOT call `addReceiveAck` or
`addReceiveSyn`. A provider failing every dial must not look healthy to
`detectBlackhole` because its failure signals count as received data.

**Parent handler** (`RemoteUserNatMultiClient`):
1. Look up the flow: under `stateLock`, `ip4PathUpdates[egressIpPath.ToIp4Path()]`
   (or v6). Conditions to act: update exists, `update.client.Load() ==
   sourceClient`, and the flow has received no inbound data yet (new
   `receivedInbound` flag on `multiClientChannelUpdate`, see below).
2. If matched and `reliabilitySettings().DialFailureRerace`:
   - unbind exactly the way `removeClient` does per-update, minus teardown:
     remove `update` from `clientUpdates[sourceClient]`,
     `update.client.Store(nil)`. Do NOT cancel the update.
   - `reliabilityMetrics.flowReraced()`. Swallow the packet (never forwarded).
   - The app's own retransmit (SYN ≈1s, QUIC PTO, DNS retry) triggers
     `sendPacket` → nil client → `raceClients` → another exit. Recovery cost
     ≈1s instead of 3–63s.
3. If matched and rerace disabled: forward the raw ICMP to the app via
   `receivePacketCallback` with the egress `ipPath` (same convention as
   teardown packets in `removeClient`). Visible but fast failure.
4. Unmatched (no update / different client / already-established): drop
   silently. Always `reliabilityMetrics.dialFailureIntercepted()` on any
   intercepted ICMP, matched or not.

**`receivedInbound`**: `atomic.Bool` on `multiClientChannelUpdate`, set where
inbound packets resolve their update (`receiveClientPath`/`receiveUpdate`
path) for non-ICMP packets. Guards against a stale dial-failure unbinding an
established flow.

**Strike accounting** on `multiClientChannel` (guard with its `stateLock`):
- `addDialFailure()` on each matched failure; `addConnectSuccess()` where
  `receivedInbound` flips false→true for a flow on this channel.
- Sliding 60s window (timestamp slices pruned on access is fine).
- `dialStarved() bool`: ≥3 failures in window AND 0 successes in window.
- `DialFailureCount` for `ExitInfo`: count of failures in the window;
  populate in `Exits()`.

**Warning, not removal:** wire `dialStarved()` into the resize pass so the
channel gets `setWarning(true)` (stops NEW flows; existing flows keep
working). **It must not accelerate removal** — do not feed it into
`unhealthyDuration` or the remove decision; a dial-starved provider's
established flows are its only working asset and destroying them is the
current bug. Investigate the existing warning mechanism around
`StatsWindowWarnUnhealthyDuration` / `setWarning` and integrate at the
warning site only. Oscillation (warned → drains → healthy → warned) is
acceptable v1 behavior.

**Tests** (all `-race`): parent-handler fixture tests in the style of
`flowCapTestParent` (match/unbind/swallow; unmatched drop; established-flow
guard; toggle-off forwarding), `dialStarved` windowing, and a channel-level
test that an intercepted ICMP does not bump `receiveAck` counters if
feasible. Do NOT restate production logic in tests — call the shipped
functions (see `TestDetectBlackholeUsesTheReasonAndOverride` for the
pattern of pinning a call site when unit isolation is impossible).

### Layer 3 — SDK + Android surface (Package C)

`sdk/reliability_controls.go` (CRLF file, mirror existing patterns exactly):
- `ReliabilitySettings.DialFailureRerace bool` — wire both directions
  (`reliabilitySettingsFromConnect`, `toConnect`).
- `ReliabilityMetrics.DialFailuresIntercepted int64`, `.FlowsReraced int64`
  — populate in `GetReliabilityMetrics` (int64, gomobile binds no uint64).
- `Exit.DialFailureCount int32` — populate in `GetExits`.

Android (`app/app/src/main/java/com/bringyour/network/ui/settings/`, CRLF):
- `DeveloperViewModel`: `setDialFailureRerace: (Boolean) -> Unit` via the
  existing `update {}` helper.
- `DeveloperScreen`: toggle in Behaviour section — label
  `dev_dial_failure_rerace` "Retry refused connects elsewhere", detail
  `dev_dial_failure_rerace_detail` "When a provider can't reach a site, move
  the connection to another exit instead of letting it hang". Two metric rows
  in Measurements: `dev_dial_failures` "Provider connect failures" (value =
  dialFailuresIntercepted) and `dev_flows_reraced` "Moved to another exit"
  (value = flowsReraced). Show an exit's `dialFailureCount` in
  `DeveloperExitRow` when > 0 (small muted text, e.g. "3 failed dials").
- `strings.xml`: CRLF, alphabetical-ish near the other `dev_` keys, no
  duplicates.
- gomobile naming trap: only Go `Get*` methods become Kotlin properties;
  everything else is a method call. Struct fields bind as properties
  (`s.dialFailureRerace` works, mirroring `s.udpTeardownSignal`).
- Do NOT run gradle: the local AAR is stale (no local gomobile), so Kotlin
  references to new SDK symbols cannot compile locally. Verification is the
  cross-reference check (every `R.string.*` exists, every
  `developerViewModel.*` member exists, every `DeveloperViewModel.*` constant
  exists) plus CRLF consistency (`tr -cd '\n'` count == `tr -cd '\r'` count).

## Rollout matrix

| | old provider | new provider |
|---|---|---|
| **old client** | status quo | signal dropped at `ParseIpPath` → status quo |
| **new client** | no signal → blackhole path (20s) still catches it | intercept → ~1s re-race |

No protocol change: the signal rides `IpPacketFromProvider` as a raw packet.

## Explicitly out of scope

- Server-side concurrency scoring / `FindProviders2` API changes (needs
  server access; the operator will grant it next).
- The selection-vs-assignment TOCTOU on `MaxFlowsPerExit` (documented in
  `underFlowCap`).
- Slow-start learned per-provider ceilings — the strike data this design
  collects is its input, but the ceiling mechanism waits for the TOCTOU fix.

## Traps (all learned this session, all real)

- Heredoc Python converts leading tabs to spaces — use the Edit tool for Go.
- Android/sdk files are CRLF; `strings.xml` must stay uniformly CRLF.
- `gofmt -w` ONLY files you touched (repo is not uniformly formatted).
- Run the FULL package suite before declaring done; targeted `-run` sweeps
  have missed regressions three times. Pre-existing failures to ignore:
  `TestCombineTrim`, `TestPump`, `TestPumpTrim` (`transport_pt_queue_test.go`).
- Never assert on a reimplementation of production logic — call the shipped
  function, and where reachability matters, pin the call site.
- Do not commit or push — the session owner verifies trees and commits
  (`.git/index` has been destroyed by a sync client here before).
