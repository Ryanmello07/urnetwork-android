# Control-plane IPv4/IPv6 selection: reactive demotion and a developer force

Date: 2026-09-01
Status: implemented on `beta/custom-server`, not yet pushed or upstreamed.
  Implementation amended this document three times — the two-space restore limitation
  (§3), the TLS-timeout out-of-scope entry, and the `sync.Once` description — each
  because the code proved a claim here wrong. Those corrections are kept inline rather
  than rewritten away, so the reasoning that failed is still readable.
Repos touched: `connect`, `sdk`, `ios`, `android`

## Problem

Some users cannot reach the urnetwork API. Their ISP's IPv6 path to the
service's Hurricane Electric address does not work, and the client has no way
to notice or route around it.

Confirmed from outside the codebase:

```
api.bringyour.com      A 65.49.70.84   AAAA 2001:470:99:5870:e643:4bff:fe89:2bca
connect.bringyour.com  A 65.49.70.70   AAAA 2001:470:99:56:e643:4bff:fec3:8446
```

`2001:470::/32` is Hurricane Electric. Both the API and the platform control
websocket publish AAAA records in it. A user whose IPv6 path to HE is broken
loses both.

### What is already handled

Go's `net.Dialer` performs RFC 6555 Happy Eyeballs unconditionally for network
`"tcp"` — no `DualStack` flag, no opt-in (`net/dial.go:551`, gated on
`FallbackDelay >= 0`, whose zero value qualifies). Nothing in `connect` or
`sdk` sets `FallbackDelay`, hardcodes `tcp4`/`tcp6` on an API dial, installs a
custom resolver on mobile, or pre-resolves the API hostname. Measured against a
synthetic blackhole: 302 ms to recover onto IPv4, versus 4 s with the race
disabled.

So a **pre-connect** IPv6 blackhole is already handled today, and "add Happy
Eyeballs" is not the work.

### What is not handled

Happy Eyeballs races only the TCP handshake. The failure a Hurricane Electric
tunnel is known for is **post-connect**: a 1480-byte MTU with ICMPv6
Packet-Too-Big filtered. SYN and SYN-ACK are small and pass, so IPv6 *wins* the
race; the TLS ServerHello is large and is dropped. The connection then stalls
to `TlsTimeout` (15 s) with no fallback to the other family.

This hypothesis fits the reported symptom (a long stall rather than a fast
error) and the known behaviour of HE tunnels. It is **not** confirmed by a
measurement from an affected device, and the codebase currently emits no signal
that can distinguish it from the pre-connect case. Closing that gap is part of
this work (§4), not a follow-up.

### Contributing defects found

1. **The force lever exists and is dead.** `ConnectSettings.DisableIpv4` /
   `DisableIpv6` (`connect/net.go:59`) is a complete, correct implementation.
   Nothing in `connect`, `sdk`, `ios` or `android` ever assigns either field.
2. **It would only half-work if set.** `newNormalDialTlsContext`
   (`net_http.go:218`) takes a fast path when `ProxySettings` and
   `DialContextSettings` are both nil — precisely the mobile production
   configuration — handing `net/http` a raw `tls.Dialer` and bypassing
   `ConnectSettings.DialContext` entirely. The fragment/reorder dialers *do*
   honour it. Setting the flags today produces a strategy that races a
   forced dialer against an unforced one, nondeterministically.
