# Connect stability redesign — flows are sacred

2026-08-01. Synthesized from five parallel review lenses (uplink/transport,
provider health model, session continuity, verdict evidence, structural
fresh-eyes) run against connect @ beta/custom-server after beta-121. Goal set
by the owner: interruptions comparable to a traditional single-hop VPN
(WireGuard/Proton); stay connected to healthy providers, route away from
unhealthy ones; tier must deprioritize dial-failing providers.

## The diagnosis, in three structural facts

1. **The verdict machinery outruns transport-death detection.** Verdicts
   convict at 3-5s (sendStalled, no-send-ack) while a dead platform websocket
   takes 10-30s to notice (TCP keepalive 5s/5s/1, ReadTimeout 30s). Every
   exit holds its own websocket, so a local network event kills ~8 stateful
   connections at once — and the verdicts execute innocent providers before
   the client knows its own carrier died. Measured: 7 exits executed in 79s
   during a Wi-Fi migration, every verdict `recv 0/0B`, 106 flows torn down.
   Nothing in the codebase distinguishes "provider silent" from "my uplink is
   silent."

2. **Soft evidence carries execution authority.** `no-receive-ack` (provider
   demonstrably alive, destination quiet) and the stats-window "unhealthy"
   classification both execute exits, though the code's own comments call the
   signal ambiguous. Historically this class was ~1 removal per 18s under
   load. Hard evidence paths (transport dead, no-send-ack, sendStalled,
   cping) already catch every genuinely dead exit without consulting the
   user-traffic window.

3. **Capacity and rotation machinery is flow-blind.** The window layer has no
   flow-count input anywhere: `collapseLowestWeighted` kills exits whose
   flows are merely idle (weight = 30s throughput, so an open SSH session
   scores 0); expand's same-clientId replacement cancels live channels; all
   client lifetimes expire together so rotation is a synchronized hourly
   event. Found while reading: the speed window's rank-0 client never
   actually warns at lifetime (remove-rank math with FixedWindowSize=1), so
   rotation is silently ineffective for the speed window's best exit.

The recent fixes (flow cap, tier crossing, least-loaded overflow, dial
re-race incl. QUIC, teardown packets) all shrink the cost of an execution.
This redesign makes execution rare.

## The invariant (adopted from the structural review, confirmed by health + verdict lenses)

> An exit carrying at least one pinned flow may only be closed on hard
> evidence — transport dead, no-send-ack, honest sendStalled, cping dead, or
> explicit user action. Every soft classification (no-receive-ack,
> stats-window unhealthy, capacity collapse, lifetime, dial starvation)
> demotes to warning: no new flows, established flows keep running, removal
> deferred until flow count reaches zero or the warning has been continuous
> past a sustained bound (the existing 60s StatsWindowKeepUnhealthyDuration).

The machinery already exists and is used correctly twice (dial-starved warns
but never removes; MaxClientLifetime drains rather than kills). The refactor
makes it the rule.

## Phase A — stop convicting the innocent (pure connect, first build)

A1. **Telemetry prerequisite** (S): transport lifecycle events + counters
    `verdictsHeldUplinkStale`, `verdictsHeldTransportDown`, `removalsDeferred`
    in reliability_metrics; add `dsts=%d` (distinct send destinations) and
    uplink-freshness-age to the existing verdict log line. Nil-receiver-safe.
    Every later item is judged by these signals.
A2. **Stall-detector honesty** (S): `SendDetailedWithAck`'s failure returns
    must undo `addSend` accounting (decrement nack counts, clear
    pendingSendTime at zero) — today a backpressure burst can convict an
    innocent idle exit as stalled. Prerequisite before stall verdicts gain
    any more authority.
