# Seedphrase-auth Android port — PR3 (Profile name-claim + rename migration) design

Date: 2026-07-20
Status: Approved (design phase) — see implementation plan for execution.

## Context

PR1 (`Ryanmello07/urnetwork-android#6`) and PR2 (`#7`) shipped seedphrase
login/instant-account-creation and the Settings Sign-In Methods list, both
merged into `beta/custom-server`. This PR (PR3) is the third and final
per-feature PR from `docs/superpowers/specs/2026-07-19-seedphrase-auth-android-port-design.md`'s
Phase 3: profile name-claim logic, a direct structural port of iOS's shipped
`ProfileView.swift`/`ProfileViewModel.swift`/`AccountNavStackView.swift`
name-claim handling.

**The original one-line Phase 3 spec undersold this PR's scope.** It read
"`ProfileScreen.kt`/`ProfileViewModel.kt` gain a `needsNameClaim`
computation" — implying Android's rename call was already correct and only
needed a UI flag. Research done at PR3 kickoff found that's wrong, and
found a second, independent problem in the same code:

1. **Android's rename call uses a different, legacy, unprotected server
   endpoint than iOS.** `ProfileViewModel.kt`'s `updateProfile()` calls
   `networkUserVc.updateNetworkUser(name)` →
   `NetworkUserViewController.UpdateNetworkUser` (Go SDK,
   `network_user_view_controller.go:245`) → `Api.NetworkUserUpdate` → `POST
   /network/user/update` → `controller/network_controller.go`'s
   `UpdateNetworkName` → `model.NetworkUpdate` directly, unconditionally,
   with no cooldown and no claim/change distinction.

   iOS instead calls `Api.changeNetworkName`/`Api.claimNetworkName`
   directly (`ProfileViewModel.swift:84-180`) → `POST
   /account/change-name` / `/account/claim-name` →
   `controller/account_controller.go`'s `ChangeNetworkName`/`ClaimNetworkName`
   — the actively-maintained path, which applies a 24h reclaim-cooldown on
   the old name after a change. Both server routes are live today (`grep`
   confirmed in `/tmp/sandbox/server/api/api.go:62,138-139`); Android has
   simply never been migrated onto the newer one. This predates the
   seedphrase-auth work entirely — a pre-existing product/security gap
   between platforms, not something this session's work introduced.

2. **The rename UI is currently dead code.** The edit-icon + Save/Cancel
   controls in `ProfileScreen.kt` are commented out (`// todo - temporarily
   remove edits until new API changes are made`), and even if uncommented,
   `updateProfile()`'s gate (`networkNameIsValid && usernameIsValid`) can
   never pass — `usernameIsValid` starts `false` and has no setter anywhere
   in the codebase. Renaming a network is effectively unreachable on
   Android today. The disabling comment's own stated condition — "until
   new API changes are made" — describes exactly what this PR delivers.

Decision (confirmed with the user): PR3 migrates Android fully onto
`changeNetworkName`/`claimNetworkName` (matching iOS, picking up cooldown
protection) rather than only bolting `needsNameClaim` onto the legacy call,
and re-enables the currently-dead edit UI rather than leaving it disabled.

## Verified SDK contract

Confirmed directly against `api.go` and `device.go`/`device_local.go`/`device_rpc.go`
in `github.com/Ryanmello07/urnetwork-sdk` (`beta/custom-server` branch):

```go
// api.go:2402
type ChangeNetworkNameArgs struct {
    NewName string // json: new_name
}
func (self *Api) ChangeNetworkName(args *ChangeNetworkNameArgs, callback ChangeNetworkNameCallback)
// ChangeNetworkNameResult { NetworkName string; Error *ChangeNetworkNameError }
// ChangeNetworkNameError { Message string }

// api.go:2433
type ClaimNetworkNameArgs struct {
    NewName string // json: new_name
}
func (self *Api) ClaimNetworkName(args *ClaimNetworkNameArgs, callback ClaimNetworkNameCallback)
// ClaimNetworkNameResult { NetworkName string; Error *ClaimNetworkNameError }
// ClaimNetworkNameError { Message string }

// device.go:634 (implemented by DeviceLocal and DeviceRemote — Android's real device type)
RefreshToken(attempt int) error
```

