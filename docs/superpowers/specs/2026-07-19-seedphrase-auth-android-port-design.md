# Seedphrase-auth Android port — design

Date: 2026-07-19
Status: Approved (design phase) — see implementation plan for execution.

## Context

The seedphrase-auth feature (seedphrase login, instant no-verification account
creation, multi-method Settings management, live-`auth_types`-based
name-claim logic) shipped on iOS this session:
- Beta fork PR: `Ryanmello07/urnetwork-ios#4` (`feat/seedphrase-auth-ios` →
  `beta/custom-server`), merged, CI-green, live-verified against the beta
  server.
- Upstream draft PR: `urnetwork/apple#331` (`feat/seedphrase-auth-upstream` →
  `main`), a single squashed commit of the net feature diff, opened as draft
  because it depends on `urnetwork/sdk#132` (still open) for the
  `AuthTypes`/`WalletAddress`/seedphrase SDK symbols it calls.

An existing attempt at porting this to Android exists at
`Ryanmello07/urnetwork-android#2` (`feat/seedphrase-auth` →
`beta/custom-server`). It covers guest-mode removal and seedphrase
login/instant-account-creation only — it's missing the Settings/AddAuthSheet
rebuild and the Profile name-claim fix, which together are over half of the
iOS feature by diff size. Per explicit direction, this PR is being replaced
outright rather than extended.

## Goal

Port the complete iOS seedphrase-auth feature set to Android, across all 4
login-capable build flavors (`google`, `ungoogle`, `ethos_dapp`,
`solana_dapp`), replacing PR #2 entirely, and ultimately land it upstream at
`urnetwork/android` the same way the iOS work landed at `urnetwork/apple`.

## Key architectural finding

Android's Settings/Profile/Account screens (`SettingsScreen.kt`,
`ProfileScreen.kt`, `AccountScreen.kt` and their ViewModels) live in the
shared `main` source set. Only the pre-login screens (`LoginInitial.kt`,
`LoginNavHost.kt`, `LoginViewModel.kt`, `LoginActivity.kt`) are duplicated
per flavor — confirmed via diff: `google` vs `solana_dapp` LoginInitial.kt
differ by 45 lines, `google` vs `ungoogle` by 222, `google` vs `ethos_dapp`
by 293, out of ~800-1000 lines each.

This means the "4 flavors" multiplier only applies to one of the three
feature areas below, not all of them. New shared-source-set code is written
once and every flavor gets it automatically.

## Scope decisions (from brainstorming)

- **Replace PR #2 entirely** — new branch cut from `beta/custom-server`, PR
  #2 closed. Not extending its branch.
- **PR1 → PR2 → PR3 are sequential, not parallel**: each branches from
  `beta/custom-server` only after the previous one has merged into it. PR2
  and PR3 don't depend on PR1's code, but doing them in this order keeps
  `beta/custom-server` as the single source of truth each new branch forks
  from, avoiding merge-order surprises.
- **All 4 flavors** in scope (`google`, `ungoogle`, `ethos_dapp`,
  `solana_dapp`), not just `google`.
- **New per-flavor UI code is copy-pasted per flavor**, matching the
  existing codebase convention (each flavor's `LoginInitial.kt` is already a
  full standalone copy) — no new shared-composable abstraction introduced
  for this feature, to avoid an unrelated architecture change/risk on top of
  the port itself.
- **AddAuthSheet UI is a direct structural port from iOS** (single sheet,
  method picker), not redesigned into a more Android-native
  multi-destination flow — explicit preference: "apps generally look the
  same between the two anyway."
- **Staged as one PR per feature area**, `google` flavor built and
  CI-verified first within each PR, then fanned out to the other 3 flavors.
- **Final upstream contribution is a single squashed PR**, following the
  exact process used for iOS: branch from `urnetwork/android:main` (not from
  `beta/custom-server`), apply/cherry-pick the net feature diff, squash to
  one clean commit, open as **draft** (blocked on `urnetwork/sdk#132` same
  as the iOS upstream PR — this SDK dependency gates both platforms
  identically).

## Phases

### Phase 1 — PR1: Guest-mode removal + seedphrase login/signup (4 flavors)

New per-flavor files (×4): `LoginSeedphrase.kt`, `CreateNetworkInstant.kt`,
`SeedphraseDisplayScreen.kt`.

Modified per-flavor: `LoginInitial.kt`, `LoginViewModel.kt`,
`LoginNavHost.kt`, `LoginActivity.kt`.

Modified in `main`: `LoginCreateNetwork.kt`/ViewModel (instant-account tab),
`AccountScreen.kt`/`AccountViewModel.kt`/`FullScreenOverlay.kt`/
`SwitchAccountScreen.kt` (drop guest-mode branches).

