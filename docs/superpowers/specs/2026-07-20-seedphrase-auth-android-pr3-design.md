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

**Correction (user feedback after first spec draft): do not resurrect the
old commented-out UI.** The dead block (`// todo - temporarily remove
edits until new API changes are made`) is Android's own pre-existing,
pre-this-feature design — a plain `Text("Edit profile")`/`Text("Save")`/
`Text("Cancel")` set of clickable links, no pencil icon, no read/edit view
swap, and (confirmed while re-checking this section) it has no
`needsNameClaim` awareness at all, since that concept didn't exist yet
when it was written. Reusing it would not match iOS and would leave the
claim-vs-change UX to be invented from scratch anyway. Instead, this
section is rebuilt from scratch to copy iOS's actual `ProfileView.swift`
flow (pencil-icon-triggered dual-view swap between a read-only display and
an editing form), using Android's existing component library
(`URTextInput`, `URButton`, `TextButton`, `URInlineErrorText` — all
already proven in PR2's `AddAuthMethodSheet`/`SettingsScreen.kt`), not the
old dead code. The currently-*active* (non-commented) `URTextInput`
that's always rendered with `enabled = isEditingProfile && !isUpdating`
is also replaced — iOS never shows an edit-capable field in read-only
mode, it shows a separate plain `Text`, so Android's single-always-visible-
input structure changes too, not just the commented block.

Read-only mode (`!isEditingProfile`), modeled on `ProfileView.swift:93-122`,
using this codebase's own existing edit-affordance convention verbatim
(confirmed at `SettingsScreen.kt:684-711`, the device-rename row: a
`.clickable`-wrapped `Row` with a trailing `Icons.Filled.Edit` icon, not
a separate `IconButton`):

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable { setIsEditingProfile(true) },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text(networkName, style = MaterialTheme.typography.bodyLarge, color = Color.White)
    Icon(
        Icons.Filled.Edit,
        contentDescription = stringResource(id = R.string.edit_network_name), // new string resource, mirrors existing edit_device_name
        tint = TextMuted,
        modifier = Modifier.size(16.dp)
    )
}
Text(
    if (needsNameClaim) "Claim a custom network name to replace your auto-generated one"
    else "Tap the edit icon to change your network name",
    style = MaterialTheme.typography.bodySmall,
    color = TextMuted
)
```

Editing mode (`isEditingProfile`):

```kotlin
URTextInput(
    value = networkNameTextFieldValue,
    onValueChange = { /* existing debounce + validateNetworkName wiring, unchanged */ },
    label = stringResource(id = R.string.network_name_label),
    isValidating = networkNameIsValidating,
    isValid = networkNameIsValid,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
)
if (networkNameError != null) {
    URInlineErrorText(networkNameError)
}
Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(modifier = Modifier.weight(1f)) {
        URButton(
            onClick = {
                if (needsNameClaim) claimNetworkName(onSuccess, onError)
                else saveNetworkName(onSuccess, onError)
            },
            enabled = !isSavingNetworkName && networkNameTextFieldValue.text.isNotBlank(),
            isProcessing = isSavingNetworkName
        ) { buttonTextStyle -> Text("Save", style = buttonTextStyle) }
    }
    TextButton(onClick = cancelEdits) { Text("Cancel") }
}
```

This mirrors iOS exactly: `UrButton("Save", ...)` → `URButton(...)` (this
codebase's own equivalent primary button, no `modifier` param — same
`Box(modifier = Modifier.weight(1f)) { URButton(...) }` wrapper pattern
PR2 already established for exactly this "URButton has no modifier param"
constraint), plain `Button(action:) { Text("Cancel") }` → `TextButton`
(already this codebase's established lightweight-secondary-action
component, used for "Remove"/"Add sign-in method" in PR2's
`SettingsScreen.kt`), and the `needsNameClaim` caption branching directly
mirrors `ProfileView.swift:111-119`.

`onSuccess`/`onError` passed into `saveNetworkName`/`claimNetworkName`:
`onSuccess = { accountViewModel.refreshNetworkUser();
profileViewModel.setIsEditingProfile(false) }`, `onError = { msg ->
/* surfaced via profileViewModel.networkNameError, already set by the
ViewModel method itself before invoking onError */ }` — same shape as
PR2's `AddAuthMethodSheet` call sites, no `DisposableEffect`/`Sub`
subscription needed (unlike the old `updateSuccessSub` mechanism being
deleted from the ViewModel).

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
pending-subscription poll, `MainNavHost.kt:356-369`), and — same as that
block, which resumes by calling a ViewModel method
(`subscriptionBalanceViewModel.pollSolanaTransaction()`), not raw device
access — the new observer should follow the same shape rather than
reaching for `deviceManager` directly, which `MainNavHost.kt` doesn't
have in scope. `MainNavViewModel` (already Hilt-injected as a
`MainNavHost` parameter with a default,
`mainNavViewModel: MainNavViewModel = hiltViewModel<MainNavViewModel>()`)
already takes `deviceManager: DeviceManager` in its constructor and
already closes over it in a property lambda
(`setIntroFunnelLastPrompted`) — add a sibling method there, e.g.
`val refreshTokenOnForeground: () -> Unit = { deviceManager.device?.refreshToken(0) }`,
and call `mainNavViewModel.refreshTokenOnForeground()` from the new
lifecycle observer in `MainNavHost.kt`.

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
