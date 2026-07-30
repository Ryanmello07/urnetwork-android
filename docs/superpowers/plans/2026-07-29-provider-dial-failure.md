# Provider dial-failure signalling — plan

Spec: `docs/superpowers/specs/2026-07-29-provider-dial-failure-signalling.md`

## Phase 0 — shared packet layer *(done, by hand, before dispatch)*

- [x] `ipOosUnreachable` extended to TCP (host-unreachable / no-route),
  distinct from teardown's port-unreachable
- [x] `ipParseIcmpUnreachable` — recovers the egress fl