Deleted: `OnboardingGuestModeSheet.kt`, `GuestModeOverlay.kt`, guest-mode
drawables.

Known bug to avoid re-introducing: iOS's `isSeedphraseValid` originally only
checked non-empty, not actual word count (12/24) — gate correctly from the
start on Android.

Build/verify `google` first, confirm CI-green, then port the same shape to
`ungoogle`, `ethos_dapp`, `solana_dapp`.

### Phase 2 — PR2: Settings "Sign-In Methods" + AddAuthSheet (shared `main`)

New: `AddAuthMethodSheet.kt` (direct structural port of iOS's
`AddAuthSheet.swift` — email/password, Apple/Google SSO, Solana wallet
connect+sign, all in one sheet with a method picker). `AuthMethods.kt` —
shared helpers (`authTypesContains`, `parseAuthMethods`, `methodDisplayName`)
matching iOS's `AuthMethods.swift`, including the exact `"solana"` (not
`"wallet"`) string the server uses in `auth_types`.

Modified: `SettingsScreen.kt` gains a Sign-In Methods list section with
remove buttons; `SettingsViewModel.kt` gains `refreshNetworkUser()` calls
after every add/remove auth-method mutation, **on the success path only**.

Known iOS bugs/lessons to build in correctly from the start (not
rediscover):
- Refresh the methods list after every successful add/remove, not
  unconditionally after a caught failure (iOS bug #9).
- Wallet-connect flow needs a cancellation path tied to sheet dismissal.
  Flagging that iOS's own fix for this was **left incomplete** even in the
  final shipped version (`connectWallet()`'s pre-poll portion and the "Sign
  with Wallet" button's task remain uncancelled) — Android should either fix
  this properly or consciously accept the same documented gap, not silently
  inherit it.
- The wallet-duplicate-bind hang and stale-JWT-name-on-refresh bugs were
  **server-side only** (already fixed, live-verified, applies to any
  client) — no Android-specific workaround needed for either.

### Phase 3 — PR3: Profile name-claim logic (shared `main`)

Modified: `ProfileScreen.kt`/`ProfileViewModel.kt` gain a `needsNameClaim`
computation checking live `auth_types` for
`email`/`phone`/`google`/`apple`/`solana` — net-new (Android currently has
no name-claim-vs-change distinction at all).

Also porting directly (proven, server-verified behavior):
- Force a network-user/JWT refresh after a successful name change (Kotlin
  equivalent of iOS's `device.refreshToken(0)` — exact method name TBD
  during implementation, same underlying Go SDK).
- Refresh on app foreground too (Android equivalent of iOS's `scenePhase`
  hook — likely `Lifecycle.Event.ON_RESUME` observed via a
  `DisposableEffect`/`LifecycleEventObserver`, or an Activity-level
  `onResume()` override, whichever matches this codebase's existing
  lifecycle-observation convention).

### Phase 4 — Upstream squash PR

Once PR1, PR2, PR3 are merged into the Android fork's `beta/custom-server`:
1. Add `upstream` remote (`urnetwork/android`) if not already present, fetch
   `upstream/main`.
2. Diff `beta/custom-server` against `upstream/main` to confirm they're
   still close in content (expect only beta-only CI workflow + SDK sibling
   path differences, mirroring what was found on iOS: 3 files, ~156 lines).
3. Create a new branch from `upstream/main` (not from `beta/custom-server`).
4. Apply the net diff (`beta/custom-server...feat/seedphrase-auth-android`,
   or whatever the fork's cumulative branch ends up named) via `git apply
   --3way`, resolving any conflicts the same way the iOS port did (trivial
   whitespace-only conflicts expected, not real ones).
5. Squash into one clean, well-described commit.
6. Push to the fork, open as a **draft** PR against `urnetwork/android:main`,
   noting the `urnetwork/sdk#132` dependency in the PR body exactly as done
   for `urnetwork/apple#331`.

## Verification

No local Android toolchain available. Same pattern as iOS: push → `Beta
Build — Android` GitHub Actions workflow (confirmed existing and working) →
download unsigned APK artifact for manual testing against the beta server.
Expect multiple rounds of CI-driven build-error fixes before green, same as
iOS hit ~8 rounds of Swift-compiler-timeout/build-error commits.

## Out of scope

- Refactoring the existing per-flavor login-screen duplication into shared
  composables — explicitly declined; matches existing codebase convention.
- Redesigning AddAuthSheet into a more Android-native navigation pattern —
  explicitly declined; direct structural port preferred.
- Any SDK-side (Go) changes — the fork SDK already has the needed symbols
  for beta work; the upstream SDK PR (`urnetwork/sdk#132`) is a separate,
  already-in-flight piece of work not owned by this effort.
