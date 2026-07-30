# Android — Regenerate Seedphrase Settings Section (bug fix) design

Date: 2026-07-20
Status: Approved (design phase) — see implementation plan for execution.

## Context

Live-testing PR2 (merged, `beta/custom-server`) surfaced two related problems, reported directly against the built APK:

1. **No "Regenerate Seedphrase" section exists in Settings.** iOS has a dedicated section (`SettingsForm-iOS.swift:141-196`) directly above "Sign-In Methods" — "Generate Seedphrase" if the account has none, "Regenerate Seedphrase" if it does — that Android's PR2 never ported.
2. **`AddAuthMethodSheet`'s "Seedphrase" option doesn't work** — tapping it shows a generic "Sign-in method added successfully" toast and nothing else; the user never sees the actual seedphrase words.

Investigation found (2) isn't a missing-UI bug, it's structural: `AddAuthMethodSheet`'s Seedphrase option calls `Api.addAuth(AddAuthArgs())` (empty args — server generates and links a phrase server-side), and `AddAuthResult` only carries `{ error }`, never the phrase itself. There is no way to retrieve the generated words from that call. Only `Api.generateSeedphrase()`/`Api.regenerateSeedphrase()` return `{ seedphrase, error }`. iOS has the exact same gap in its own `AddAuthSheet.swift`'s "seedphrase" case (confirmed by reading its source — it also just shows a snackbar and dismisses) — this is a shared, pre-existing latent bug, not an Android regression.

This means (1) and (2) collapse into one fix: build the dedicated Generate/Regenerate section (which is the only code path that actually returns and displays the phrase), and remove the sheet's broken Seedphrase option rather than leave a permanently-unviewable trap in place (confirmed with the user — see decision below).

**Rescue path for the user who already hit this:** their account now has `authTypes` containing `"seedphrase"` (the earlier `addAuth` call did succeed server-side, per the "Sign-in method added successfully" toast). Once this section ships, `authTypesContains(networkUser.authTypes, "seedphrase")` will correctly evaluate `true` for them, so the section shows "Regenerate Seedphrase" (not "Generate," which would be the wrong action) — tapping it calls `regenerateSeedphrase()`, which invalidates the old (never-seen) phrase and returns a new one they *do* get shown. This is the intended, correct recovery path — no separate server-side remediation needed.

## Decision (confirmed with the user)

Remove the "Seedphrase" option from `AddAuthMethodSheet`'s method picker entirely (Google/Wallet/Email remain). The new dedicated section is the one correct way to obtain a seedphrase going forward. This is a deliberate improvement over strict iOS parity, not a port of iOS's (also-broken) sheet option.

## Verified SDK contract

Confirmed directly against `api.go` in `github.com/Ryanmello07/urnetwork-sdk` (`beta/custom-server` branch):

```go
// api.go:2288
type GenerateSeedphraseArgs struct{}
func (self *Api) GenerateSeedphrase(args *GenerateSeedphraseArgs, callback GenerateSeedphraseCallback)
// GenerateSeedphraseResult { Seedphrase string; Error *ApiError }

// api.go:2313
type RegenerateSeedphraseArgs struct{}
func (self *Api) RegenerateSeedphrase(args *RegenerateSeedphraseArgs, callback RegenerateSeedphraseCallback)
// RegenerateSeedphraseResult { Seedphrase string; Error *ApiError }

// api.go:116 — a shared error type used across many result types (not method-specific like AddAuthError)
type ApiError struct {
    Message string
}
```

Both args structs are empty (no fields) — the request body carries nothing, the server derives everything from the JWT-authenticated session. Gomobile's established Kotlin binding convention (already used throughout this codebase) exposes these as `GenerateSeedphraseArgs()`/`RegenerateSeedphraseArgs()` (no-arg constructors), callback-lambda methods (`api.generateSeedphrase(args) { result, err -> ... }`), and `result?.error?.message` for the error path — implementer must independently confirm the exact Kotlin class name for the shared error type (`ApiError` vs. something renamed) during pre-write verification, same discipline as every other new SDK call in this port.

## Goal

Port iOS's Generate/Regenerate Seedphrase section to Android Settings, reusing the already-built `SeedphraseDisplayScreen.kt` (from PR1) to actually show the result, and remove `AddAuthMethodSheet`'s broken Seedphrase option.

## Components

### `SettingsViewModel.kt` (modified)

Add, following the exact `addAuth`/`removeAuth` `StateFlow` + callback-lambda convention already in this file:

