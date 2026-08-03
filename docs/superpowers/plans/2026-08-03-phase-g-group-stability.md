# Phase G: group-granular stability (plan)

2026-08-03. Follows the A–F redesign and the 2026-08-02 IP-consistency work
(AffinityStickyPastCap, domainAffinityAliases, full-table probing).

## The problem, from field evidence

The 2026-08-02 build fixed two of the three ways a site's egress IP was being
split (the flow-cap veto on inheritance, and cross-domain CDN constellations).
The owner's same-night session (02:18–02:24, flows 176→276 over 12 exits)
shows the third split source is now the dominant cost, and quantifies it:

- **Five quarantine episodes in six minutes, every one acquitted** on receive
  progress within 10–90s. The benches were false alarms on loaded exits
  (22–24 flows each, receive-quiet ~10s under load, then fine).
- While benched, an exit is refused as an affinity donor (`isWarning` veto in
  `inheritAffinityClient4/6WithLock`), so **its sites' new flows scatter to
  other exits** — the exact egress-IP split sticky affinity exists to prevent,
  triggered by our own (wrong, as it turned out every time) suspicion.
- **Up to 3 of 12 exits benched at once**, shrinking the active pool by a
  quarter; combined with two `create client args expired` (platform slow to
  hand over candidates), new placements crowded the remaining nine. Felt as
  "slight slowness."

Sticky affinity raises the stakes: groups are bigger now, so a false bench
scatters more. The quarantine mechanism is right (zero teardowns, zero flows
lost); its *placement side effects* are the remaining problem.

Secondary gap: affinity grouping is domain-keyed (server name → eTLD+1 →
constellation alias). Traffic with no observed server name (raw-IP apps,
games, some QUIC) falls back to coarse destination/port groups, and nothing
groups by *application*. The owner's instinct — "assign a provider to an app"
— has no mechanism.

## Design pillars

### G-2 (first, prerequisite): split the warning flag

`multiClientChannel.warning` is one bool ORed from unrelated causes
(unhealthy stats, lifetime draining, dial starvation), plus the separate
`quarantined`. Inheritance and migration need to know WHY an exit is warned:

- `warnUnhealthy` — evidence against the exit. Never inherit.
- `warnStarved` — dials failing. Never inherit (a new flow IS a dial).
- `warnDraining` — healthy, retiring on lifetime. Inheritance policy owned by
  G-3 (coordinated migration); until G-3 lands, keep the current no-inherit.
- `quarantined` — suspicion, not conviction. Policy owned by G-1.

`isWarning()` remains the OR for selection/UI compatibility. Pure refactor +
tests; the resize healthy pass is the danger zone (it has clobbered the
shared bool before — every write site gets a flag-specific assertion test).

### G-1: benched exits keep their own sites (group-follow)

While an exit is quarantined, a new flow whose affinity group already lives
there CONTINUES to inherit it. New groups still avoid it; selection, racing,
and rebind still avoid it. Rationale: the group's established flows are
already on the suspect exit — placing the site's next flow elsewhere breaks
the site's IP binding *without* reducing the group's exposure. If the exit
is genuinely dead the whole group fails together, the evidence sustains, and
the existing escalation (drain-to-conviction, hard removal, rebind/re-race)
recovers everything at once.

Safety gate: follow only while the exit shows receive progress within
`GroupFollowReceiveFreshness` (default ~10s, the congested-not-dead
signature). A receive-silent benched exit stops receiving its groups' new
flows immediately — dead exits get no fresh hostages.

Setting: `QuarantineGroupFollow bool` (default true; false = today's
scatter, the A/B point). Observability: `[rel] event=group_follow` per
followed inheritance is too chatty — count it: heartbeat gains
`follow=<followed>/<scattered>`, and the quarantine line gains the count of
groups it holds.

Expected field result: quarantine_lift acquittals stop being preceded by
site-splitting; the bench-window slowness drops because followers don't
crowd the rest of the pool.

### G-5: bench backfill (pool compensation)

A quarantine shrinks the active pool but does not today trigger expansion —
the standing reserve absorbs one bench, not three. On quarantine entry,
request expansion immediately (one spare per concurrently-benched exit,
bounded by WindowSizeHardMax and the existing storm budget). On acquittal,
surplus drains via the normal lifetime machinery. Small change in the resize
path; the `create client args expired` slowness argues for *early* asks.

