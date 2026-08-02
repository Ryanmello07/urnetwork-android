# Upstream reliability work vs the fork's redesign

2026-08-02. Comparison of urnetwork upstream main (d350362..e05ecee, 143
files, ~46K insertions, 5 checkpoint commits) against beta/custom-server at
92078c8. Both lines forked from the SAME commit (merge-base = d350362) and
rewrote ip_remote_multi_client.go concurrently: upstream ~2,493 changed
lines, ours ~4,474, touching an almost identical function set.

## Executive summary

Convergent intent, different sensors. Upstream detects dead peers with an
ACTIVE busy-flow control-ping probe (CPingBusyStaleTimeout 5s, default on:
stale acks trigger a probe; probe ack acquits, probe timeout convicts —
their detection 30.7s -> ~11.8s). We use passive sendStalled conviction
(3-4s, faster but convictable-by-congestion where their probe would acquit).
Upstream DELETED the pure no-receive-ack conviction outright (a transfer
send-ack now counts as liveness); we kept it behind a 20s bar +
>=2-destination corroboration + quarantine. They independently fixed the
same rejected-send accounting bug we did (addSendReject ~= our
addSendAbandoned) — convergent evidence both are right, and a guaranteed
conflict.

Flows are NOT sacred upstream: removeClient still tears down every flow
(now with a bounded recency-prioritized RST queue). They have no quarantine,
no verdict gates, no storm breaker, no QUIC rebind, no flow caps, no
effectiveTier, no rotation work, no prober, no serve-stale DoH.

Their strongest unique layers, orthogonal to ours and clean to merge: gVisor
tun fixes (a real PacketBuffer leak — every inbound packet stayed live for
the process lifetime; a lossless link endpoint replacing silent queue
drops), NetworkChanged() broadcast -> transport Kick (event-driven reconnect
on wifi<->cell), RTT-scaled resend floor (300ms), DoH hedging/warm/score
persistence (no serve-stale though), formation fast-poll (200ms first-load),
generator deadlines + observer workers so maintenance can never hang
(pinned by a 1,890-line stall test suite), host-state exculpation
(scheduler-pause detection + DegradedMode scaling), comparative blackhole
(silent first connect while siblings pass traffic cuts at 10s), suspect
routing (probe-failing exits sort last in the race), user-facing ICMP echo
path (default off; NOT a health prober — unrelated to our qualification
prober), a make-before-break transport migrator, and an env-gated recovery
measurement kernel (URNET_RECOVERY=1) that is the neutral instrument for
the APK bake-off — it can run against OUR build almost unchanged.

## Who is stronger where

- Busy-flow detection: split — ours faster (3-4s vs ~11.8s), theirs safer
  (probe acquits congested-but-alive). Ideal: our watchdog cadence firing
  THEIR probe.
- Soft-evidence removal: ours narrowly (they can never remove an exit that
  acks sends but blackholes all returns, except the SYN branch; we
  quarantine it) — but theirs provably cannot false-remove.
- Phone-side exculpation: complementary — our uplink-freshness gate catches
  network migration/tunnel silence; their scheduler-pause/degraded catches
  process suspension. Want both. Their probe WITHOUT our uplink gate would
  time out on every client at once during a live-but-silent uplink.
- Flows-are-sacred, storm breaker, QUIC rebind, flow caps, rotation,
  effectiveTier, dial-failure re-race, qualification prober, serve-stale
  DoH: ours, unique.
- Maintenance-stall hardening, tun/gVisor, event-driven reconnect,
  first-load, DoH resilience program, p2p/webrtc: theirs, unique or
  stronger.

## Merge assessment

ip_remote_multi_client.go conflict severity is near-total: a textual merge
is not viable. Their multi-client also cannot be cherry-picked alone (it
depends on their transfer.go raw-frame send path, their ip.go borrowed-path
parser, webrtc PrioritizePeer, ResidentMigrate frames).

Recommended strategy for the eventual merge:
1. Take their tree wholesale for the orthogonal files (tun, ICMP, mux/DoH,
   transfer/transport/p2p, util); re-apply our small diffs on top
   (serve-stale into their DoH; keep our TLS cache for transports, theirs
   for DoH).
2. For the multi-client: START FROM THEIR FILE (their skeleton — generation
   guards, bounded maintenance, context generators, callback workers — is
   the better base and is pinned by their stall tests), then port our layer
   onto it in order: verdict gates + quarantine wrapping isBlackholeAt;
   uplink/transport gates feeding their probe; storm breaker around every
   verdict addError; QUIC rebind into their removeClient ahead of the RST
   budget; effectiveTier/caps/rotation into their selection, absorbing
   suspect-last as a demerit; retire our passive 3s conviction into "probe
   trigger at 3s" (our latency, their false-positive safety).
3. Adopt their stall suite + recovery kernel EARLY — the kernel is the
   neutral bake-off instrument, the suite catches porting mistakes.
4. Expect our ~40 reliability test files to need mechanical rework.
This is a multi-day port, not a git merge.

## Upstream contribution candidates (ours they lack), by appeal

1. Quarantine-instead-of-execute (composes with their probe: timeout on a
   loaded exit quarantines rather than cancels)
2. Removal storm breaker (2-per-30s) — small, provable, covers the
   correlated-verdict case their pause detector cannot see
3. Per-destination corroboration (one dead site cannot convict)
4. Uplink-freshness gate + verdict-clock rebasing
5. Proactive QUIC rebind with affinity-group cohesion
6. Flow cap + least-loaded overflow; jittered rotation + standing reserve
7. Serve-stale DoH (RFC 8767)
8. Client-side qualification prober (no-convict guaranteed)
9. Dial-failure classification + per-flow re-race
Per the upstream-PR workflow: 2, 3, 7 are small enough to send now; 1, 4, 5
land best with/after the structural port.

## Build notes for the comparison APK

Upstream main was unbuildable as published: sdk main references
goidenticons.RenderPngV2, which exists in no published goidenticons ref
(their sibling workspace hides an unpushed change). The comparison build
(branch build/upstream-main) shims it — RenderPng has the identical
signature; v2 is a palette change per their own comment, so identicons
render in the v1 palette. Cosmetic only. Also fixed there and flagged for
our own workflow: ANDROID_NDK_HOME was passed from an env var the setup
action never sets; our fork's older Makefile silently tolerated the empty
path (skipping the .comment strip), upstream's hardened Makefile fails on
it.
