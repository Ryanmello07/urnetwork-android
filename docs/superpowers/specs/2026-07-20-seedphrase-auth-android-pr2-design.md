# Seedphrase-auth Android port — PR2 (Sign-In Methods + AddAuthMethodSheet) design

Date: 2026-07-20
Status: Approved (design phase) — see implementation plan for execution.

## Context

PR1 (`Ryanmello07/urnetwork-android#6`, merged into `beta/custom-server`) shipped seedphrase login, instant account creation, and guest-mode removal across all 4 flavors. This PR (PR2) is the second of three planned follow-ups from `docs/superpowers/specs/2026-07-19-seedphrase-auth-android-port-design.md`'s Phase 2: a Settings "Sign-In Methods" list (view + remove existing auth methods) and an `AddAuthMethodSheet` (add a new one), direct structural ports of iOS's shipped `AuthMethods.swift` / `AddAuthSheet.swift` / the "Sign-In Methods" section of `SettingsForm-iOS.swift`.

Confirmed via `grep` across `app/app/src/main/java/com/bringyour/network/ui/{account,settings}/*.kt`: zero existing references to `authTypes`, `wallet_address`, or multi-auth-method state anywhere in the Android app today — this is genuinely net-new, not a modification of existing partial support.

## Verified SDK contract

Both iOS and Android bind the same Go SDK (`github.com/Ryanmello07/urnetwork-sdk`, `beta/custom-server` branch). Verified directly against `api.go` and `network_user_view_controller.go` in that repo (not inferred from iOS's Swift bindings alone):

```go
// api.go:2338
type AddAuthArgs struct {
    UserAuth    string          // json: user_auth
    Password    string          // json: password
    AuthJwt     string          // json: auth_jwt
    AuthJwtType string          // json: auth_jwt_type
    WalletAuth  *WalletAuthArgs // json: wallet_auth
}
func (self *Api) AddAuth(args *AddAuthArgs, callback AddAuthCallback)
// AddAuthResult { Error *AddAuthError }  // AddAuthError { Message string }

// api.go:2372
type RemoveAuthArgs struct {
    AuthType string // json: auth_type
}
func (self *Api) RemoveAuth(args *RemoveAuthArgs, callback RemoveAuthCallback)
// RemoveAuthResult { Error *RemoveAuthError }  // RemoveAuthError { Message string }

// api.go:130
type WalletAuthArgs struct {
    PublicKey  string // json: wallet_address
    Signature  string // json: wallet_signature
    Message    string // json: wallet_message
    Blockchain string // json: blockchain
}

// api.go:174
type AuthWalletChallengeArgs struct {
    WalletAddress string
    Blockchain    Blockchain
}
func (self *Api) AuthWalletChallenge(args *AuthWalletChallengeArgs, callback AuthWalletChallengeCallback)
// AuthWalletChallengeResult { Challenge, Timestamp, ExpiresIn, MessageTemplate string/int64, Error *ApiError }

// network_user_view_controller.go:30
type NetworkUser struct {
    UserId        *Id
    UserName      string
    UserAuth      string
    Verified      bool
    AuthType      string      // legacy singular fallback
    NetworkName   string
    WalletAddress string
    AuthTypes     *StringList // json: auth_types — the array this feature reads
}
```

Gomobile's Android binding convention (already established in this codebase — `AuthWalletChallengeArgs`/`WalletAuthArgs` are already used, unprefixed, in `LoginCreateNetworkViewModel.kt`/`SolanaWalletAuth.kt`) exposes these as Kotlin classes with the same names, PascalCase fields lowered to camelCase, and callback-lambda methods (`api.addAuth(args) { result, err -> ... }`), consistent with every other SDK call already in this codebase.

## Goal

Port iOS's account-security-methods UI to Android, shared across all 4 flavors (`github`, `play`, `solana_dapp`, `ethos_dapp` — see PR1's flavor-naming note), landing entirely in the shared `main` source set.

## Components

### `AuthMethods.kt` (new, `app/app/src/main/java/com/bringyour/network/ui/settings/`)

Direct port of iOS's `AuthMethods.swift`, three functions:
- `authTypesContains(authTypes: StringList?, method: String): Boolean`
- `parseAuthMethods(networkUser: NetworkUser): List<String>` — reads `authTypes` if present and non-empty; else falls back to the legacy `authType` + `userAuth` (email-shaped values inferred as `"email"`).
- `methodDisplayName(method: String): String` — `"email"→"Email"`, `"google"→"Google"`, `"apple"→"Apple"`, `"solana"→"Solana Wallet"`, `"seedphrase"→"Seedphrase"`, else `method.replaceFirstChar { it.uppercase() }`.

### `AddAuthMethodSheet.kt` (new, same directory)

