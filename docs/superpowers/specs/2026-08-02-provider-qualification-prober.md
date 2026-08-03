# Phase F — client-side provider qualification (aggressive pooling + probing)

Owner direction (2026-08-02): "connect to a large amount of them and do a
client probe to our sites list. Startup test all providers on first connect,
and probe new providers who join our provider pool. Being aggressive for
mainnet at the moment and finding good providers ourselves on the client."

## Why this is compatible with the no-probing adjudication

The earlier review rejected probing as NEGATIVE evidence: anti-bot
infrastructure drops datacenter-IP probes, so a failed probe must never
convict, strike, or demote. This design uses probes exclusively as POSITIVE
qualification: a passed probe proves the provider dials real destinations;
a failed probe leaves the provider exactly where it was (unqualified, never
punished). False negatives are affordable when the pool is oversized --
which is the point of aggressive pooling.

## Design

1. **Probe mechanism — SYN canary through the channel.** Split-TCP means the
   provider only synthesizes/forwards a SynAck after its own upstream dial
   succeeds, so one crafted TCP SYN through `client.Send` to a real site is
   a truthful end-to-end dial probe. Flow lifecycle: register a probe-marked
   update in the path maps so the SynAck routes back cleanly; on SynAck ->
   record success + send RST upstream (courtesy close); on dial-failure ICMP
   or ~4s timeout -> record failure. DNS-class probes: one crafted UDP/53
   A-query through the channel; any answer = success. Probe flows are
   excluded from: reliability metrics, flow counts (the cap), affinity maps,
   window weights, and drain/idle detection -- a probe must never make an
   exit look busy, healthy, or stalled to the machinery that judges real
   traffic. Probe successes DO feed addConnectSuccess (proven dial); probe
   failures feed NOTHING (the asymmetry above).

2. **Probe targets — from probe-list-v3.csv, embedded as a Go table.**
   Health-class entries only, reduced to hostnames dialed at :443, plus the
   dns-class resolver IPs at :53. Reputation-class entries (Akamai, Reddit,
   Epic, etc.) are excluded from automated probing entirely. Per probe pass:
   a rotating sample of ~4 hosts + 1 resolver per provider (deterministic
   rotation seeded per provider, so repeated passes cover the list), pass =
   >= 60% answered. Hostname resolution for probe targets uses the tunnel's
   DoH cache (already present) resolved OUTSIDE the probed channel, so a
   provider's DNS quirks cannot fail a TCP probe.

3. **Qualification state.** Per-provider (keyed by destination MultiHopId,
   surviving channel incarnations via the parent): unproven -> qualified
   (passed a probe pass; timestamped) -> stale (qualification older than
   ~30min, re-probe opportunistically). effectiveTier consumes it as a
   POSITIVE signal only: unproven/stale +1 (behind proven peers, never
   convicted). Surface per-exit in ExitInfo/sdk/dev menu: "proven" chip or
   probe age.

4. **Startup sweep.** On window init (first connect), probe every candidate
   channel as it passes its IpPing evaluation, concurrency-bounded (~8
   in-flight), before real traffic concentrates. Budget: ~5 probes x ~40
   providers x a few hundred bytes = well under 100KB total.

5. **Joiner probes.** Hook the expand add path: a new channel is probed
   immediately after entering the window. Standing-reserve spares get
   re-probed when their qualification goes stale (idle spares only; loaded
   exits are proven by their own traffic -- real receive progress refreshes
   qualification for free).

6. **Aggressive pooling.** New settings: EvaluationPoolMultiple (dial
   N x target candidates during expand, keep the best probers, default 2 for
   mainnet-aggressive; 1 = today), WindowSizeMax raised for the beta build.
   The window keeps the qualified best; unqualified surplus channels are
   closed politely (flowless by construction) after the sweep. Provider-side
   cost bounded by the multiple and by probing only at evaluation.

7. **A/B + proof-of-life.** Settings: ProviderProbe bool (default true on
   this fork), per the house rules everything behind ReliabilitySettings
   with sdk mirrors. Metrics: probesSent, probesPassed, providersQualified,
   plus per-exit probe age in the dev menu. The acceptance drill: fresh
   connect on mainnet -> exits list shows proven chips within ~20s, and the
   Shorts workload rides only proven providers.

## Constraints carried forward

Lock hierarchy (probe bookkeeping parent-side under the parent lock; packet
crafting channel-side, never parent under leaf); gomobile mirrors both
directions; glog v=0 (probe pass results at default level, per-probe silent);
nil-safe everywhere; the cping lesson pinned in tests: probe machinery must
never convict -- assert no addError/strike path is reachable from a probe
failure. e2e battery is the gate (probe flows must not disturb the fixtures:
default probe targets empty in bare tests).
