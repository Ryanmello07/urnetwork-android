# Android Regenerate Seedphrase Settings Section — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Generate/Regenerate Seedphrase section to Android Settings (matching iOS, above Sign-In Methods) that actually shows the user their seedphrase, and remove `AddAuthMethodSheet`'s broken Seedphrase option (which can never show the phrase — `Api.addAuth` doesn't return it).

**Architecture:** Two modified files carry the new feature — `SettingsViewModel.kt` (new `generateSeedphrase`/`regenerateSeedphrase` methods wrapping the two dedicated SDK calls that DO return the phrase, plus the `StateFlow`s driving the UI) and `SettingsScreen.kt` (new section + confirm dialog + full-screen render of the existing `SeedphraseDisplayScreen` from PR1) — plus one file with matter removed (`AddAuthMethodSheet.kt`, dropping its non-functional Seedphrase option).

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, `com.bringyour.sdk` gomobile bindings.

## Global Constraints

- Reuses `SeedphraseDisplayScreen.kt` (`app/app/src/main/java/com/bringyour/network/ui/login/SeedphraseDisplayScreen.kt`, from PR1) as-is — no changes to that file. Its signature: `@Composable fun SeedphraseDisplayScreen(seedphrase: String, onConfirmed: () -> Unit, onBack: () -> Unit)`.
- **The seedphrase must never be passed through a `NavController` navigation argument** — it's a secret and nav args persist in the back stack. It is held only in `SettingsViewModel`'s `StateFlow` and rendered as a conditional full-screen composable directly inside `SettingsScreen.kt`'s composition, not via `navController.navigate(...)`.
- The new full-screen render must sit at the root of `SettingsScreen.kt`'s outer composable (a sibling to the existing dialogs/sheets: `AddAuthMethodSheet`, the remove-confirmation `URDialog`, etc.), not nested inside the `Scaffold`'s scrollable content — otherwise it won't actually cover the screen.
- Confirmation-before-action: both Generate and Regenerate go through a confirm `URDialog` first (regenerate explicitly warns the old phrase stops working), reusing the exact `URDialog` shape already proven for "Remove sign-in method" (`SettingsScreen.kt:388-430` on the current `beta/custom-server` HEAD).
- Verified SDK contract (from `github.com/Ryanmello07/urnetwork-sdk`, `beta/custom-server` branch, `api.go:2286-2331`):
  - `GenerateSeedphraseArgs {}` (no fields), `Api.generateSeedphrase(args, callback)`, `GenerateSeedphraseResult { seedphrase: String, error: ApiError? }`.
  - `RegenerateSeedphraseArgs {}` (no fields), `Api.regenerateSeedphrase(args, callback)`, `RegenerateSeedphraseResult { seedphrase: String, error: ApiError? }`.
  - `ApiError { message: String }` — a shared error type (not method-specific like `AddAuthError`); implementer must independently confirm the exact Kotlin class name resolves during pre-write checks, same discipline as every other new SDK call in this port.
- Refresh convention: `accountViewModel.refreshNetworkUser()` is called from `SettingsScreen.kt` (which owns `accountViewModel`), not threaded into `SettingsViewModel`, matching how `AddAuthMethodSheet`'s `onAdded` callback already triggers it — and only on the real completion point (`SeedphraseDisplayScreen`'s `onConfirmed`, i.e. after the user has actually seen and dismissed the phrase — not immediately on generate success, since the display screen is still showing at that point).
- No local Android build toolchain — verification is CI-push-based only (`assembleGithubDebug`/`assemblePlayDebug`/`assembleSolana_dappDebug`/`assembleEthos_dappDebug`), same as prior PRs. This fix touches only shared `main` files — no per-flavor split needed.

---

### Task 1: `SettingsViewModel.kt` — add seedphrase generate/regenerate state and methods

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/settings/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `com.bringyour.sdk.GenerateSeedphraseArgs`, `com.bringyour.sdk.RegenerateSeedphraseArgs` (SDK, per Global Constraints).
- Produces: `generatedSeedphrase: StateFlow<String?>`, `isGeneratingSeedphrase: StateFlow<Boolean>`, `isRegeneratingSeedphrase: StateFlow<Boolean>`, `generateSeedphrase: (onError: (String) -> Unit) -> Unit`, `regenerateSeedphrase: (onError: (String) -> Unit) -> Unit`, `dismissSeedphraseDisplay: () -> Unit` — all consumed by Task 2 (`SettingsScreen.kt`).