A3. **Uplink-freshness gate** (M): atomic `lastIngressTime` stamped in
    `clientReceivePacket` (coarsened ~100ms); dial-failure ICMP intercepts
    also stamp it (proof the uplink works). Receive-branch verdicts hold
    while the whole tunnel is silent. Non-negotiable details from review:
    clock REBASE on resume (`max(firstSendNackTime, uplinkFreshSince)`) so
    held verdicts don't all fire at once on unfreeze; bypass when <2 channels
    have send activity (single-exit degenerate case keeps today's behavior);
    hard cap on total hold (~60s) after which the response is transport
    recycle, not provider conviction. Gating happens BEFORE `addError` —
    `endErr` is first-write-wins, there is no un-conviction.
A4. **Per-exit transport-liveness cross-check** (S): a channel whose own
    carrier has no registered transport (`RouteManager.HasActiveTransport()`)
    holds its verdicts and rebases clocks on re-registration. Catches the
    solo websocket death (~4/20min on 5G) the tunnel-wide gate ignores.
A5. **Demote-don't-execute** (M): `no-receive-ack` and stats-window
    "unhealthy" set a quarantine flag (own flag ORed into `isWarning()` —
    the resize healthy path calls setWarning(false) and would clobber a
    shared bool) instead of `addError`. Execute only when flowCount==0, or
    the state has persisted past the 60s sustained bound with zero receive
    progress. `no-receive-syn` gains a flowCount==0 gate. `no-send-ack`,
    `sendStalled`, transport-dead keep immediate execution — unambiguous.
A6. **Storm breaker** (S): after 2 verdict-removals in 30s, further
    verdict-driven removals defer (demote instead) until the window reopens.
    Exempt: DropExit, context-done, lifetime drains, capacity collapse.
    Cause-agnostic backstop for failure modes not yet discovered.

Expected field outcome for Phase A alone: the migration-storm reproduction
(toggle Wi-Fi mid-5G session) goes from 7 convictions to ~0; removals/min
drops well below 0.4 baseline; RecoveryMissed must stay flat (the guard
metric against "improved averages by abandoning flows").

## Phase B — rotation and capacity never touch live flows (pure connect)

B1. **flowCount callback into the window** (S): parent reads
    `len(clientUpdates[client])` under its own lock; wired like
    reliabilitySettingsFunc. Consumers: `collapseLowestWeighted` (only
    flowless drained exits die; hard deadline ~2x lifetime so one immortal
    flow can't pin an exit), expand same-clientId replacement (decline while
    the old channel carries flows).
B2. **Lifetime jitter** (S): effective lifetime = MaxClientLifetime x
    uniform(0.75, 1.0) per channel, computed once at construction — drains
    spread over ~15min instead of one synchronized hourly event. Fix the
    speed-window warn quirk in the same pass (drain must warn regardless of
    remove-rank).
B3. **Standing reserve** (S/M): size expand to target+1 and keep
    pre-validated client args warm (periodic generator tick) so failover
    replacement never pays the measured ~45s enumeration+build+ping serial
    path.

Acceptance: an idle SSH session survives 20+ minutes across a rotation
boundary; teardown-with-flows log lines only ever carry hard-evidence
reasons.

## Phase C — failover in milliseconds, not seconds

C1. **Proactive QUIC rebind on exit death** (M): in `removeClient`,
    established UDP/443 flows get immediately re-pinned to a live,
    cap-checked replacement (candidates gathered before the parent-locked
    section; assignment via the inherit idiom inside it; skip the
    RFC-9000-inert ICMP for these). Server sees the same connection ID from
    a new IP and path-validates. Bundled metric: recovery events split by
    same-source-port (migration accepted) vs new-port (app re-dialed) — the
    field answer to how well servers accept path changes.
C2. **Affinity-group failover** (M, rides C1): group a dead exit's flows by
    affinity key, pick ONE replacement per group — a site sees one
    coordinated egress-IP change, not a scatter.
C3. **Hard verdicts leave the resize cadence** (S/M): sendStalled and cping
    failures cancel the client directly instead of waiting for a sweep
    (3-4s consistent instead of 3-18s); cping timeout gains an addError
    reason so its removals stop logging as bare "Done."
C4. **Reconnect fast path** (S): scoped bypass of the global dial serializer
    during recovery (concurrency capped ~4) + `tls.NewLRUClientSessionCache`
    in the shared dialer configs (today every re-dial pays a full handshake).
C5. **Serve-stale DNS** (S): DohCache retains expired entries (~5-10min) and
    serves stale on resolver failure (RFC 8767) instead of SERVFAIL — the
    one dependency every new connection shares during a failover.

## Phase D — prefer the healthy (the tier ask)

D1. **Effective tier** (M): `effectiveTier() = server tier + demerits`
    (dialStarved +2, recent stall/unhealthy +1, survived-quarantine +2),
    consumed only by `minTierClients`. Demote within ~1s of evidence;
    promote only after N clean minutes AND a connect success. Quantized
    demerits (0/+1/+2) bound flapping; a phone-side stall demerits everyone
    equally, which min-based selection is invariant to. Surface as
    `EffectiveTier` in ExitInfo + dev menu.
D2. **Per-destination evidence** (M): no-receive-ack conviction additionally
    requires >=2 distinct send destinations in the window (count already
    derivable from ip4/ip6DestinationSourceCount); dial starvation requires
    strikes spanning >=2 distinct destinations. One dead website can no
    longer demote or convict an exit.
D3. **Bound the race field** (S): MultiRaceClientCount 0 -> 2. Most flows
    never race (affinity); this caps duplicate origin dials per cold start at
    2 instead of window-size, halving dial-strike noise. Add races-run/mean
    field size to metrics.

## Phase E — platform signals, server integration, long-term

E1. **Android migration epoch** (M, 3-repo dance): ConnectivityManager
    callbacks -> `DeviceLocal.NotifyNetworkChange()` -> preemptive verdict
    freeze + proactive transport recycle + reconnect fast path. Debounced,
    time-bounded, additive to (never replacing) the reactive A3/A4 gates.
E2. **Graded observations to the server** (M): report labeled observations,
    never bare verdicts — reason string, uplink-freshness age, distinct
    destinations, within-client cluster size (clustering LOWERS confidence:
    it's the local-storm signature), rotation flagged administrative.
    Under-report by design; the punishment spec's 3-network corroboration
    covers the blind spots. Do this before the punishment endpoint goes live.
E3. **Monotonic verdict clocks** (M): move verdict ages off the coalescing
    stats buckets (~31s silent ceiling; quarantine timelines sit one bucket
    from it) onto per-channel monotonic timestamps; clamp-and-log oversized
    settings meanwhile. Run old and new side by side one release.
E4. **Long-term, infrastructure-gated**: h3/QUIC platform transport with
    connection migration (code exists, disabled pending LB PROXY-protocol
    support — the only path where the platform link doesn't die at all on
    address change); opt-in dual-network warm standby (battery cost is real);
    MASQUE-style relay for TCP continuity (protocol + provider cooperation —
    upstream conversation, not a fork patch).

## Tensions adjudicated

- **Canary probing** (health lens pro, verdict lens anti): deferred. The
  anti-bot objection is decisive for third-party SYN canaries. The
  promotion-deadlock concern it addressed is mitigated by
  quarantine-clears-on-any-receive + strike-window decay. Fallback if field
  data shows warned-forever exits: a DoH query to our own resolver routed
  through the spare (no third party, real dial).
- **Sticky primary vs race**: keep the race, bound the field (D3). The
  484-flow concentration data disproves single-exit; WireGuard's lesson here
  is stable MEMBERSHIP, not one peer.
- **BlackholeReceiveTimeout tuning**: after A5 the knob only starts
  quarantine, not execution — the owed 20s-vs-5s A/B becomes low-stakes and
  should be re-run then (pre-removal PTO accumulation, Phase 10 theory).

## Measurement protocol (every stage)

Runtime toggle in ReliabilitySettings (gomobile: bool/int64-ms only, lists
not maps); a named counter or default-level log line proving the path ran on
device (glog pinned v=0 — nothing behind V(1)); A/B = 20-min arms of the
same workload with the metrics panel (blast radius, worst single failure,
recovery avg, RecoveryMissed) plus the reproducible drills: Wi-Fi toggle
mid-5G (storm), DropExit mid-HTTP/3-download (failover), StallExit (stall
rescue), SSH-across-rotation (capacity). RecoveryMissed is the universal
guard metric: any stage that improves averages while it rises gets backed
out. Five prior fixes were correct in isolation and unreached in place;
proof-of-life is part of each mechanism, not an afterthought.

## Sequencing rationale

A2 before C3 (the stall verdict must be honest before it gains authority).
A1/A3 before A5 (demotion decisions need the uplink gate or migrations fill
quarantine with everyone at once). B1 is the shared callback A5/B and C1
consume. D land after A+B so tier demerits are fed by de-noised evidence.
E2 lands before the server punishment endpoint activates.