### G-6: loaded-exit verdict leniency

Tonight's benches: no-receive-syn/ack against exits carrying 22–24 flows,
all acquitted. A loaded exit has more in-flight questions and more ways to
look briefly silent. Scale the corroboration requirement with load:
`MinBlackholeDestinations` becomes `max(configured, flowCount/8)` for the
soft verdicts (hard evidence paths unchanged). One pure function + tests,
A/B via the existing setting (0 keeps today's flat behavior). This reduces
false benches at the source; G-1 reduces the cost of the ones that remain.

### G-3: coordinated group migration (the big one)

A deliberate mechanism to move an affinity group between exits as ONE event:

1. choose target (existing candidate order: proven, under-cap, best tier),
2. flip the group's donor so new flows land on the target,
3. QUIC-rebind the movable flows (existing rebind machinery, which already
   groups by affinity),
4. let TCP flows drain on the old exit (they cannot migrate — split-TCP),
5. release the old exit at group-idle or a hard deadline.

Consumers, in order of value:
- **Lifetime drain**: retirement stops being a scatter. The exit past its
  lifetime migrates groups one at a time, then closes. Bounds the
  sticky-affinity concern that a hot site keeps an exit alive forever: the
  drain deadline is absolute; the migration makes honoring it cheap.
- **Quarantine escalation**: a benched exit whose evidence sustains (the
  drain-to-conviction path) migrates groups BEFORE the removal, so the
  removal executes empty. Tonight's `13ce7edf`-style 9-minute drains become
  proactive moves.
- **Dev action**: "Migrate exit" button — the falsification instrument.

Touches removeClient/rebind internals; reuses `rebindFlowsWithLock`'s
group-cohesion and headroom logic. The lock discipline is the risk (parent
stateLock vs per-flow leaf locks — same rules as rebind). Ships behind
`GroupMigration bool`.

### G-4: per-app affinity (uid grouping)

Android can map a flow to its owning app: `ConnectivityManager.
getConnectionOwnerUid(protocol, local, remote)` (API 29+). Plumbing:

- sdk: `FlowOwnerLookup` interface (gomobile-safe: ints and strings),
  implemented in Kotlin against ConnectivityManager, registered on the
  device; connect receives it like `serverNameLookup`.
- connect: flow-open only (never the packet path), cached per
  (proto, sourcePort) with idle expiry. Failure/timeout → uid 0 → today's
  behavior. Budget: one binder call ~0.1–1ms per NEW flow.
- Grouping modes (`AppAffinityMode`): `off` (today), `fallback` (uid group
  used only where no server name is known — replaces the coarse port-wide
  fallback, covering raw-IP apps), `strict` (uid IS the primary key: one
  app, one exit, the owner's "per-app split tunnel"). Default `fallback`;
  `strict` as the dev-menu experiment — it concentrates a browser's whole
  world onto one exit, which is traditional-VPN behavior but makes that
  exit hot.

Independent of G-1/2/3/5/6; needs an sdk+android release cycle.

## Sequencing

1. **G-2** flag split (prerequisite, pure refactor)
2. **G-1** group-follow + **G-5** backfill + **G-6** leniency — one build,
   directly aimed at the observed bench-churn slowness
3. **G-3** migration (drain consumer first, escalation second)
4. **G-4** uid affinity (parallel track once 1–2 are stable)

Each package ships behind its own A/B toggle, dev-menu exposed, with
heartbeat/[rel] counters as proof-of-life (standing lesson: a mechanism
without a field-observable signal does not exist).

## Risks

- G-1 feeding a dead exit: bounded by the receive-freshness gate and the
  unchanged hard-conviction paths.
- G-3 lock discipline inside remove/rebind: reuse, don't reinvent; the
  cping and rebind regressions are the cautionary tales.
- G-4 binder latency: flow-open only, cached, fail-open.
- G-6 under-detection on huge exits: leniency scales corroboration, never
  disables it; hard evidence paths untouched.
- The flow-cap TOCTOU overshoot remains open and orthogonal.