- [ ] **Step 1: Add the new imports**

Find the existing SDK imports near the top of the file (alongside `import com.bringyour.sdk.AddAuthArgs`) and add:

```kotlin
import com.bringyour.sdk.GenerateSeedphraseArgs
import com.bringyour.sdk.RegenerateSeedphraseArgs
```

- [ ] **Step 2: Add the new state and methods**

Find the `removeAuth` property (it ends with the closing `}` right before `val toggleRouteLocal: () -> Unit = { ... }`). Immediately after `removeAuth`'s closing `}` and before `val toggleRouteLocal`, insert:

```kotlin
    private val _generatedSeedphrase = MutableStateFlow<String?>(null)
    val generatedSeedphrase: StateFlow<String?> = _generatedSeedphrase

    private val _isGeneratingSeedphrase = MutableStateFlow(false)
    val isGeneratingSeedphrase: StateFlow<Boolean> = _isGeneratingSeedphrase

    private val _isRegeneratingSeedphrase = MutableStateFlow(false)
    val isRegeneratingSeedphrase: StateFlow<Boolean> = _isRegeneratingSeedphrase

    val generateSeedphrase: (onError: (String) -> Unit) -> Unit = { onError ->

        _isGeneratingSeedphrase.value = true

        deviceManager.device?.api?.generateSeedphrase(GenerateSeedphraseArgs()) { result, err ->
            viewModelScope.launch {
                _isGeneratingSeedphrase.value = false

                if (err != null) {
                    onError(err.message ?: "Failed to generate seedphrase")
                } else if (result?.error != null) {
                    onError(result.error.message ?: "Failed to generate seedphrase")
                } else if (result != null) {
                    _generatedSeedphrase.value = result.seedphrase
                }
            }
        } ?: run {
            _isGeneratingSeedphrase.value = false
            onError("Unable to connect. Please try again.")
        }
    }

    val regenerateSeedphrase: (onError: (String) -> Unit) -> Unit = { onError ->

        _isRegeneratingSeedphrase.value = true

        deviceManager.device?.api?.regenerateSeedphrase(RegenerateSeedphraseArgs()) { result, err ->
            viewModelScope.launch {
                _isRegeneratingSeedphrase.value = false

                if (err != null) {
                    onError(err.message ?: "Failed to regenerate seedphrase")
                } else if (result?.error != null) {
                    onError(result.error.message ?: "Failed to regenerate seedphrase")
                } else if (result != null) {
                    _generatedSeedphrase.value = result.seedphrase
                }
            }
        } ?: run {
            _isRegeneratingSeedphrase.value = false
            onError("Unable to connect. Please try again.")
        }
    }

    val dismissSeedphraseDisplay: () -> Unit = {
        _generatedSeedphrase.value = null
    }
```

Note: unlike `addAuth`/`removeAuth` (which take an `onSuccess: () -> Unit` callback), `generateSeedphrase`/`regenerateSeedphrase` only take `onError` — there is no separate success callback because the caller (Task 2) reacts to `generatedSeedphrase` becoming non-null to decide when to show the display screen, rather than being told "it worked" via a callback. This matches the spec's stated rationale exactly.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/SettingsViewModel.kt
git commit -m "feat: add generateSeedphrase/regenerateSeedphrase to SettingsViewModel"
```

---

### Task 2: `SettingsScreen.kt` — add the Seedphrase section, confirm dialog, and full-screen display

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsViewModel.generatedSeedphrase`/`.isGeneratingSeedphrase`/`.isRegeneratingSeedphrase`/`.generateSeedphrase`/`.regenerateSeedphrase`/`.dismissSeedphraseDisplay` (Task 1), `authTypesContains` (existing, `AuthMethods.kt`), `SeedphraseDisplayScreen` (existing, `ui/login/SeedphraseDisplayScreen.kt`), `AccountViewModel.refreshNetworkUser` (existing).

- [ ] **Step 1: Add the missing import**

Find the existing imports block and add:

