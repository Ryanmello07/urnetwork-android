# Upstream port + full dev-menu exposure + scan-friendly logging

2026-08-02. Owner direction: port upstream's useful changes into the beta
line, ship one optimized APK, expose as much as possible in developer
settings, and improve the logs for post-hoc logcat scanning. Source
analysis: ../reports/2026-08-02-upstream-reliability-comparison.md.

## The big call: port direction

The report's long-term strategy (their multi-client skeleton, our invariant
layer ported onto it) is a multi-day structural port. This cycle takes the
REVERSE direction deliberately: our beta stays the base — it carries the
field-validated invariant layer (quarantine, gates, breaker, rebind, caps,
prober) — and upstream's useful pieces come to us, as concepts where their
code entangles with their restructure, wholesale where files are orthogonal.
The structural port remains the eventual merge plan; nothing this cycle
forecloses it.

## Brainstorm: what upstream has, triaged

TAKE WHOLESALE (files our fork never touched; verify dependency edges):
- tun.go gVisor fixes: PacketBuffer leak (every inbound packet stayed live
  for process lifetime), lossless link endpoint (silent queue drop ->
  bounded backpressure; the user-NAT TCP bridge never retransmits its
  return path), TCP inbound shards, raceTunDialContext absolute deadline.
  The single largest perf/memory win available. Verify: does their tun.go
  reference their changed ip.go/transfer.go APIs? If yes, trim to the
  separable fixes.
- wakeup_schedule.go delta (small), udp_receive_dispatch_test.go.

PORT AS CONCEPTS into our machinery (their code owns different structure):
- Busy-flow liveness probe: the headline. In OUR terms: when sendStalled's
  3s bar trips, instead of convicting immediately, fire one control ping
  through the exit with a snappy budget (max(1s, bar/2)); ack acquits
  (clears the stall clock, records liveness), timeout or unsendable-x2
  convicts with the existing "send stalled" reason. Gets our 3-4s latency
  with their false-positive safety (a congested-but-alive exit answers the
  probe). Behind BusyProbe bool default true; 0-cost when off (today's
  behavior). Their transport-level ping machinery (SendDetailedMessage +
  IpPing proto) already exists in our tree — reuse the idle-ping plumbing.
- Scheduler-pause exculpation: a timer firing far later than armed = host
  suspension. Parent-level detector (armed-vs-fired delta > 2s) feeding the
  SAME hold path as the uplink gate: verdicts held + clocks rebased for a
  5s recovery window after resume. Complements the uplink gate (suspend vs
  silent-network are different cases; we cover migration, they cover doze).
- Comparative connect blackhole: the no-receive-syn branch drops its bar
  from 30s to 10s when >=2 sibling exits have recent receive progress
  (window-health signal the uplink gate's sendingChannelCount machinery can
  already approximate). One dead-connecting exit cut early while the pool
  is demonstrably fine.
- Formation fast-poll: when the window has zero clients, the send retry
  loop polls at 200ms instead of SendRetryTimeout — first DNS+SYN leave
  moments after the first client lands. Small settings change in sendPacket
  retry.
- Suspect-as-demerit: we already have richer effectiveTier; add +1 while a
  busy-probe is outstanding-unanswered on the exit (their suspect bit,
  absorbed our way). Clears on probe ack.
- Generator deadlines: wrap NextDestinations/NewClientArgs calls in resize
  /expand with a deadline context (20s) so a hung platform API can never
  wedge maintenance. Ours currently trusts the API.
- NetworkChanged kick: process-level broadcast -> platform transports drop
  their connection + reconnect backoff immediately (event-driven reconnect
  instead of waiting out ping timeouts) + uplink-gate epoch reset. Wire:
  connect util broadcast (port their util.go registry), transport.go Kick
  into our runH1/runH3 (merge with our reconnect fast path — Kick triggers,
  our parallel-jitter path executes), sdk DeviceLocal.NotifyNetworkChange,
  android ConnectivityManager callbacks (already registered for
  providePaused — add the device call). This closes Phase E item E1 with
  upstream's own design.
- DoH: take their hedge-on-quiet + Warm() concepts ONLY if separable
  cheaply; otherwise defer to the structural port and keep our serve-stale.
  (Their DoH rewrite is 870 lines entangled with mux wiring; serve-stale is
  field-proven here. Decision left to the implementing agent with a
  verification step; default = defer.)