Gomobile's established Kotlin binding convention (already used throughout
this codebase, including PR2's `AddAuthArgs`/`RemoveAuthArgs`) exposes
these as `ChangeNetworkNameArgs`/`ClaimNetworkNameArgs` classes with
camelCase fields (`newName`) and callback-lambda methods
(`api.changeNetworkName(args) { result, err -> ... }`).
`device.refreshToken(0)` is not new plumbing — it already has a proven,
existing call site in this codebase at
`SubscriptionBalanceViewModel.kt:156`
(`deviceManager.device?.refreshToken(0)`, plain `Int` literal `0`, no
cast needed); PR3 reuses that exact call shape, matching iOS's
`deviceManager.device?.refreshToken(0)` 1:1.

`needsNameClaim` reuses PR2's existing `authTypesContains(authTypes:
StringList?, method: String): Boolean` from
`com.bringyour.network.ui.settings.AuthMethods.kt` — a cross-package
import (`ui.profile` → `ui.settings`), same pattern as any other shared
utility in this codebase.

## Goal

Re-enable Android's network-name editing on the correct, cooldown-protected
API path, matching iOS's claim-vs-change UX and JWT-refresh behavior.
Shared `main` only — `changeNetworkName`/`claimNetworkName`/`refreshToken`
are plain `Api`/`Device` methods, not flavor-gated, so no per-flavor split
is needed here (unlike PR2's Google Sign-In split).

## Components

### `ProfileViewModel.kt` (modified, `app/app/src/main/java/com/bringyour/network/ui/profile/`)

Delete the dead write-path machinery entirely:
- `networkUserVc` (`NetworkUserViewController`) and its `init`/`onCleared`
  lifecycle — its only two jobs (the write call, and observing update
  success/error/in-flight) are both being replaced. The *read* side
  (current `networkUser` for display) already comes from
  `AccountViewModel.networkUser`, pushed in externally via
  `ProfileScreen.kt`'s `LaunchedEffect(networkUser) {
  profileViewModel.setNetworkUser(networkUser) }` — `networkUserVc` was
  never the source of truth for reads, so removing it drops no read
  capability.
- `addIsUpdatingListener`, `addUpdateErrorListener`, `updateSuccessSub`/
  `updateSuccessListener`/`updateSuccessSubInstance` and their `Sub`
  bookkeeping.
- `usernameIsValid` and its unused setter-less state (dead: no setter
  exists anywhere in the codebase; permanently blocks `updateProfile()`
  even once the UI is re-enabled).
- `isUpdatingProfile`/`errorUpdatingProfile` and their setters (replaced
  by the new methods' own state, below).

Keep unchanged: `networkNameValidationVc` and `validateNetworkName` (live
name-availability check, unrelated to the actual save call).

Add, matching PR2's `SettingsViewModel.addAuth`/`.removeAuth` convention:

```kotlin
private val _isSavingNetworkName = MutableStateFlow(false)
val isSavingNetworkName: StateFlow<Boolean> = _isSavingNetworkName

private val _networkNameError = MutableStateFlow<String?>(null)
val networkNameError: StateFlow<String?> = _networkNameError

val needsNameClaim: StateFlow<Boolean> // derived from networkUser.authTypes via authTypesContains,
                                        // false if no email/phone/google/apple/solana bound

val saveNetworkName: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit
    // wraps Api.changeNetworkName(ChangeNetworkNameArgs(newName = ...), callback)

val claimNetworkName: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit
    // wraps Api.claimNetworkName(ClaimNetworkNameArgs(newName = ...), callback)