A `ModalBottomSheet` (Android's sheet idiom — the plan's Global Constraints will note this replaces iOS's `NavigationStack`-wrapped sheet, not a literal 1:1 view-hierarchy port) containing:
- A method selector — **Google / Wallet / Email / Seedphrase only, Apple omitted entirely** (not shown as a disabled/informational option — there's no Android equivalent to "Sign in with Apple" and no other part of this app offers one, so a picker entry that always says "not available here" is dead UI, not a helpful cross-platform parity signal). Dropdown-style, matching this codebase's existing picker conventions — the plan will identify the exact reusable component during its file-structure research pass.
- Per-method content:
  - **Google**: reuses the existing `GoogleSignInClient`/launcher pattern already used in each flavor's `LoginInitial.kt`. Per PR1: `play` (`google`-dir), `ethos_dapp`, and `solana_dapp` all have Google Sign-In (`googleLogin`/`googleAuthInProgress`/`allowGoogleSso`); only `github` (`ungoogle`-dir) lacks it. The sheet must conditionally omit the Google option on `github`, mirroring how `LoginInitial.kt` already conditionally has those params per flavor. Since `AddAuthMethodSheet.kt` lives in shared `main` (unlike `LoginInitial.kt`, which is per-flavor), it determines this via the existing per-flavor `BuildConfig.BRINGYOUR_BUNDLE_SSO_GOOGLE` field (already set per flavor in `app/app/build.gradle`'s `productFlavors` block: `false` for `github`, `true` for the other 3) — a `main`-visible compile-time constant, not a new mechanism.
  - **Wallet — corrected from the original iOS-modeled sketch, verified against actual Android code**: Android's Solana wallet connection is NOT iOS's deep-link-and-poll pattern (`ConnectWalletProviderViewModel`/`pendingAddAuthSignatureHandler`/60s polling loop). It uses Mobile Wallet Adapter (MWA): `requestAndSignSolanaChallenge(activityResultSender, api)` in `SolanaWalletAuth.kt:41-126` is a single `suspend` function, already proven in the login flow (`LoginInitial.kt`'s `connectSolanaWallet`), that fetches a fresh challenge via `Api.authWalletChallenge`, opens the wallet through `MobileWalletAdapter.transact(activityResultSender) { ... }` (OS-native wallet picker, no separate Phantom/Solflare buttons needed — MWA handles provider selection), and returns a `SolanaChallengeSignResult` (`Success(SolanaSignedChallenge(publicKey, message, signature))` / `NoWalletFound` / `Failure(Throwable)`) — no deep link, no polling, no pending-callback field. The sheet's Wallet section is one "Connect Wallet" button that launches this suspend call in a coroutine, then on `Success` calls `Api.addAuth(AddAuthArgs(walletAuth = WalletAuthArgs().apply { publicKey = signed.publicKey; signature = signed.signature; message = signed.message; blockchain = "solana" }))`. `SettingsScreen.kt` already receives `activityResultSender: ActivityResultSender?` as a parameter (confirmed: `app/app/src/main/java/com/bringyour/network/ui/settings/SettingsScreen.kt:137`) — no new plumbing needed to get it into the sheet.
  - **Email**: two text fields (email, password ≥ 12 chars) — reuses `URTextInput` (already used throughout the login flow).
  - **Seedphrase**: one-line explainer ("A new seedphrase will be generated and linked to your account"), no extra fields.
- One "Add Sign-In Method" button, shown only for Email/Seedphrase (Google completes on its own native callback; Wallet completes when its single suspend call returns, matching iOS's "no separate Add button" treatment for non-form methods).
- On sheet dismiss: cancel the wallet-connect coroutine (standard `Job.cancel()` on the `CoroutineScope`/`rememberCoroutineScope()` job launched for `requestAndSignSolanaChallenge`, via `DisposableEffect`'s `onDispose` or the sheet's dismiss callback) so a dismissed sheet doesn't keep a stale MWA transaction running. This is simpler than iOS's incomplete fix (which left one polling path uncancelled) precisely because Android's flow has no separate "connect" and "sign" polling stages to track — there is exactly one coroutine to cancel.

### `SettingsScreen.kt` / `SettingsViewModel.kt` (modified)

New "Sign-In Methods" section (added alongside the existing sections in `SettingsScreen.kt` — this file is already 1500+ lines with many sections; adding one more section inline matches its existing convention rather than requiring a new file, since `AddAuthMethodSheet.kt` already carries the bulk of the new UI logic):
- Lists `parseAuthMethods(networkUser)`, each row: `methodDisplayName(method)` + a destructive "Remove" button → confirmation dialog → `Api.removeAuth(RemoveAuthArgs(authType = method))`.
- "Add sign-in method" row → opens `AddAuthMethodSheet`.
- `SettingsViewModel` gains: sheet-visibility state, remove-confirmation state (which method is pending removal), and `refreshNetworkUser()` — called **only on the success path** of every add/remove, never unconditionally after a catch (iOS's shipped bug, already documented in the Phase 1 spec as something to avoid, applies here too since this is where that logic actually lives).

## Data flow

`AddAuthMethodSheet` calls `Api.addAuth(...)` directly (no ViewModel indirection needed for a one-shot sheet — same pattern as PR1's `CreateNetworkInstant.kt`), then invokes a passed-in `onAdded: () -> Unit` callback that the `SettingsScreen` wires to `settingsViewModel.refreshNetworkUser()` + dismiss. "Remove" flow: confirmation dialog → `Api.removeAuth(...)` → on success, `refreshNetworkUser()` + dismiss confirmation; on error, show the error inline in the confirmation dialog, dialog stays open.

## Testing / verification

No local Android build toolchain — CI (`assembleGithubDebug`/`assemblePlayDebug`/`assembleSolana_dappDebug`/`assembleEthos_dappDebug`, all 4 real flavors per PR1's Phase 0 fix) is the verification gate, same as PR1.

## Out of scope

- Profile name-claim logic (PR3, separate plan).
- Final upstream squash (Phase 4, separate plan, after PR3 merges).
- Any SDK-side (Go) changes — the fork SDK already has every symbol this PR needs, verified directly above.
