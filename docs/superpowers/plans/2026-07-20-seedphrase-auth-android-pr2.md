# Seedphrase-auth Android Port — PR2 (Sign-In Methods + AddAuthMethodSheet) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port iOS's "Sign-In Methods" Settings section and `AddAuthSheet` to Android, shared across all 4 flavors, landing entirely in shared `main`.

**Architecture:** Three new/changed units — `AuthMethods.kt` (new, pure helper functions), `AddAuthMethodSheet.kt` (new, a `ModalBottomSheet` with a method picker), and `SettingsScreen.kt`/`SettingsViewModel.kt` (modified, new "Sign-In Methods" list section + the mutation methods backing it). `SettingsViewModel` owns the `addAuth`/`removeAuth` network calls (matching this file's existing `deleteAccount` convention); `AccountViewModel` (already injected into `SettingsScreen`) already owns `networkUser: StateFlow<NetworkUser?>` and `refreshNetworkUser()` — both reused as-is, no new plumbing.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, `com.bringyour.sdk` gomobile bindings, Mobile Wallet Adapter (`com.solana.mobilewalletadapter`).

## Global Constraints

- Direct structural port of iOS's `AddAuthSheet.swift` / `AuthMethods.swift` / `SettingsForm-iOS.swift`'s "Sign-In Methods" section — same 5 methods conceptually (Apple/Google/Wallet/Email/Seedphrase), same list+remove+add UX shape.
- **Apple is omitted entirely** from the Android method picker — no disabled/informational placeholder entry, just not present as an option.
- **Google is conditionally shown** based on `BuildConfig.BRINGYOUR_BUNDLE_SSO_GOOGLE` (`false` on `github` flavor, `true` on `play`/`solana_dapp`/`ethos_dapp`) — this is an existing per-flavor `buildConfigField`, not a new mechanism.
- **Google Sign-In code cannot live in shared `main`** — `com.google.android.gms.auth.api.signin.*` (`play-services-auth`) is only on the classpath via `playImplementation`/`solana_dappImplementation`/`ethos_dappImplementation`; `github` has no equivalent dependency line (deliberately — it's the de-Googled flavor) and would fail to compile if `main` imported those symbols unconditionally. Task 3 isolates this to a small `GoogleAddAuthButton(...)` composable that every flavor's source set implements separately (real on `google`/`solana_dapp`/`ethos_dapp`, no-op stub on `ungoogle`), matching the "flavor source set supplies the symbol" mechanism this codebase already uses for `MainActivity.kt`/`LoginInitial.kt`, just at a single-function scale instead of a whole file.
- **Wallet uses Mobile Wallet Adapter (MWA)**, not a deep-link-and-poll pattern — verified against `SolanaWalletAuth.kt`'s existing `requestAndSignSolanaChallenge(activityResultSender, api): SolanaChallengeSignResult` suspend function, already proven in the login flow. No separate Phantom/Solflare buttons; MWA's own OS-level wallet picker handles provider selection.
- **Refresh only on the success path** of every add/remove, never unconditionally after a catch (a real bug in iOS's shipped version — do not reintroduce it here).
- **Wallet-connect coroutine must be cancelled on sheet dismiss** — since Android's flow is a single suspend call (not two separate connect/sign polling stages like iOS), this is a single `Job.cancel()`, simpler than iOS's own incomplete fix.
- No local Android build toolchain — verification is CI-push-based only (`assembleGithubDebug`/`assemblePlayDebug`/`assembleSolana_dappDebug`/`assembleEthos_dappDebug`, all 4 real flavors, per PR1's Phase 0 CI fix).
- Verified SDK contract (from `github.com/Ryanmello07/urnetwork-sdk`, `beta/custom-server` branch, `api.go`/`network_user_view_controller.go` — both iOS and Android bind this same Go SDK):
  - `AddAuthArgs { userAuth: String, password: String, authJwt: String, authJwtType: String, walletAuth: WalletAuthArgs? }`, `Api.addAuth(args, callback)`, `AddAuthResult { error: AddAuthError? }`, `AddAuthError { message: String }`.
  - `RemoveAuthArgs { authType: String }`, `Api.removeAuth(args, callback)`, `RemoveAuthResult { error: RemoveAuthError? }`, `RemoveAuthError { message: String }`.
  - `WalletAuthArgs { publicKey: String, signature: String, message: String, blockchain: String }` — confirmed exact Kotlin field names via existing usage in `LoginCreateNetworkViewModel.kt:238-244`.
  - `NetworkUser { authTypes: StringList?, authType: String, userAuth: String, walletAddress: String, ... }`.
  - `com.bringyour.network.utils.sdkStringListToList: (StringList?) -> List<String>` already exists in `UrSdkUtils.kt` — reuse it, don't hand-roll another `StringList` iterator.

---

### Task 1: Create `AuthMethods.kt` (shared `main`)

**Files:**
- Create: `app/app/src/main/java/com/bringyour/network/ui/settings/AuthMethods.kt`

**Interfaces:**
- Produces: `authTypesContains(authTypes: StringList?, method: String): Boolean`, `parseAuthMethods(networkUser: NetworkUser): List<String>`, `methodDisplayName(method: String): String` — consumed by Task 3 (`SettingsViewModel.kt`) and Task 4/5 (`SettingsScreen.kt`).

- [ ] **Step 1: Write the file**

```kotlin
package com.bringyour.network.ui.settings

import com.bringyour.network.utils.sdkStringListToList
import com.bringyour.sdk.NetworkUser
import com.bringyour.sdk.StringList

fun authTypesContains(authTypes: StringList?, method: String): Boolean {
    return sdkStringListToList(authTypes).contains(method)
}

fun parseAuthMethods(networkUser: NetworkUser): List<String> {
    val fromAuthTypes = sdkStringListToList(networkUser.authTypes)
    if (fromAuthTypes.isNotEmpty()) {
        return fromAuthTypes
    }

    // Fallback for old server: read single authType + userAuth
    val methods = mutableListOf<String>()
    if (networkUser.authType.isNotEmpty()) {
        methods.add(networkUser.authType)
    }
    val userAuth = networkUser.userAuth
    if (userAuth.isNotEmpty()) {
        val methodLabel = if (userAuth.contains("@")) "email" else userAuth
        if (!methods.contains(methodLabel)) {
            methods.add(methodLabel)
        }
    }

    return methods
}

fun methodDisplayName(method: String): String {
    return when (method) {
        "email" -> "Email"
        "google" -> "Google"
        "apple" -> "Apple"
        "solana" -> "Solana Wallet"
        "seedphrase" -> "Seedphrase"
        else -> method.replaceFirstChar { it.uppercase() }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/AuthMethods.kt
git commit -m "feat: add AuthMethods helpers for multi-auth-method Settings UI"
```

---

### Task 2: `SettingsViewModel.kt` — add `addAuth`/`removeAuth` mutation methods and their state

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/settings/SettingsViewModel.kt`

**Interfaces:**
- Consumes: `com.bringyour.sdk.AddAuthArgs`, `com.bringyour.sdk.RemoveAuthArgs` (SDK types, per Global Constraints).
- Produces: `isAddingAuth: StateFlow<Boolean>`, `addAuth: (args: AddAuthArgs, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit`, `isRemovingAuth: StateFlow<Boolean>`, `removeAuth: (authType: String, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit` — both consumed by Task 5 (`SettingsScreen.kt` outer composable).

- [ ] **Step 1: Add the new imports**

Find the existing SDK imports (near the top of the file, alongside `import com.bringyour.sdk.AuthCodeCreateArgs`) and add:

```kotlin
import com.bringyour.sdk.AddAuthArgs
import com.bringyour.sdk.RemoveAuthArgs
```

- [ ] **Step 2: Add the new state and methods**

Find the `deleteAccount` property (the `val deleteAccount: (onSuccess: () -> Unit, onFailure: (Exception?) -> Unit) -> Unit = { ... }` block). Immediately after it, insert:

```kotlin
    private val _isAddingAuth = MutableStateFlow(false)
    val isAddingAuth: StateFlow<Boolean> = _isAddingAuth

    val addAuth: (
        args: AddAuthArgs,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { args, onSuccess, onError ->

        _isAddingAuth.value = true

        deviceManager.device?.api?.addAuth(args) { result, err ->
            viewModelScope.launch {
                _isAddingAuth.value = false

                if (err != null) {
                    onError(err.message ?: "Failed to add sign-in method")
                } else if (result?.error != null) {
                    onError(result.error.message ?: "Failed to add sign-in method")
                } else {
                    onSuccess()
                }
            }
        } ?: run {
            _isAddingAuth.value = false
            onError("Unable to connect. Please try again.")
        }
    }

    private val _isRemovingAuth = MutableStateFlow(false)
    val isRemovingAuth: StateFlow<Boolean> = _isRemovingAuth

    val removeAuth: (
        authType: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { authType, onSuccess, onError ->

        _isRemovingAuth.value = true

        val args = RemoveAuthArgs()
        args.authType = authType

        deviceManager.device?.api?.removeAuth(args) { result, err ->
            viewModelScope.launch {
                _isRemovingAuth.value = false

                if (err != null) {
                    onError(err.message ?: "Failed to remove sign-in method")
                } else if (result?.error != null) {
                    onError(result.error.message ?: "Failed to remove sign-in method")
                } else {
                    onSuccess()
                }
            }
        } ?: run {
            _isRemovingAuth.value = false
            onError("Unable to connect. Please try again.")
        }
    }
```

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/SettingsViewModel.kt
git commit -m "feat: add addAuth/removeAuth mutation methods to SettingsViewModel"
```

---

### Task 3: Create `AddAuthMethodSheet.kt` (shared `main`) + per-flavor `GoogleAddAuthButton.kt`

**Correction (found during implementation, before Task 3 was ever committed):** an earlier draft of this task put the Google Sign-In code (`com.google.android.gms.auth.api.signin.*` imports) directly inside `AddAuthMethodSheet.kt` in shared `main`. That cannot compile: `play-services-auth` is only on the classpath via `playImplementation`/`solana_dappImplementation`/`ethos_dappImplementation` in `app/app/build.gradle` — there is no `githubImplementation` (or plain `implementation`) line for it, and adding one would contradict `github`'s deliberate de-Googled purpose. Every existing Google-Sign-In-touching composable in this codebase (`LoginInitial.kt`) already lives per-flavor for exactly this reason.

The fix applies that same principle at minimal surface: the bulk of the sheet (Wallet/Email/Seedphrase — verified safe, since `mobile-wallet-adapter-clientlib-ktx` is a plain `implementation`, not per-flavor) stays one shared `main` file. Only the ~30-line Google-specific piece moves into a small function, `GoogleAddAuthButton(...)`, that **every flavor's source set must provide its own implementation of** — three real implementations (`play`→`src/google`, `solana_dapp`, `ethos_dapp`) and one no-op stub (`github`→`src/ungoogle`). `main`'s code calls `GoogleAddAuthButton(...)` uniformly, trusting the active flavor's source set to supply exactly one implementation — the same "flavor source set provides the symbol" mechanism Gradle already uses for `MainActivity.kt`/`LoginInitial.kt`, just applied to a single function instead of a whole file.

**Files:**
- Create: `app/app/src/main/java/com/bringyour/network/ui/settings/AddAuthMethodSheet.kt` (shared `main`, no `com.google.android.gms.*` imports)
- Create: `app/app/src/google/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt` (real impl, `play` flavor)
- Create: `app/app/src/solana_dapp/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt` (real impl)
- Create: `app/app/src/ethos_dapp/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt` (real impl)
- Create: `app/app/src/ungoogle/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt` (no-op stub, `github` flavor)

**Interfaces:**
- Consumes: `SettingsViewModel.addAuth`/`.isAddingAuth` (Task 2), `com.bringyour.network.ui.login.requestAndSignSolanaChallenge`/`SolanaChallengeSignResult` (existing, `SolanaWalletAuth.kt`), `com.bringyour.sdk.{AddAuthArgs, WalletAuthArgs}` (SDK), `com.bringyour.network.ui.components.{URButton, URTextInput}` (existing).
- Produces: `@Composable fun AddAuthMethodSheet(visible: Boolean, onDismiss: () -> Unit, showGoogleOption: Boolean, activityResultSender: ActivityResultSender?, isAddingAuth: Boolean, addAuth: (AddAuthArgs, () -> Unit, (String) -> Unit) -> Unit, onAdded: () -> Unit)` — consumed by Task 5 (`SettingsScreen.kt`).
- Internal (all 4 `GoogleAddAuthButton.kt` files must define this **exact same signature**, package `com.bringyour.network.ui.settings`, or the flavor lacking a match won't compile): `@Composable fun GoogleAddAuthButton(addAuth: (AddAuthArgs, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit, isAddingAuth: Boolean, onAdded: () -> Unit, onError: (String) -> Unit)`.

- [ ] **Step 1: Write `AddAuthMethodSheet.kt` (shared `main`, no GMS imports)**

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

private enum class AddAuthMethod { GOOGLE, WALLET, EMAIL, SEEDPHRASE }

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
            listOf(AddAuthMethod.GOOGLE, AddAuthMethod.WALLET, AddAuthMethod.EMAIL, AddAuthMethod.SEEDPHRASE)
        } else {
            listOf(AddAuthMethod.WALLET, AddAuthMethod.EMAIL, AddAuthMethod.SEEDPHRASE)
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
            AddAuthMethod.SEEDPHRASE -> {
                // Server generates and links a new seedphrase when no auth fields are set
                val args = AddAuthArgs()
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
                                    AddAuthMethod.SEEDPHRASE -> "Seedphrase"
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
                    // no-op stub on ungoogle/github). See Task 3 note above.
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
                AddAuthMethod.SEEDPHRASE -> {
                    Text(
                        "A new seedphrase will be generated and linked to your account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }

            if (addError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                URInlineErrorText(addError)
            }

            if (selectedMethod == AddAuthMethod.EMAIL || selectedMethod == AddAuthMethod.SEEDPHRASE) {
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

Note: `ButtonStyle.PRIMARY` — check `com.bringyour.network.ui.components.ButtonStyle` during implementation for the exact default/selected-state enum value name (used elsewhere as `ButtonStyle.SECONDARY` for the non-default case throughout this codebase; confirm the default/no-arg style's exact name — it may not literally be called `PRIMARY`, check the enum definition and use its real default value name if different).

- [ ] **Step 2: Commit the shared sheet**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/AddAuthMethodSheet.kt
git commit -m "feat: add AddAuthMethodSheet (Wallet/Email/Seedphrase, Google via per-flavor slot)"
```

- [ ] **Step 3: Write the real `GoogleAddAuthButton.kt` (identical content in all three real-Google flavors)**

Create this exact file at all three paths — `app/app/src/google/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt`, `app/app/src/solana_dapp/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt`, and `app/app/src/ethos_dapp/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt`:

```kotlin
package com.bringyour.network.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bringyour.network.R
import com.bringyour.network.ui.components.URButton
import com.bringyour.network.ui.theme.TextMuted
import com.bringyour.sdk.AddAuthArgs
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

@Composable
fun GoogleAddAuthButton(
    addAuth: (AddAuthArgs, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    isAddingAuth: Boolean,
    onAdded: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current

    val googleClientId = stringResource(id = R.string.google_client_id)
    val googleSignInOpts = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(googleClientId)
        .requestEmail()
        .build()
    val googleSignInClient = GoogleSignIn.getClient(context, googleSignInOpts)

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken == null) {
                onError("Could not get Google ID token")
                return@rememberLauncherForActivityResult
            }
            val args = AddAuthArgs()
            args.authJwt = idToken
            args.authJwtType = "google"
            addAuth(
                args,
                {
                    Toast.makeText(context, "Google sign-in method added", Toast.LENGTH_SHORT).show()
                    onAdded()
                },
                { msg -> onError(msg) }
            )
        } catch (e: ApiException) {
            onError("Error signing in with Google")
        }
    }

    Text(
        "Sign in with Google to add it as a sign-in method.",
        style = MaterialTheme.typography.bodyMedium,
        color = TextMuted
    )
    Spacer(modifier = Modifier.height(12.dp))
    URButton(
        onClick = { googleSignInLauncher.launch(googleSignInClient.signInIntent) },
        enabled = !isAddingAuth
    ) { buttonTextStyle ->
        Text("Sign in with Google", style = buttonTextStyle)
    }
}
```

Note: `R.string.google_client_id` already exists at `app/app/src/main/res/values/google.xml` (a shared `main` resource, distinct from the Kotlin-source per-flavor split) plus duplicate copies under `src/solana_dapp/res/values/google.xml` and `src/ethos_dapp/res/values/google.xml` — resource resolution isn't the blocker here (Gradle merges `main` + flavor resources regardless of flavor), only the `com.google.android.gms.*` Kotlin symbols are. No new resource work needed.

- [ ] **Step 4: Write the no-op `GoogleAddAuthButton.kt` stub for `github`**

Create `app/app/src/ungoogle/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt`:

```kotlin
package com.bringyour.network.ui.settings

import androidx.compose.runtime.Composable
import com.bringyour.sdk.AddAuthArgs

// github is deliberately de-Googled (no play-services-auth dependency), so
// this flavor never shows the Google option (showGoogleOption is false via
// BuildConfig.BRINGYOUR_BUNDLE_SSO_GOOGLE) and this body is unreachable in
// practice. It must still exist so AddAuthMethodSheet.kt's call to
// GoogleAddAuthButton(...) resolves for this flavor's compilation.
@Composable
fun GoogleAddAuthButton(
    addAuth: (AddAuthArgs, onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    isAddingAuth: Boolean,
    onAdded: () -> Unit,
    onError: (String) -> Unit,
) {
}
```

- [ ] **Step 5: Commit the per-flavor Google pieces**

```bash
git add app/app/src/google/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt \
        app/app/src/solana_dapp/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt \
        app/app/src/ethos_dapp/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt \
        app/app/src/ungoogle/java/com/bringyour/network/ui/settings/GoogleAddAuthButton.kt
git commit -m "feat: add per-flavor GoogleAddAuthButton (github gets a no-op stub)"
```

---

### Task 4: `SettingsScreen.kt` — add the "Sign-In Methods" section to the inner content composable

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `parseAuthMethods`, `methodDisplayName` (Task 1).
- Produces: 5 new parameters on the inner `SettingsScreen` composable (the one starting at line ~364): `authMethods: List<String>`, `onRemoveAuthMethod: (String) -> Unit`, `onAddAuthMethodClick: () -> Unit` — consumed by Task 5 (outer composable's call site).

- [ ] **Step 1: Add the 3 new parameters**

Find the inner `SettingsScreen` composable's parameter list (starts `fun SettingsScreen(\n    navController: NavController,\n    clientId: String,\n ...`, the SECOND declaration of this name in the file, distinguishing it from the outer one by its much longer parameter list). At the end of that parameter list, immediately before the closing `) {`, add:

```kotlin
    authMethods: List<String>,
    onRemoveAuthMethod: (String) -> Unit,
    onAddAuthMethodClick: () -> Unit,
```

- [ ] **Step 2: Add the "Sign-In Methods" section to the screen body**

This screen is organized into sections rendered inside its `Scaffold`'s content. Locate an existing section (e.g. search for a section header `Text` composable close to where account-related settings are shown — the exact insertion point is anywhere among the other top-level sections in the body `Column`/`LazyColumn`, since sections in this file are independent siblings). Insert a new section, matching the visual weight of neighboring sections (a header `Text`, then content):

```kotlin
                Text(
                    "Sign-In Methods",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
                )

                authMethods.forEach { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            methodDisplayName(method),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        TextButton(onClick = { onRemoveAuthMethod(method) }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                TextButton(onClick = onAddAuthMethodClick) {
                    Text("Add sign-in method")
                }
```

Adjust the exact `Modifier`/spacing values to match whatever the immediately-surrounding sections in this file use (read ~30 lines above and below your chosen insertion point before finalizing padding/spacing values, so the new section doesn't visually clash) — the values above are a reasonable default consistent with the rest of this plan's spacing conventions (8/16/24dp), not a hard requirement if the file's own established rhythm differs slightly.

Add missing imports if not already present in this file: `androidx.compose.material3.TextButton`, `com.bringyour.network.ui.settings.methodDisplayName` (same package, likely no import needed if `SettingsScreen.kt` and `AuthMethods.kt` share the `com.bringyour.network.ui.settings` package — confirm no import statement is needed for same-package symbols).

- [ ] **Step 3: Fix the `@Preview` functions**

Every `@Preview` composable that calls the inner `SettingsScreen(...)` (there are several — `SettingsScreenPreview`, `SettingsScreenSupporterPreview`, `SettingsScreenNotificationsDisabledPreview`, `SettingsScreenNotificationsAllowedPreview`, `SettingsScreenDeleteAccountDialogPreview`) needs the 3 new required parameters added to its call site. Add to each:

```kotlin
                    authMethods = listOf("email"),
                    onRemoveAuthMethod = {},
                    onAddAuthMethodClick = {},
```

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/SettingsScreen.kt
git commit -m "feat: add Sign-In Methods list section to Settings screen body"
```

---

### Task 5: `SettingsScreen.kt` — wire state, the sheet, and the remove-confirmation dialog in the outer composable

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `AddAuthMethodSheet` (Task 3), `SettingsViewModel.addAuth`/`.isAddingAuth`/`.removeAuth`/`.isRemovingAuth` (Task 2), `AccountViewModel.networkUser`/`.refreshNetworkUser` (existing, `AccountViewModel.kt`), `parseAuthMethods` (Task 1), `URDialog` (existing, `URDialog.kt`).

- [ ] **Step 1: Read `networkUser` and compute `authMethods` in the outer composable**

Find the outer `SettingsScreen` composable's state-collection block (near the top, alongside `val notificationsAllowed = settingsViewModel.permissionGranted.collectAsState().value`). Add:

```kotlin
    val networkUser = accountViewModel.networkUser.collectAsState().value
    val authMethods = networkUser?.let { parseAuthMethods(it) } ?: emptyList()
    val isAddingAuth = settingsViewModel.isAddingAuth.collectAsState().value
    val isRemovingAuth = settingsViewModel.isRemovingAuth.collectAsState().value

    var presentAddAuthSheet by remember { mutableStateOf(false) }
    var pendingRemoveMethod by remember { mutableStateOf<String?>(null) }
    var removeAuthError by remember { mutableStateOf<String?>(null) }
```

- [ ] **Step 2: Pass the 3 new params into the inner `SettingsScreen(...)` call**

Find the inner `SettingsScreen(...)` call (the one starting `SettingsScreen(\n        navController,\n        clientId = accountViewModel.clientId,\n ...`). Add, before its closing `)`:

```kotlin
        authMethods = authMethods,
        onRemoveAuthMethod = { method -> pendingRemoveMethod = method },
        onAddAuthMethodClick = { presentAddAuthSheet = true },
```

- [ ] **Step 3: Render the sheet and the remove-confirmation dialog**

Find where the existing dialogs/sheets are rendered as siblings after the inner `SettingsScreen(...)` call (`if (isPresentingRenameDevice) { URDialog(...) }`, `if (isPresentingAuthCodeDialog) { ... }`, `if (isPresentingUpdateReferralNetworkSheet) { UpdateReferralNetworkBottomSheet(...) }`). After the last of these, add:

```kotlin
    AddAuthMethodSheet(
        visible = presentAddAuthSheet,
        onDismiss = { presentAddAuthSheet = false },
        showGoogleOption = com.bringyour.network.BuildConfig.BRINGYOUR_BUNDLE_SSO_GOOGLE,
        activityResultSender = activityResultSender,
        isAddingAuth = isAddingAuth,
        addAuth = settingsViewModel.addAuth,
        onAdded = {
            presentAddAuthSheet = false
            accountViewModel.refreshNetworkUser()
        }
    )

    URDialog(
        visible = pendingRemoveMethod != null,
        onDismiss = {
            pendingRemoveMethod = null
            removeAuthError = null
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Remove ${pendingRemoveMethod?.let { methodDisplayName(it) } ?: ""}?",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "You won't be able to sign in with this method anymore.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            if (removeAuthError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                URInlineErrorText(removeAuthError)
            }
            Spacer(modifier = Modifier.height(16.dp))
            URButton(
                onClick = {
                    val method = pendingRemoveMethod ?: return@URButton
                    removeAuthError = null
                    settingsViewModel.removeAuth(
                        method,
                        {
                            pendingRemoveMethod = null
                            accountViewModel.refreshNetworkUser()
                        },
                        { msg -> removeAuthError = msg }
                    )
                },
                enabled = !isRemovingAuth,
                isProcessing = isRemovingAuth
            ) { buttonTextStyle ->
                Text("Remove", style = buttonTextStyle)
            }
        }
    }
```

Add missing imports if not already present: `com.bringyour.network.ui.components.URInlineErrorText`.

- [ ] **Step 4: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/settings/SettingsScreen.kt
git commit -m "feat: wire AddAuthMethodSheet and remove-confirmation dialog into Settings"
```

---

### Task 6: Checkpoint — push and verify all 4 flavors build clean

- [ ] **Step 1: Push**

```bash
git push -u origin HEAD
```

- [ ] **Step 2: Watch CI**

```bash
gh run watch --repo Ryanmello07/urnetwork-android $(gh run list --repo Ryanmello07/urnetwork-android --branch feat/seedphrase-auth-android-pr2 --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: all 4 `Build Android app (github|play|solana_dapp|ethos_dapp)` jobs green. Fix any failures before proceeding.

- [ ] **Step 3: Open the PR**

```bash
gh pr create --repo Ryanmello07/urnetwork-android --base beta/custom-server --head feat/seedphrase-auth-android-pr2 \
  --title "feat: Sign-In Methods list + AddAuthMethodSheet" \
  --body "PR2 of the seedphrase-auth Android port (see docs/superpowers/plans/2026-07-20-seedphrase-auth-android-pr2.md and docs/superpowers/specs/2026-07-20-seedphrase-auth-android-pr2-design.md). Adds a Settings \"Sign-In Methods\" list (view + remove) and AddAuthMethodSheet (add Google/Wallet/Email/Seedphrase), direct structural port of iOS's AddAuthSheet.swift. PR3 (Profile name-claim) is next, planned just-in-time after this merges."
```

## Self-review notes

- **Spec coverage**: `AuthMethods.kt` (Task 1), `SettingsViewModel` mutation methods (Task 2), `AddAuthMethodSheet.kt` (Task 3), Settings screen list section (Task 4) and wiring (Task 5) — all spec components covered. Apple omission and Google's `BuildConfig`-based conditional visibility both implemented in Task 3. Wallet's MWA-based (not deep-link-poll) flow implemented in Task 3, matching the corrected spec.
- **Placeholder scan**: no TBD/"similar to"/undefined-symbol references. Task 3's `ButtonStyle.PRIMARY` note and Task 4's "adjust to match surroundings" note are explicit, actionable verification instructions for the implementer, not vague placeholders — both name the exact thing to check and why.
- **Type consistency**: `addAuth: (AddAuthArgs, () -> Unit, (String) -> Unit) -> Unit` and `removeAuth: (String, () -> Unit, (String) -> Unit) -> Unit` (Task 2) match their exact call shape at every consumption site (Task 3's `AddAuthMethodSheet` parameter, Task 5's `settingsViewModel.addAuth`/`.removeAuth` references). `authMethods: List<String>` is produced once (Task 5) and consumed once (Task 4's new inner-composable parameter) with a matching type both places.