```

Both success paths: call `deviceManager.device?.refreshToken(0)` (JWT
refresh so the changed name propagates — the displayed name is cached-JWT-
derived, same rationale as iOS's `handleNetworkNameResult`'s comment),
then invoke the passed `onSuccess`. Error paths: set `_networkNameError`,
invoke `onError`. Refresh only on success, never unconditionally after a
catch — the existing project-wide Global Constraint from PR1/PR2, one iOS
already gets right here (`ProfileViewModel.swift`'s `saveNetworkName`/
`claimNetworkName` only touch `networkNameError` in the catch branch, no
refresh) but Android's own PR2 `SettingsViewModel` docs called out
explicitly, so it stays an explicit requirement for this task too.

### `ProfileScreen.kt` (modified, same directory)

Re-enable the currently-commented-out edit-icon + Save/Cancel controls
(the block starting `// todo - temporarily remove edits until new API
changes are made`). Save routes to `profileViewModel.claimNetworkName` if
`needsNameClaim` else `profileViewModel.saveNetworkName`, matching iOS's
`ProfileView.swift:74-80` branch exactly. Remove the outer composable's
`DisposableEffect(Unit) { profileViewModel.updateSuccessSub { ... } }`
block (no longer needed — the new methods take direct `onSuccess`/
`onError` callbacks, same shape as PR2's `AddAuthMethodSheet` call sites).
On save success: `accountViewModel.refreshNetworkUser()` +
`profileViewModel.setIsEditingProfile(false)` (moved from the old
`updateSuccessSub` callback into the new `onSuccess` callback, same
effect).

Text/copy for the claim-vs-change distinction should follow the existing
read-only-view precedent already present in this file (the two-branch
`if (needsNameClaim) { ... } else { ... }` Text block already exists at
lines 111-119 for read-only mode — extend the same branching to the
Save-button-visible/editing-mode copy, rather than inventing new copy).

### `MainNavHost.kt` (modified, `app/app/src/main/java/com/bringyour/network/ui/`)

Add a JWT-refresh-on-app-foreground hook, matching iOS's app-root
`scenePhase` → `refreshJwtOnForeground()` (`NetworkApp.swift:102-108,176-180`) —
deliberately app-wide, not scoped to the Profile screen, since an
out-of-band name change (another device, the web dashboard) should
propagate anywhere the JWT-derived name is shown, not just when Profile
happens to be open.

This file already has one `DisposableEffect(lifecycleOwner,
pendingSolanaSubReference) { LifecycleEventObserver { _, event -> if
(event == Lifecycle.Event.ON_RESUME) { ... } } }` block (a Solana
pending-subscription poll, `MainNavHost.kt:356-369`) — add a sibling
observer of the same shape calling `device?.refreshToken(0)`, matching
this file's own established lifecycle-observation convention (also used
in `SettingsScreen.kt:1313-1320` for a battery-optimization check) rather
than introducing a new one.

## Data flow

Save/Claim tap → `Api.changeNetworkName`/`claimNetworkName` → on success,
`device.refreshToken(0)` (ViewModel) → `onSuccess` callback →
`accountViewModel.refreshNetworkUser()` + exit editing mode (Screen) → UI
shows the new name. On error → `networkNameError` set, shown inline,
editing mode stays open (matches the existing read-only/editing-mode
pattern already in this file, no new error-display mechanism needed).

Foreground resume (anywhere in the app) → `device.refreshToken(0)`,
independent of whether Profile is the visible screen.

## Testing / verification

Same as PR1/PR2 — no local Android build toolchain; CI
(`assembleGithubDebug`/`assemblePlayDebug`/`assembleSolana_dappDebug`/
`assembleEthos_dappDebug`) is the verification gate. This PR touches only
shared `main` files, so all 4 flavor builds exercise the identical code
path (no per-flavor divergence risk like PR2's Google Sign-In split).

## Out of scope

- Any server-side change — the cooldown logic in `account_controller.go`
  already exists and is correct; nothing to fix there.
- Removing the legacy `/network/user/update` route or its Go handler —
  left alone server-side in case anything else still depends on it; this
  PR only stops Android from calling it.
- Final upstream squash (Phase 4, separate plan, after this PR merges).