- `generatedSeedphrase: StateFlow<String?>` — holds the just-generated/regenerated phrase; `null` means "not showing the display screen." Set on success, cleared by a `dismissSeedphraseDisplay()` method.
- `isGeneratingSeedphrase: StateFlow<Boolean>`, `isRegeneratingSeedphrase: StateFlow<Boolean>` — separate flags (matching iOS's separate `isGeneratingSeedphrase`/`isRegeneratingSeedphrase`), since the section shows only one button at a time (Generate xor Regenerate) but keeping them distinct avoids ambiguity if that ever changes.
- `seedphraseError: StateFlow<String?>`.
- `generateSeedphrase: (onError: (String) -> Unit) -> Unit` — wraps `Api.generateSeedphrase(GenerateSeedphraseArgs())`; on success sets `_generatedSeedphrase.value = result.seedphrase` (no separate `onSuccess` callback needed — the screen reacts to `generatedSeedphrase` becoming non-null, matching how the display screen will be driven).
- `regenerateSeedphrase: (onError: (String) -> Unit) -> Unit` — same shape, wraps `Api.regenerateSeedphrase(RegenerateSeedphraseArgs())`.
- `dismissSeedphraseDisplay: () -> Unit` — sets `_generatedSeedphrase.value = null`.

**No `refreshNetworkUser()` call is threaded through this ViewModel** — that call belongs to `SettingsScreen.kt` (which already owns `accountViewModel`), triggered when the display screen's "I've Saved My Seedphrase" button is tapped (`onConfirmed`), matching how `AddAuthMethodSheet`'s `onAdded` already triggers `accountViewModel.refreshNetworkUser()` in the Screen layer, not the ViewModel layer — refresh-only-on-the-real-completion-point stays consistent with this port's existing convention.

### `SettingsScreen.kt` (modified)

New "Seedphrase" section, inserted between the existing "Balance codes link" row and the "Sign-In Methods" section (matching iOS's exact ordering — `SettingsForm-iOS.swift:141` sits directly above `:198`'s Sign-In Methods):

- `URTextInputLabel("Seedphrase")`.
- One row: `Text("Regenerate Seedphrase")` if `authTypesContains(networkUser?.authTypes, "seedphrase")` else `Text("Generate Seedphrase")`, as a `TextButton` (matching the existing "Sign-In Methods" row's `TextButton` style, not a full `URButton` — this is a same-visual-weight list row, not a primary CTA), with a `CircularProgressIndicator(modifier = Modifier.width(16.dp))` shown when the relevant `isGenerating`/`isRegenerating` flag is true — reusing this exact, already-proven sizing from `AuthCodeCreateDialog.kt:51-53` (same package), matching iOS's inline `ProgressView()`.
- Caption text below: `"A seedphrase lets you recover your account if you lose access."` (verbatim from iOS).
- Confirmation gate before executing: reuse the exact `URDialog` pattern already used for "Remove sign-in method" (`SettingsScreen.kt:388-430`) — tapping the Generate/Regenerate row sets a `pendingSeedphraseAction: SeedphraseAction?` state (`enum class SeedphraseAction { GENERATE, REGENERATE }`), which drives a confirmation `URDialog` ("Generate a new seedphrase?" / "Regenerate your seedphrase? Your current seedphrase will stop working." — the regenerate copy needs the explicit invalidation warning iOS's UI implies through the button label alone; Android's dialog can be more explicit since it already has a confirm-step precedent to match). Confirming calls `settingsViewModel.generateSeedphrase`/`regenerateSeedphrase` with an `onError` that sets a dialog-local error `String?` shown via `URInlineErrorText`, matching the remove-confirmation dialog's own error-handling shape exactly.
- **Full-screen `SeedphraseDisplayScreen` render**, gated on `settingsViewModel.generatedSeedphrase.collectAsState().value != null`, placed at the root of `SettingsScreen`'s outer composable (a sibling to the existing dialogs, not nested inside the `Scaffold`'s content) so it genuinely covers the whole screen — matching the advisor-flagged constraint that the seedphrase must never be threaded through a nav argument (nav args persist in the back stack; this is a secret). `onConfirmed = { settingsViewModel.dismissSeedphraseDisplay(); accountViewModel.refreshNetworkUser() }`, `onBack = { settingsViewModel.dismissSeedphraseDisplay() }` (the screen's own built-in `BackHandler`/confirm-dialog — "This is the ONLY time you'll see your seedphrase" — already exists and needs no changes).

### `AddAuthMethodSheet.kt` (modified)

Remove the `SEEDPHRASE` entry from the `AddAuthMethod` enum and every branch that references it: the `methods` list construction (both the `showGoogleOption` true/false branches), the button-label `when`, the `formValid`/`onAddClick`'s `SEEDPHRASE` case, and the "Add Sign-In Method" button's visibility condition (`selectedMethod == EMAIL || selectedMethod == SEEDPHRASE` → just `selectedMethod == EMAIL`).

## Data flow

Tap Generate/Regenerate row → confirm dialog → confirm → `Api.generateSeedphrase`/`regenerateSeedphrase` → on success, `generatedSeedphrase` StateFlow becomes non-null → `SeedphraseDisplayScreen` renders full-screen, showing the words → user taps "I've Saved My Seedphrase" → dismiss + `refreshNetworkUser()` (so the Sign-In Methods list below correctly shows/keeps "Seedphrase" and the section correctly flips to "Regenerate" for next time). On error at the confirm-dialog step → inline error shown, dialog stays open (same shape as remove-confirmation).

## Testing / verification

Same as prior PRs in this port — no local Android build toolchain; CI (`assembleGithubDebug`/`assemblePlayDebug`/`assembleSolana_dappDebug`/`assembleEthos_dappDebug`) is the verification gate. Shared `main` only, no per-flavor split needed (these are plain `Api` methods, not flavor-gated).

## Out of scope

- Any server-side change.
- Re-adding a Seedphrase option to `AddAuthMethodSheet` in any form.
- The AddAuthMethodSheet/Settings-list refresh-on-success discipline already established by PR2 — unchanged, this fix follows the same rule.