DEFER (structural port territory; do not touch this cycle):
- Their transfer.go raw-frame V2 send path, memory budget, stream manager,
  encryption changes, frame_protobuf.
- p2p/webrtc overhaul (huge, orthogonal, own risk profile).
- ICMP echo path (default-off upstream, needs provider rollout; textual
  conflict with our ip.go dial-failure work; take in the structural port).
- Their multi-client generation guards/observer workers (their skeleton's
  virtue — comes with the skeleton later).
- Their stall test suite (pins their semantics; adopt with the port).
- ADOPT NOW though: the recovery measurement kernel
  (multi_client_recovery_kernel_test.go, URNET_RECOVERY=1 env-gated) —
  adapted to our APIs. It is the neutral bake-off instrument.

## Developer settings: expose everything

New controls (all runtime, ReliabilitySettings + sdk mirror + UI):
- Busy probe: toggle + stale-bar presets (0/2s/3s/5s).
- Scheduler-pause hold: toggle.
- Comparative connect cut: toggle (+ implicit 10s).
- Formation fast-poll: toggle.
- Network-change kick: "Simulate network change" ACTION button (fires the
  broadcast — turns the storm drill into a one-tap reproduction).
Existing settings shipped without UI, now surfaced:
- Removal budget count/window presets; StandingReserve toggle;
  EvaluationPoolMultiple (1/2/3); ProbeTimeout presets;
  MinBlackholeDestinations (0/1/2/3); QUIC rebind (already);
  UplinkStalenessGate (already); plus a "Probe all exits now" action.
Layout: group the behaviour section into Detection / Placement / Recovery /
Probing subheads so the growing list stays scannable.

## Scan-friendly logging (for logcat forensics)

Today's pain: reconstruction needs cross-referencing scattered formats, and
state (exits/flows/qualification) is only in the UI. Add, all default-level:
1. Session banner at multi-client construction: one line, version + the
   effective ReliabilitySettings snapshot (key=value), so every capture is
   self-describing.
2. Structured event grammar for NEW lines going forward: prefix "[rel]"
   with key=value pairs (event=quarantine exit=<id8> flows=16 reason=...).
   EXISTING verdict/teardown lines keep their exact format — external
   tooling and this session's greps depend on them — but gain a trailing
   " | [rel] event=..." twin only where cheap.
3. Heartbeat: one line per 60s summarizing live state — exits=N proven=N
   quarantined=N warned=N flows=N held=U/T deferred=N rebinds=A/R
   probes=S/A removals=N — so any buffer window reconstructs the session
   shape without the dev screen. Skipped when nothing changed since the
   last beat (idle sessions stay quiet).
4. Settings-change lines: every SetReliabilitySettings diff logged as
   [rel] event=settings changed=<field>:<old>-><new> — A/B arms become
   self-documenting in captures.
5. Dev-menu actions logged (DropExit/StallExit/Shuffle/probe-now).

## Implementation packages (sequential, same pipeline)

- P1 (wholesale + transport): tun.go take + verification; util.go
  NetworkChanged registry; transport Kick merged with our reconnect path;
  formation fast-poll; generator deadlines. Gate: e2e battery.
- P2 (verdict-layer ports): busy-probe into sendStalled path;
  scheduler-pause hold; comparative connect cut; suspect demerit. All
  behind toggles, all with the no-false-conviction pins extended. Gate:
  e2e + full quarantine/blackhole suites.
- P3 (observability): banner, [rel] grammar, heartbeat, settings-change
  logs; recovery kernel adapted; "probe all" + "simulate network change"
  actions in connect/sdk.
- P4 (surface): sdk mirrors for every new knob; the reorganized dev menu;
  strings. Kotlin uncompiled locally — minimal, pattern-matching edits.
- My review between packages; full split suite before commit; one connect
  commit per package landing zone if separable, else one; sdk + android
  commits; CI; universal APK.

## Risks and guards

- tun.go take may drag hidden APIs — P1 verifies compile + e2e before
  anything else lands on top; if entangled, trim to the leak fix + link
  endpoint which the report identifies as self-contained.
- The busy probe must never DELAY the hard paths: transport-dead and
  no-send-ack keep immediate execution; the probe only interposes on the
  sendStalled bar. Pinned by test.
- Heartbeat cost: one formatted line per minute, assembled from atomics —
  no lock sweeps on the packet path; the exits walk reuses Exits().
- Every port keeps our invariant: nothing new may convict without passing
  the existing gates (uplink, transport, breaker, quarantine).