3. **A bad choice is sticky for 90 s.** HTTP/2 multiplexing plus a 90 s idle
   pool plus `HTTP2Config.SendPingTimeout` left at zero (Go: *"If zero, no
   health check is performed"*). A pooled connection that goes dark is never
   proactively detected.
4. **H3/QUIC has no family race at all.** `resolveEgressUDPAddr`
   (`egress_dial.go:79`) returns exactly one address. It is the fallback
   carrier meant to rescue a stalled H1, and it dies the same way.
5. **None of this is diagnosable from a bundle.** `logControlDialResult`
   (`egress_dial.go:193`) returns early unless `egressBound()`, which is always
   false on Android and iOS. And the log redactor collapses IPv4 and IPv6 into
   the same `<addr:hex>` token, so even an address would not survive a redacted
   export — the mode users are asked to send.

### Out of scope

- **The VPN data plane.** Already IPv4-only by explicit design
  (`PacketTunnelProvider.swift:995`, `VpnPacketFlowConfiguration.kt:24`,
  `Tun.dialContext` returning `EAFNOSUPPORT` for `tcp6`/`udp6` with a test
  pinning it). Separate settings objects; cannot be regressed by this work.
- **Racing both families in the H3/QUIC dial.** A materially larger change to
  QUIC dialing, and unnecessary once H3 inherits H1's demotion (§2, site 3).
  Deliberately excluded, not overlooked.
- **Shortening the 15 s TLS handshake timeout — declined, and then overtaken
  by what implementation found.** The original ruling was that shortening
  `TlsTimeout` would risk false-positive demotion for users on genuinely slow
  links. That reasoning holds, and `TlsTimeout` is untouched at 15 s.

  What the ruling missed is that with no bound at all the reactive demotion
  **cannot fire on either production control path**. Both callers hand the
  shared helper a deadline at or below `TlsTimeout`: on the API path
  `http.Client.Timeout` is `RequestTimeout` (15 s, and it starts before the
  dial); on the platform control websocket gorilla caps its dial context at
  `HandshakeTimeout` (5 s). A handshake bounded only by `TlsTimeout` therefore
  never reaches a timeout of its own — the caller's deadline always arrives
  first, which the helper correctly refuses to read as evidence about a family,
  and which leaves no budget to retry with either way. As specified here, the
  mechanism this document exists for would never have engaged in production.

  What shipped instead is a **floored** bound.
  `ControlFamilyFirstHandshakeTimeout` (8 s) bounds the FIRST handshake of a
  control dial, and only while the caller still has
  `bound + ControlFamilyRetryReserve` (5 s) remaining when that handshake
  starts. Below that threshold the first handshake keeps the caller's whole
  budget and the helper behaves exactly as it did with no bound at all —
  because a bound that produces a timeout with no room to retry is strictly
  worse than none: it converts a request that would have kept waiting into one
  that fails early and learns nothing. See `connect/control_family_dial.go`,
  with both values as `ConnectSettings` fields in `connect/net.go`.

  **A floor is not the thing that was declined.** The false-positive risk came
  from tolerance that scales DOWN with the caller. An earlier attempt on this
  branch did exactly that — it halved whatever the caller had left, which gave
  the control websocket 2.5 s, a figure a congested mobile link reaches with a
  pinned P-384 chain and nothing wrong, and it shrank hardest precisely where
  the budget was already smallest. A fixed floor cannot behave that way, and
  its size is derived rather than picked: gorilla wraps the *entire* websocket
  dial — TCP connect, TLS handshake **and** HTTP upgrade — in
  `HandshakeTimeout`'s 5 s, and every production control-websocket reconnect
  completes all three inside it. 8 s for the handshake alone is 60 % more than
  a whole successful control dial is allowed. A handshake past 8 s is one this
  product already treats as failed everywhere else.

  That same threshold is what keeps the bound off the control websocket: its
  5 s is under 8 s + 5 s, so that path is never bounded and its handshake
  tolerance is left exactly as it was. It needs no retry of its own — the
  ledger is process-global (§1), so what the API path has the budget to learn
  is already in force for the websocket, the H3/QUIC name path and the
  extenders.
- **`nettest`'s two existing call sites** (`net_http.go:1178-1179`, extender
  selection). Untouched.

### A cheaper fix that lives outside this repo

Removing the `AAAA` records from `api.bringyour.com` and
`connect.bringyour.com` would fix every affected user immediately, including
those on builds that will never be updated. This client work is still worth
doing for robustness, but it is slower to reach users and should not be
mistaken for the fastest remedy available.

## Design

### §1 Policy object and state model

New file `connect/control_family.go`, following `connect/egress.go`'s shape:
process-global, documented, inert until used.

```go
type IpFamilyPolicy int32
const (
    IpFamilyAuto   IpFamilyPolicy = 0  // Happy Eyeballs + reactive demotion
    IpFamilyForce4 IpFamilyPolicy = 1
    IpFamilyForce6 IpFamilyPolicy = 2
)
var controlIpFamilyPolicy atomic.Int32   // the developer setting
var controlFamilyDemotion struct {       // the learned memory
    mu         sync.Mutex
    generation uint64                    // bumped on NetworkChanged()
    demoted    map[int]demotion          // 4|6 -> {until, strikes}
}
```

The setting and the learned memory are independent state and never mix.

