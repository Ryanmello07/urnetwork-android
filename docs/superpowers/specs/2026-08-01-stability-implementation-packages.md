# Stability redesign — implementation packages (Phases A+B)

Work packages for the 2026-08-01 connect stability redesign plan
(../plans/2026-08-01-connect-stability-redesign.md). Four packages, run
SEQUENTIALLY against connect @ beta/custom-server — they touch overlapping
regions of ip_remote_multi_client.go and each builds on the last. Every
package: match the file's comment style (long rationale comments), respect
the lock hierarchy (parent RemoteUserNatMultiClient.stateLock -> per-flow
leaf update.stateLock, never parent under leaf; multiClientChannel.stateLock
independent), gomobile constraints (no uint64/time.Duration/maps exported),
glog pinned v=0 (no V(1) for field-relevant events), nil-receiver-safe
metrics, and new behavior behind ReliabilitySettings toggles where specified.
The three pre-existing failures TestCombineTrim/TestPump/TestPumpTrim are
known — ignore them.

## Package 1 — stall-detector honesty + verdict-hold telemetry (A1+A2)

1. In multiClientChannel.SendDetailedWithAck: both transport-failure returns
   (err != nil, and success == false from backpressure) currently leave
   addSend's accounting armed — sendNack counts stay up and pendingSendTime
   never clears, so an innocent idle exit can be convicted by sendStalled
   after a backpressure burst. Add symmetric undo (decrement the nack
   count/bytes recorded by that send; when outstanding count reaches zero,
   clear pendingSendTime). No double-undo: the ack callback is only
   reachable when the send was accepted. Verify against the real accounting
   in addSend/ackCallback before writing.
2. reliability_metrics.go: add nil-safe atomic counters verdictsHeldUplinkStale,
   verdictsHeldTransportDown, removalsDeferred (int64), exposed via the
   existing snapshot path. Packages 2-3 increment them.
3. Tests: a stalled-accounting test proving a failed/backpressured send does
   not arm sendStalled (extend the existing TestStalledChannelSwallows...
   fixture style); counter nil-safety tests per the existing metrics tests.

## Package 2 — uplink-freshness gate + transport-liveness cross-check (A3+A4)

1. RemoteUserNatMultiClient gains atomic lastIngressNano stamped in
   clientReceivePacket (coarsen: skip store when <100ms newer) and in
   clientDialFailure (an intercepted ICMP proves the uplink works).
2. blackholeReasonFromStats stays PURE: it gains explicit time parameters
   (uplink last-receive, uplink fresh-since) and applies, to the RECEIVE
   branches only (noReceiveAck, noReceiveSyn — never noSendAck):
   - hold: uplink stale past UplinkStalenessGate (~5s) => blackholeNone;
   - rebase: ages count from max(firstSend*Time, uplinkFreshSince) so held
     verdicts do not all fire at once when receiving resumes.
   detectBlackhole computes the inputs. Guards: gate bypassed when fewer
   than 2 window channels have send activity (parent helper taking the
   parent lock briefly, called at the existing 1.25s cadence — the
   single-exit degenerate case keeps today's behavior); hard cap ~60s of
   continuous hold after which the gate stops applying. Gating happens
   BEFORE addError — endErr is first-write-wins, no un-conviction exists.
3. Transport cross-check: RouteManager gains HasActiveTransport() (transfer
   route manager maintains the set); detectBlackhole and the sendStalled
   consumer hold verdicts for a channel whose own client has no registered
   transport, rebasing clocks on re-registration.
4. Counters from Package 1 increment on every held verdict; one default-level
   Infof per gate begin/end transition (not per held evaluation).
5. Settings: UplinkStalenessGateMillis (0 = off) in ReliabilitySettings +
   sdk mirror. Tests: pure-function tests for hold/rebase branches; source
   anchors for both stamp sites; a test that noSendAck is never gated.

## Package 3 — flowCount callback + demote-don't-execute + storm breaker (B1+A5+A6)

1. B1 callback: multiClientWindow gains flowCount func(*multiClientChannel)
   int wired at construction (parent reads len(clientUpdates[client]) under
   the parent lock; call sites in resize hold no window lock — the
   reliabilitySettingsFunc pattern). Nil-safe for bare test windows.
2. A5 demote: quarantine is its OWN flag + start time on the channel (own
   lock), ORed into isWarning() — the resize healthy path calls
   setWarning(false) and would clobber a shared bool. detectBlackhole on
   noReceiveAck: do NOT addError; set quarantine, notify resize (replacement
   expands now). Execute (addError as today) only when the reason persists
   with zero receive progress past the existing 60s sustained bound
   (StatsWindowKeepUnhealthyDuration), or flowCount==0. Any receiveAck
   clears quarantine. noReceiveSyn keeps executing but gains flowCount==0
   gate. noSendAck/sendStalled/transport-dead untouched. The resize
   stats-"unhealthy" removal branch: flowCount>0 => warn, not remove; the
   60s hatch stays the escape.
3. A6 breaker: window-level ring of verdict-removal timestamps; after 2 in
   30s, further verdict-driven removals defer (warn instead) until the
   window reopens. Exempt: DropExit, ctx-done/transport-dead cleanup,
   lifetime drains, capacity collapse. removalsDeferred counter + one Infof
   per deferral naming the client.
4. Settings: SoftVerdictDemote bool, RemovalBudgetCount/WindowMillis.
   Tests: quarantine set/clear/execute state transitions (pure or bare-
   channel); clobber test (resize healthy pass must not clear quarantine);
   breaker exemption tests; source anchors for the resize call sites.

## Package 4 — capacity and rotation never touch live flows (B2+B3 + gates)

1. collapseLowestWeighted: a warned/draining client is only removable when
   flowCount==0 (Package 3 callback) or past a hard deadline
   (~2x MaxClientLifetime since first event). expand's same-clientId
   replacement: decline the Cancel when the old channel carries flows and
   is not done (drop the new args instead).
2. Lifetime jitter: effective lifetime = MaxClientLifetime x uniform(0.75,
   1.0), computed once per channel at construction, used where removeTime
   derives from MaxClientLifetime.
3. Speed-window quirk fix: the resize drain branch must setWarning(true)
   for every past-lifetime client regardless of remove-rank (today
   FixedWindowSize=1 windows compute remove=false for rank 0 and the best
   speed exit never rotates).
4. Standing reserve: size expand targets to +1 spare beyond target window
   size (bounded by WindowSizeHardMax). Skip the warm-args prefetch if it
   does not fall out naturally — target+1 alone closes most of the
   measured ~45s backfill hole.
5. Tests: collapse-gate and replacement-decline tests with the flowCount
   callback; jitter bounds test; a source anchor pinning the drain-branch
   warn fix.

## Acceptance for the whole build

Full suite green (minus the three known failures); the Wi-Fi-toggle drill
expectation: verdictsHeldUplinkStale > 0 and zero convictions during a
migration; teardown-with-flows log lines only ever carry hard-evidence
reasons; RecoveryMissed flat across A/B arms.