```kotlin
import com.bringyour.network.ui.login.SeedphraseDisplayScreen
```

- [ ] **Step 2: Collect the new ViewModel state and add local dialog state (outer composable)**

Find this block near the top of the outer `SettingsScreen` composable:

```kotlin
    var presentAddAuthSheet by remember { mutableStateOf(false) }
    var pendingRemoveMethod by remember { mutableStateOf<String?>(null) }
    var removeAuthError by remember { mutableStateOf<String?>(null) }
```

Immediately after it, add:

```kotlin
    val generatedSeedphrase = settingsViewModel.generatedSeedphrase.collectAsState().value
    val isGeneratingSeedphrase = settingsViewModel.isGeneratingSeedphrase.collectAsState().value
    val isRegeneratingSeedphrase = settingsViewModel.isRegeneratingSeedphrase.collectAsState().value

    var pendingSeedphraseAction by remember { mutableStateOf<SeedphraseAction?>(null) }
    var seedphraseActionError by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 3: Add the `SeedphraseAction` enum**

At the very end of the file (after the last `@Preview` function, outside any composable), add:

```kotlin
private enum class SeedphraseAction { GENERATE, REGENERATE }
```

- [ ] **Step 4: Pass `authMethods`-derived seedphrase flag and the new callbacks into the inner composable call**

Find the inner `SettingsScreen(...)` call's tail (ending `authMethods = authMethods, onRemoveAuthMethod = ..., onAddAuthMethodClick = ...,`). Immediately after `onAddAuthMethodClick = { presentAddAuthSheet = true },`, add:

```kotlin
        hasSeedphrase = authTypesContains(networkUser?.authTypes, "seedphrase"),
        isGeneratingSeedphrase = isGeneratingSeedphrase,
        isRegeneratingSeedphrase = isRegeneratingSeedphrase,
        onSeedphraseActionClick = { action -> pendingSeedphraseAction = action },