A single resolver `controlDialNetwork(network string) string` maps `"tcp"` to
`"tcp"`, `"tcp4"` or `"tcp6"` (and likewise for `"udp"`). Every dial site calls
it. Precedence: an explicit force wins outright; otherwise a live demotion
narrows the network string; otherwise `"tcp"`, and Go races as it does today.

**Demotion trigger — deliberately narrow.** All of the following must hold:

- TCP connect succeeded over family F (F is read from `conn.RemoteAddr()`)
- the TLS handshake then failed
- that failure was a timeout — `net.Error` with `Timeout() == true`

Certificate errors, ALPN mismatches, RSTs and refusals are not path problems
and must never demote. Getting this wrong blames IPv6 for a server
misconfiguration and steers every user off a healthy path.

**Backoff, not a fixed cooldown.** First demotion 5 minutes, doubling on each
re-confirmation, capped at 6 hours. A persistent HE-tunnel problem stops
costing the user anything within a couple of strikes; a transient blip recovers
quickly. Any `NetworkChanged()` (`util.go:170`, already fired by the host on
NWPathMonitor / ConnectivityManager updates) bumps the generation and clears
the ledger — new path, new reality.

**The guard against a self-inflicted outage.** Never demote family F when the
other family is unusable. On a true IPv6-only network with no CLAT, demoting
IPv6 would take the user fully offline. 464XLAT is unaffected either way, since
the CLAT presents an IPv4 address.

This guard needs its own capability probe. `nettest.SupportsIPv4/6` memoizes
inside `golang.org/x/net/nettest` and cannot be re-probed from outside, so it
would go stale across a wifi↔cellular switch — dangerous in exactly the wrong
direction. The local probe is injectable for tests and re-evaluated on
`NetworkChanged()`.

An explicit force is honoured even against this guard: it is a developer
setting whose entire purpose is to override judgement. It is logged loudly.

### §2 Dial sites

Three sites, not four. `WsDialer` (`net_http.go:1404`) reuses the same
`self.dialTlsContext` the HTTP client uses, so the API and the platform
websocket are one fix.

**Site 1 — `newNormalDialTlsContext` (`net_http.go:213`).** Delete the
fast-path branch at `:218-224` so every dial takes the explicit path already
written below it. This single deletion covers API HTTPS and the platform
websocket, closes the split-brain against the fragment/reorder dialers, and
exposes the TLS handshake as an observable step.

Behavioural change worth naming: `tls.Dialer.DialContext` applies one deadline
across TCP+TLS, while the explicit path applies `ConnectTimeout` to the dial and
`TlsTimeout` to the handshake separately. That is the behaviour we want, but it
applies to every user, not only affected ones.

**Site 2 — `ConnectSettings.DialContext` (`net.go:67`).** Replace the
`DisableIpv4`/`DisableIpv6` reads with `controlDialNetwork()`. The switch body
is already correct and stays as-is. This covers the resilient dialers, the
extender dialer, and — because the policy is process-global rather than a
struct field — the log-upload client at `net_http.go:1861`, which builds its
own `DefaultConnectSettings()` and would otherwise stay ungoverned. That is the
one API call a user makes *while reporting this bug*.

The two dead bools are **deleted** rather than left in place. They are
unreferenced across all four repos, and leaving a second, subtly different
family mechanism beside the real one is how the next person ships a bug.

**Site 3 — `resolveEgressUDPAddr` (`egress_dial.go:79`), the H3/QUIC path.**
Make it policy-aware: filter the resolved list by an active force, and prefer a
family that is not demoted. H1 is tried first
(`DefaultTransportModePreferences` H1=1, H3=2), so by the time H3 runs, H1's
failure has usually already populated the ledger and H3 inherits the learning
for free.

**The retry** lives in one shared helper — "dial, then hand off to a handshake
step" — used by sites 1 and 2, so the logic exists once and is unit-testable
without a network. On a qualifying timeout it records the strike and re-dials
the other family in place; the caller receives a working connection.

**Exactly one retry**, and only ever to the *other* family. If the retry also
fails the original error is returned unwrapped. This is deliberate: the dial
already sits inside `ClientStrategy`'s own serial/parallel dialer evaluation
under a 15 s `RequestTimeout` budget, so a helper that retried more than once
could consume the whole budget alone and starve the other dialers — the
existing failure shape described in the Problem section. A second failure over
the second family is also not a family problem, and treating it as one would be
wrong.