```

- [ ] **Step 5: Render the confirmation dialog and the full-screen seedphrase display (outer composable)**

Find where the remove-confirmation `URDialog` block ends (the `URDialog(visible = pendingRemoveMethod != null, ...)` block's closing `}`, right before the outer composable's own closing `}`). Immediately after that `URDialog` block, add:

```kotlin
    URDialog(
        visible = pendingSeedphraseAction != null,
        onDismiss = {
            pendingSeedphraseAction = null
            seedphraseActionError = null
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val isRegenerate = pendingSeedphraseAction == SeedphraseAction.REGENERATE
            Text(
                if (isRegenerate) "Regenerate your seedphrase?" else "Generate a seedphrase?",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (isRegenerate) {
                    "Your current seedphrase will stop working. You'll be shown a new one to save."
                } else {
                    "A seedphrase lets you recover your account if you lose access. You'll be shown it once."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            if (seedphraseActionError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                URInlineErrorText(seedphraseActionError)
            }
            Spacer(modifier = Modifier.height(16.dp))
            URButton(
                onClick = {
                    seedphraseActionError = null
                    val onError: (String) -> Unit = { msg -> seedphraseActionError = msg }
                    if (isRegenerate) {
                        settingsViewModel.regenerateSeedphrase(onError)
                    } else {
                        settingsViewModel.generateSeedphrase(onError)
                    }
                    pendingSeedphraseAction = null
                },
                enabled = !isGeneratingSeedphrase && !isRegeneratingSeedphrase,
                isProcessing = isGeneratingSeedphrase || isRegeneratingSeedphrase
            ) { buttonTextStyle ->
                Text(if (isRegenerate) "Regenerate" else "Generate", style = buttonTextStyle)
            }
        }
    }

    if (generatedSeedphrase != null) {
        SeedphraseDisplayScreen(
            seedphrase = generatedSeedphrase,
            onConfirmed = {
                settingsViewModel.dismissSeedphraseDisplay()
                accountViewModel.refreshNetworkUser()
            },
            onBack = {
                settingsViewModel.dismissSeedphraseDisplay()
            }
        )
    }
```

Note: `pendingSeedphraseAction = null` is set immediately when the confirm button is tapped (not inside the success callback) — the confirmation dialog's job is done once the request is fired; the full-screen `SeedphraseDisplayScreen` (driven by `generatedSeedphrase != null`) is what shows next, and the `isProcessing` spinner on the confirm button covers the brief gap between tapping confirm and the API call resolving in the case the dialog is still visible during that window. This matches the remove-confirmation dialog's own approach of closing eagerly on the destructive action's initiation.

- [ ] **Step 6: Add the 4 new parameters to the inner composable's signature**

Find the inner `SettingsScreen` composable's parameter list, specifically the 3 params added by PR2 (`authMethods: List<String>, onRemoveAuthMethod: (String) -> Unit, onAddAuthMethodClick: () -> Unit,`) right before the closing `) {`. Immediately after `onAddAuthMethodClick: () -> Unit,`, add:

```kotlin
    hasSeedphrase: Boolean,
    isGeneratingSeedphrase: Boolean,
    isRegeneratingSeedphrase: Boolean,
    onSeedphraseActionClick: (SeedphraseAction) -> Unit,
```

- [ ] **Step 7: Add the Seedphrase section to the inner composable's body**

Find the "Balance codes link" `Row` block and the `Spacer(modifier = Modifier.height(32.dp))` that follows it, immediately before the `/** * Sign-In Methods */` comment and `URTextInputLabel(text = "Sign-In Methods")`. Insert the new section between that `Spacer` and the Sign-In Methods label:

```kotlin
            /**
             * Seedphrase
             */
            URTextInputLabel(text = "Seedphrase")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (hasSeedphrase) "Regenerate Seedphrase" else "Generate Seedphrase",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if ((hasSeedphrase && isRegeneratingSeedphrase) || (!hasSeedphrase && isGeneratingSeedphrase)) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp))
                    } else {
                        TextButton(onClick = {
                            onSeedphraseActionClick(
                                if (hasSeedphrase) SeedphraseAction.REGENERATE else SeedphraseAction.GENERATE
                            )
                        }) {
                            Text(
                                if (hasSeedphrase) "Regenerate" else "Generate",
                                color = BlueMedium
                            )
                        }
                    }
                }
            }
            Text(
                "A seedphrase lets you recover your account if you lose access.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(32.dp))

```

Add the missing import if not already present in this file: `androidx.compose.material3.CircularProgressIndicator` (already used elsewhere in this codebase, e.g. `AuthCodeCreateDialog.kt`, but confirm it's imported in `SettingsScreen.kt` specifically — add it if not).

- [ ] **Step 8: Update all 5 `@Preview` functions**

Every `@Preview` composable that calls the inner `SettingsScreen(...)` needs the 4 new required parameters added to its call site. Add, alongside the existing `authMethods = listOf("email"), onRemoveAuthMethod = {}, onAddAuthMethodClick = {}` lines in each of the 5 previews:

```kotlin
            hasSeedphrase = false,
            isGeneratingSeedphrase = false,
            isRegeneratingSeedphrase = false,
            onSeedphraseActionClick = {},
```

- [ ] **Step 9: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/SettingsScreen.kt
git commit -m "feat: add Seedphrase Generate/Regenerate section to Settings, above Sign-In Methods"
```

---

### Task 3: `AddAuthMethodSheet.kt` — remove the broken Seedphrase option

**Files:**
- Modify (full-file replace): `app/app/src/main/java/com/bringyour/network/ui/settings/AddAuthMethodSheet.kt`

**Interfaces:**
- Produces: `AddAuthMethodSheet`'s public signature is unchanged (still `visible, onDismiss, showGoogleOption, activityResultSender, isAddingAuth, addAuth, onAdded`) — Task 2 and the rest of `SettingsScreen.kt` are unaffected by this task.

- [ ] **Step 1: Replace the full file**

```kotlin
package com.bringyour.network.ui.settings

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.bringyour.network.MainApplication
import com.bringyour.network.ui.components.ButtonStyle
import com.bringyour.network.ui.components.URButton
import com.bringyour.network.ui.components.URInlineErrorText
import com.bringyour.network.ui.components.URTextInput
import com.bringyour.network.ui.login.SolanaChallengeSignResult
import com.bringyour.network.ui.login.requestAndSignSolanaChallenge
import com.bringyour.network.ui.theme.TextMuted
import com.bringyour.sdk.AddAuthArgs
import com.bringyour.sdk.WalletAuthArgs
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.launch

private enum class AddAuthMethod { GOOGLE, WALLET, EMAIL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAuthMethodSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    showGoogleOption: Boolean,
    activityResultSender: ActivityResultSender?,
    isAddingAuth: Boolean,
    addAuth: (AddAuthArgs, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onAdded: () -> Unit,
) {
    if (!visible) {
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val methods = remember(showGoogleOption) {
        if (showGoogleOption) {
            listOf(AddAuthMethod.GOOGLE, AddAuthMethod.WALLET, AddAuthMethod.EMAIL)
        } else {
            listOf(AddAuthMethod.WALLET, AddAuthMethod.EMAIL)
        }
    }
    var selectedMethod by remember(methods) { mutableStateOf(methods.first()) }

    var email by remember { mutableStateOf(TextFieldValue("")) }
    var password by remember { mutableStateOf(TextFieldValue("")) }
    var addError by remember { mutableStateOf<String?>(null) }

    var walletConnectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isConnectingWallet by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            walletConnectJob?.cancel()
        }
    }

    val formValid = when (selectedMethod) {
        AddAuthMethod.EMAIL -> email.text.isNotBlank() && password.text.length >= 12
        else -> true
    }

    val onAddClick: () -> Unit = {
        addError = null
        when (selectedMethod) {
            AddAuthMethod.EMAIL -> {
                val args = AddAuthArgs()
                args.userAuth = email.text
                args.password = password.text
                addAuth(
                    args,
                    {
                        Toast.makeText(context, "Sign-in method added successfully", Toast.LENGTH_SHORT).show()
                        onAdded()
                    },
                    { msg -> addError = msg }
                )
            }
            else -> { /* Google/Wallet complete on their own callback, no explicit Add click */ }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Add a sign-in method",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                "Link another way to sign in to your account.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                methods.forEach { method ->
                    // URButton has no `modifier` parameter (checked against its
                    // real signature in URButton.kt) — wrap it in a weighted Box
                    // instead of trying to pass modifier through to URButton itself.
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        URButton(
                            style = if (method == selectedMethod) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                            onClick = {
                                selectedMethod = method
                                addError = null
                            }
                        ) { buttonTextStyle ->
                            Text(
                                when (method) {
                                    AddAuthMethod.GOOGLE -> "Google"
                                    AddAuthMethod.WALLET -> "Wallet"
                                    AddAuthMethod.EMAIL -> "Email"
                                },
                                style = buttonTextStyle
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedMethod) {
                AddAuthMethod.GOOGLE -> {
                    // Google Sign-In is per-flavor code (com.google.android.gms.*
                    // is not on github's classpath) -- every flavor's source set
                    // provides its own GoogleAddAuthButton with this exact
                    // signature (real impl on google/solana_dapp/ethos_dapp,
                    // no-op stub on ungoogle/github).
                    GoogleAddAuthButton(
                        addAuth = addAuth,
                        isAddingAuth = isAddingAuth,
                        onAdded = onAdded,
                        onError = { msg -> addError = msg }
                    )
                }
                AddAuthMethod.WALLET -> {
                    Text(
                        "Connect a Solana wallet to add it as a sign-in method.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    URButton(
                        onClick = {
                            walletConnectJob = scope.launch {
                                activityResultSender?.let { sender ->
                                    val api = (context.applicationContext as? MainApplication)?.api
                                    if (api == null) {
                                        addError = "Error connecting to wallet"
                                        return@launch
                                    }
                                    isConnectingWallet = true
                                    when (val result = requestAndSignSolanaChallenge(sender, api)) {
                                        is SolanaChallengeSignResult.Success -> {
                                            val walletAuth = WalletAuthArgs()
                                            walletAuth.publicKey = result.signed.publicKey
                                            walletAuth.signature = result.signed.signature
                                            walletAuth.message = result.signed.message
                                            walletAuth.blockchain = "solana"
                                            val args = AddAuthArgs()
                                            args.walletAuth = walletAuth
                                            addAuth(
                                                args,
                                                {
                                                    Toast.makeText(context, "Wallet sign-in method added", Toast.LENGTH_SHORT).show()
                                                    onAdded()
                                                },
                                                { msg -> addError = msg }
                                            )
                                        }
                                        is SolanaChallengeSignResult.NoWalletFound -> {
                                            addError = "No compatible wallet app found on this device."
                                        }
                                        is SolanaChallengeSignResult.Failure -> {
                                            Log.i("AddAuthMethodSheet", "Error connecting to wallet: ${result.error}")
                                            addError = "Error connecting to wallet"
                                        }
                                    }
                                    isConnectingWallet = false
                                }
                            }
                        },
                        enabled = !isAddingAuth && !isConnectingWallet,
                        isProcessing = isConnectingWallet
                    ) { buttonTextStyle ->
                        Text("Connect Wallet", style = buttonTextStyle)
                    }
                }
                AddAuthMethod.EMAIL -> {
                    URTextInput(
                        value = email,
                        onValueChange = { email = it },
                        label = "Email",
                        placeholder = "your@email.com",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    URTextInput(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        placeholder = "Enter a password",
                        isPassword = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Password must be at least 12 characters",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                }
            }

            if (addError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                URInlineErrorText(addError)
            }

            if (selectedMethod == AddAuthMethod.EMAIL) {
                Spacer(modifier = Modifier.height(16.dp))
                URButton(
                    onClick = onAddClick,
                    enabled = !isAddingAuth && formValid,
                    isProcessing = isAddingAuth
                ) { buttonTextStyle ->
                    Text("Add Sign-In Method", style = buttonTextStyle)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/AddAuthMethodSheet.kt
git commit -m "fix: remove broken Seedphrase option from AddAuthMethodSheet

Api.addAuth never returns the generated seedphrase, so this option could
add a seedphrase auth method the user could never see or use. The new
dedicated Seedphrase section in Settings (Generate/Regenerate) is the
only path that actually returns and displays the phrase."
```

---

### Task 4: Checkpoint — push and verify all 4 flavors build clean

- [ ] **Step 1: Push**

```bash
git push
```

- [ ] **Step 2: Watch CI**

```bash
gh run watch --repo Ryanmello07/urnetwork-android $(gh run list --repo Ryanmello07/urnetwork-android --branch fix/android-regenerate-seedphrase --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: all 4 `Build Android app (github|play|solana_dapp|ethos_dapp)` jobs green. Fix any failures before proceeding.

- [ ] **Step 3: Report readiness**

Draft PR #9 (already open) is CI-green and ready for the user's live preview check, matching the same "stop before merge, let the user test" pattern used for the prior PR in this port. Do not mark ready-for-review or merge until the user confirms.

## Self-review notes

- **Spec coverage:** Task 1 covers `SettingsViewModel.kt`'s new state/methods. Task 2 covers the new Settings section, confirm dialog, and full-screen display wiring — including the nav-argument-avoidance constraint (rendered directly in composition, never via `navController.navigate`). Task 3 covers the sheet-option removal decision. All three "Components" from the spec have a matching task.
- **Placeholder scan:** no TBD/TODO in code steps. The one verify-before-assume note (confirming `ApiError`'s exact Kotlin class name) is a concrete instruction with a clear resolution path, not an unresolved gap — consistent with how every other new-SDK-call task in this port has been written.
- **Type consistency:** `generateSeedphrase: (onError: (String) -> Unit) -> Unit` / `regenerateSeedphrase: (same shape)` are identical between Task 1 (defined) and Task 2 (consumed, called as `settingsViewModel.generateSeedphrase(onError)`). `hasSeedphrase: Boolean` is computed once in the outer composable (Task 2 Step 4, via `authTypesContains`) and threaded as a plain `Boolean` into the inner composable (Task 2 Step 6/7) — consistent with this file's own established `authMethods`/`isAddingAuth` StateFlow-to-plain-param pattern from PR2.
- **Cross-task check:** Task 3's full-file replacement of `AddAuthMethodSheet.kt` does not touch its public signature, so Task 2's `AddAuthMethodSheet(...)` call site (unchanged, pre-existing in `SettingsScreen.kt`) is unaffected regardless of task execution order — Tasks 1/2 and Task 3 are independent and could theoretically run in parallel, though the plan sequences them for a cleaner linear review.