**HTTP/2 health check.** Set `SendPingTimeout`/`PingTimeout` on the control
plane's `HTTP2Config` so a pooled connection that goes dark is detected in
seconds rather than hanging every API call for up to the 90 s idle window. This
addresses a failure no dial-time logic can catch, and helps any dead-path
cause, not only IPv6. Note the block building `HTTP2Config` is currently guarded
on the mobile memory budget (`net_http.go:1380`), so desktop builds none at
all; that guard changes too.

### §3 SDK surface, persistence, and the three iOS regimes

```go
const ( IpFamilyPolicyAuto = 0; IpFamilyPolicyForce4 = 1; IpFamilyPolicyForce6 = 2 )

// the developer setting only. Never reflects a learned demotion, so the UI row
// round-trips exactly what was set and Auto always reads back as Auto.
func SetControlIpFamilyPolicy(policy int)   // process-global, clamped
func GetControlIpFamilyPolicy() int

// the learned state, for the detail line. Empty string when nothing is
// demoted. Separate from the setting on purpose: a row that showed "Force
// IPv4" because the heuristic fired would be unsettable back to Auto.
func GetControlIpFamilyStatus() string
```

Plain `int` constants rather than a named Go type, matching `LogVerbosity*`,
for gomobile. `Device.SetControlIpFamilyPolicy` / `GetControlIpFamilyPolicy` on
the interface, whose two implementations are compile-time asserted so the
compiler catches a missed one.

`DeviceLocal` sets the global and persists. `DeviceRemote` reproduces
`SetLogVerbosity` (`device_rpc.go:5706`) exactly and for the same reason: set
this process's value, persist to this process's own container, hosted-guard the
crossing, RPC via `rpcCallVoidAllowMissingMethod` so an older peer refuses
without tearing down the session, and queue for replay when the tunnel is down.

**The restore point is a deliberate departure from that template.** The
persisted policy is applied in `newNetworkSpace` (`network_space.go:186-191`,
after `asyncLocalState` is built and before `newApi`) — *not* in the Device
constructors where `applyPersistedLogVerbosity` is called
(`device_local.go:1353`, `device_rpc.go:519`).

The reason is concrete. A user who forces IPv4, kills the app and relaunches
hits the **login** API call before any Device exists, and that is precisely the
call they are stuck on. Restoring at Device construction would leave the
setting inert during the one request that matters — while the menu read back
the correct value the whole time.

That placement also resolves iOS's three regimes without any RPC, because
`newNetworkSpace` runs in both processes:

| Regime | Who dials | Covered by |
|---|---|---|
| 1. Pre-login, no device | app process | NetworkSpace-construction restore |
| 2. Device + rpc up | extension | its own restore; live changes via RPC |
| 3. Device, tunnel down | app process | restore + direct set |

Regime 3 matters most and is easiest to get wrong: it is the disconnected state
a user is in when they open the Developer menu to fix this very problem.

Android is single-process, so the split does not arise there.

**Stated limitation, and a correction to it.** The runtime policy is
process-global while `LocalState` is per-network-space.

This section originally said that with two spaces configured "whichever
constructs last wins the restore", and called that theoretical because one
space is active at a time on mobile. **That was wrong**, and a reviewer caught
it during implementation. The manager constructs *every* stored space before
selecting the active one, so the last entry in the stored slice won — not the
active space — and the restore re-fired on every `updateNetworkSpace`, which
iOS calls at boot and on every custom-server import. On `beta/custom-server`,
whose whole purpose is that a custom API host and the production host coexist,
two configured spaces is the normal case rather than an edge case.

The implemented behaviour is a once-per-manager restore applied from the active
space at manager load, from `SetActiveNetworkSpace` — which covers both a fresh
install whose `.network_spaces` index is missing or unreadable and an
in-session space switch — and from `updateNetworkSpace` **only when no space is
active at all**. That last case is not a loose end: the iOS network extension
builds its own manager and never calls `SetActiveNetworkSpace`, so its active
space is permanently nil and this is its only restore — regime 2 in the table
above. Gating strictly on the active space would have left the extension
dialing under Auto until the app's RPC reached it.

The guard is a mutex and a bool, **not** a `sync.Once`, and the difference is
load-bearing rather than stylistic. A `sync.Once` is spent by being *entered*,
so the first space the restore is offered would close it whether or not that
space had anything to restore. With a custom API host configured alongside the
production one — two spaces, the normal case on this branch — the bundled space
with nothing persisted is routinely the first one offered, and it would leave
the space that actually holds the user's force unable to restore it for the
rest of the session. The bool is therefore set from what
`NetworkSpace.restoreControlIpFamilyPolicy` reports, so the guard is spent only
when a policy was **actually applied**. Once one has been, it is closed for
good, which is the half this must not lose: `updateNetworkSpace` rebuilds a
space on every launch and every custom-server import, and a second apply there
would re-impose the persisted value over one an embedder had just set. The lock
is its own rather than `stateLock`, because `load` and `updateNetworkSpace`
both reach the restore while already holding `stateLock` and `sync.Mutex` is
not reentrant.

What remains true is the narrower original point: the runtime policy is
process-global, so the active space's persisted value is the one in force for
the whole process.

### §4 Developer controls and the evidence trail

**Both platforms** get a tri-state row cycling Auto → Force IPv4 → Force IPv6,
modelled on `DeveloperVerbositySetting` (`DeveloperScreen.kt:892`) and its iOS
counterpart, including the read-back discipline: show the value the SDK
reports, never the one last tapped, so a clamped or refused set is visible
rather than assumed.

**One thing it must not copy.** The verbosity row is inert with no device
("Unavailable") because there is nothing to set a level on. The family row does
the opposite: it reads and writes the process-global
`GetControlIpFamilyPolicy()`, which always has a value, so it works with no
device and with the tunnel down — regimes 1 and 3, exactly when a user needs
it. A device-gated row would be inert in the disconnected state it exists to
rescue.

The detail line names what is in force *including any learned demotion* — e.g.
"Auto — IPv6 demoted for 4m (2 strikes)". Otherwise Auto looks identical
whether the heuristic has fired or not.

**Evidence trail.** Drop the `egressBound()` gate in `logControlDialResult` for
a family-only line, and emit one line per control-dial outcome at default
verbosity: family, tag (`api`/`platform`), and whether it was forced, demoted
or raced — plus a line when a demotion is recorded or expires.

The family goes out as a **literal** `family=4` / `family=6` token, never
derived from the address, so redaction cannot erase it. Verified: neither
`redactIPv6Pattern` (requires colons) nor `redactAddrBytesPattern` (requires
brackets) matches `family=4`.

This closes the loop on the unconfirmed hypothesis. The next bundle from an
affected user confirms or refutes it. If the stall turns out to be pre-connect
after all, the force setting still helps and the demotion logic simply never
fires.

### §5 Testing

All deterministic and offline. `net_http_seam_test.go` already proves the
harness: `httptest.NewTLSServer` plus an injected `DialContextSettings` that
records what it is asked for. Plain stdlib `testing`, no testify, matching both
repos.

**The three that carry the weight:**

1. **Demotion narrowness** — feed timeout, certificate error, connection
   refused, RST and ALPN-mismatch failures; assert only the timeout demotes.
2. **Bypass closure** — extend the seam test to the mobile configuration
   specifically (no proxy, no `DialContextSettings`) and assert the family seam
   is still consulted. Direct regression guard for the defect that left the
   existing knob dead, and for the failure mode where a setting reads back
   correctly while changing nothing.
3. **IPv6-only guard** — capability probe reporting no IPv4 plus a proven IPv6
   failure: the demotion must be refused.

**Supporting:** the policy resolver as a table-driven pure function; backoff
doubling and the 6-hour cap against an injected clock (no sleeping);
`NetworkChanged()` clearing the ledger; the in-place retry returning a working
connection to a caller that never sees the failure; SDK persist/restore
round-trip and clamping; and the restore being in force *before any Device
exists*, since that departure is invisible if tested only through a Device.

One cheap, disproportionately valuable test in `sdk/diagnostics_redact_test.go`:
`family=4` survives `redactLine`. If redaction ever eats that token, every
bundle silently loses the only evidence that makes this diagnosable, and
nothing else would catch it.

### Verification reach

- `connect`, `sdk`: built and tested locally.
- `ios`: built and tested locally, simulator and device.
- `android`: **cannot be compiled on this machine** — no JDK, Android SDK or
  NDK. Verification is by reading, type-checking against the Go source, and
  upstream CI, which is its first real compile.

**The limit no test reaches:** none of this reproduces an actual Hurricane
Electric PMTU blackhole. The tests verify the mechanism — that a proven
post-connect timeout demotes, that a force is honoured on every path, that the
retry recovers. They do not verify the diagnosis. Only a bundle from a
genuinely affected user does that, which is why §4 ships with the fix rather
than after it.
