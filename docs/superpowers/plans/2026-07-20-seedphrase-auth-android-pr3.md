# Seedphrase-auth Android Port — PR3 (Profile Name-Claim + Rename Migration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate Android's network-rename off the legacy, cooldown-free `NetworkUserUpdate` endpoint onto `changeNetworkName`/`claimNetworkName` (matching iOS), re-enable the currently-dead edit UI with iOS's actual pencil-icon + dual-view flow, and add JWT-refresh-on-save/foreground.

**Architecture:** Three units — `ProfileViewModel.kt` (rewritten write path: delete the dead `NetworkUserViewController`-based machinery, add direct `Api.changeNetworkName`/`claimNetworkName` calls plus `needsNameClaim`), `ProfileScreen.kt` (rebuilt UI: read-only display + pencil icon vs. editing form + Save/Cancel, copied from iOS's `ProfileView.swift`, not from Android's old dead code), and `MainNavViewModel.kt`/`MainNavHost.kt` (new JWT-refresh-on-app-foreground hook, matching iOS's app-root `scenePhase` hook).

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, `com.bringyour.sdk` gomobile bindings.

## Global Constraints

- Direct structural port of iOS's `ProfileView.swift`/`ProfileViewModel.swift`/`AccountNavStackView.swift` name-claim handling — same claim-vs-change UX, same JWT-refresh-on-save/foreground behavior.
- **Migrate fully off the legacy `Api.NetworkUserUpdate`/`NetworkUserViewController.UpdateNetworkUser` path onto `Api.changeNetworkName`/`Api.claimNetworkName`** — not just adding `needsNameClaim` on top of the old call. The legacy path has no cooldown and no claim/change distinction; the new path routes through the actively-maintained, cooldown-protected `controller/account_controller.go`.
- **Rebuild the edit UI from iOS's actual flow, not from Android's old commented-out dead code.** The old block (`// todo - temporarily remove edits until new API changes are made`) is a plain Text-link design with no `needsNameClaim` awareness — do not uncomment or adapt it. Use this codebase's own established components (`URTextInput`, `URButton`, `TextButton`, `URInlineErrorText`) and its own edit-icon precedent (`SettingsScreen.kt`'s device-rename row: `.clickable`-wrapped `Row` + trailing `Icons.Filled.Edit`, not a separate `IconButton`).
- **`needsNameClaim`** = true when the account has no verified identity method bound (`email`/`phone`/`google`/`apple`/`solana` all absent from `authTypes`), reusing PR2's existing `authTypesContains(authTypes: StringList?, method: String): Boolean` from `com.bringyour.network.ui.settings.AuthMethods.kt`.
- **Refresh only on the success path**, never unconditionally after a catch — the existing project-wide constraint from PR1/PR2.
- **`device.refreshToken(0)`** is proven, existing plumbing (`SubscriptionBalanceViewModel.kt:156`) — reuse the exact call shape, not new plumbing.
- **`MainNavHost.kt` has no direct `deviceManager` access** — the foreground-refresh hook must go through `MainNavViewModel` (already Hilt-injected there with `deviceManager` in its constructor), matching how the file's existing lifecycle observer already calls a ViewModel method (`subscriptionBalanceViewModel.pollSolanaTransaction()`), not raw device access.
- Verified SDK contract (from `github.com/Ryanmello07/urnetwork-sdk`, `beta/custom-server` branch):
  - `ChangeNetworkNameArgs { newName: String }`, `Api.changeNetworkName(args, callback)`, `ChangeNetworkNameResult { networkName: String, error: ChangeNetworkNameError? }`, `ChangeNetworkNameError { message: String }`.
  - `ClaimNetworkNameArgs { newName: String }`, `Api.claimNetworkName(args, callback)`, `ClaimNetworkNameResult { networkName: String, error: ClaimNetworkNameError? }`, `ClaimNetworkNameError { message: String }`.
  - `Device.refreshToken(attempt: Int)` — real, already-used method.
- No local Android build toolchain — verification is CI-push-based only (`assembleGithubDebug`/`assemblePlayDebug`/`assembleSolana_dappDebug`/`assembleEthos_dappDebug`), same as PR1/PR2. This PR touches only shared `main` files — no per-flavor split needed.
- The user wants to do a live/manual preview check of the final UI before deciding to merge — do NOT auto-merge at the end of this plan. The final checkpoint task stops once CI is green and the draft PR (#8, already open) is ready for review; wait for explicit go-ahead before invoking `superpowers:finishing-a-development-branch`.

---

### Task 1: `ProfileViewModel.kt` — rewrite the write path

**Files:**
- Modify (full-file replace): `app/app/src/main/java/com/bringyour/network/ui/profile/ProfileViewModel.kt`

**Interfaces:**
- Consumes: `com.bringyour.network.ui.settings.authTypesContains` (PR2, `AuthMethods.kt`), `com.bringyour.sdk.ChangeNetworkNameArgs`/`ClaimNetworkNameArgs` (SDK, per Global Constraints).
- Produces: `needsNameClaim: StateFlow<Boolean>`, `isSavingNetworkName: StateFlow<Boolean>`, `networkNameError: StateFlow<String?>`, `saveNetworkName: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit`, `claimNetworkName: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit` — consumed by Task 2 (`ProfileScreen.kt`). Unchanged (kept from the existing file): `isEditingProfile: Boolean`, `setIsEditingProfile: (Boolean) -> Unit`, `networkNameTextFieldValue: TextFieldValue`, `setNetworkNameTextFieldValue: (TextFieldValue) -> Unit`, `cancelEdits`, `setNetworkUser: (NetworkUser?) -> Unit`, `networkNameIsValid: Boolean`, `isValidatingNetworkName: Boolean`, `validateNetworkName: (String) -> Unit`.
- Removed (confirmed via repo-wide grep that no file outside `ui/profile/` references these — only `ProfileScreen.kt`, rewritten in Task 2, touches them): `isUpdatingProfile`, `errorUpdatingProfile`, `setErrorUpdatingProfile`, `usernameIsValid`, `updateProfile`, `updateSuccessSub`, `addUpdateErrorListener`, `addIsUpdatingListener`, `networkUserVc`.

- [ ] **Step 1: Replace the full file**

```kotlin
package com.bringyour.network.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bringyour.network.DeviceManager
import com.bringyour.network.NetworkSpaceManagerProvider
import com.bringyour.network.ui.settings.authTypesContains
import com.bringyour.sdk.ChangeNetworkNameArgs
import com.bringyour.sdk.ClaimNetworkNameArgs
import com.bringyour.sdk.NetworkNameValidationViewController
import com.bringyour.sdk.NetworkUser
import com.bringyour.sdk.Sdk
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject


@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val deviceManager: DeviceManager,
    networkSpaceManagerProvider: NetworkSpaceManagerProvider
): ViewModel() {

    private var networkNameValidationVc: NetworkNameValidationViewController? = null

    var isEditingProfile by mutableStateOf(false)
        private set

    var isValidatingNetworkName by mutableStateOf(false)
        private set

    val setIsValidatingNetworkName: (Boolean) -> Unit = { iv ->
        isValidatingNetworkName = iv
    }

    private var networkUser: NetworkUser? = null

    var networkNameTextFieldValue by mutableStateOf(TextFieldValue())
        private set

    var networkNameIsValid by mutableStateOf(true)
        private set

    val setNetworkNameIsValid: (Boolean) -> Unit = { isValid ->
        networkNameIsValid = isValid
    }

    private val _isSavingNetworkName = MutableStateFlow(false)
    val isSavingNetworkName: StateFlow<Boolean> = _isSavingNetworkName

    private val _networkNameError = MutableStateFlow<String?>(null)
    val networkNameError: StateFlow<String?> = _networkNameError

    private val _needsNameClaim = MutableStateFlow(false)
    val needsNameClaim: StateFlow<Boolean> = _needsNameClaim

    val setNetworkNameTextFieldValue: (TextFieldValue) -> Unit = {
        networkNameTextFieldValue = it
    }

    val setIsEditingProfile: (Boolean) -> Unit = {
        isEditingProfile = it
        if (it) {
            _networkNameError.value = null
        }
    }

    val validateNetworkName: (String) -> Unit = { nn ->

        if (networkUser?.networkName != nn) {
            setIsValidatingNetworkName(true)

            networkNameValidationVc?.networkCheck(nn) { result, err ->
                viewModelScope.launch {

                    if (err == null) {
                        if (result.available) {
                            setNetworkNameIsValid(true)
                        } else {
                            setNetworkNameIsValid(false)
                        }
                    } else {
                        setNetworkNameIsValid(false)
                    }

                    setIsValidatingNetworkName(false)
                }
            }
        } else {
            setNetworkNameIsValid(true)
            setIsValidatingNetworkName(false)
        }

    }

    val saveNetworkName: (
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { onSuccess, onError ->

        val name = networkNameTextFieldValue.text
        _isSavingNetworkName.value = true
        _networkNameError.value = null

        val args = ChangeNetworkNameArgs()
        args.newName = name

        deviceManager.device?.api?.changeNetworkName(args) { result, err ->
            viewModelScope.launch {
                _isSavingNetworkName.value = false

                if (err != null) {
                    val msg = err.message ?: "Failed to change network name"
                    _networkNameError.value = msg
                    onError(msg)
                } else if (result?.error != null) {
                    val msg = result.error.message ?: "Failed to change network name"
                    _networkNameError.value = msg
                    onError(msg)
                } else {
                    deviceManager.device?.refreshToken(0)
                    onSuccess()
                }
            }
        } ?: run {
            _isSavingNetworkName.value = false
            val msg = "Unable to connect. Please try again."
            _networkNameError.value = msg
            onError(msg)
        }
    }

    val claimNetworkName: (
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { onSuccess, onError ->

        val name = networkNameTextFieldValue.text
        _isSavingNetworkName.value = true
        _networkNameError.value = null

        val args = ClaimNetworkNameArgs()
        args.newName = name

        deviceManager.device?.api?.claimNetworkName(args) { result, err ->
            viewModelScope.launch {
                _isSavingNetworkName.value = false

                if (err != null) {
                    val msg = err.message ?: "Failed to claim network name"
                    _networkNameError.value = msg
                    onError(msg)
                } else if (result?.error != null) {
                    val msg = result.error.message ?: "Failed to claim network name"
                    _networkNameError.value = msg
                    onError(msg)
                } else {
                    deviceManager.device?.refreshToken(0)
                    onSuccess()
                }
            }
        } ?: run {
            _isSavingNetworkName.value = false
            val msg = "Unable to connect. Please try again."
            _networkNameError.value = msg
            onError(msg)
        }
    }

    val cancelEdits = {
        setNetworkNameTextFieldValue(TextFieldValue(networkUser?.networkName ?: ""))
        _networkNameError.value = null
        setIsEditingProfile(false)
    }

    val setNetworkUser: (NetworkUser?) -> Unit = { nu ->
        networkUser = nu
        setNetworkNameTextFieldValue(TextFieldValue(nu?.networkName ?: ""))
        _needsNameClaim.value = nu?.let {
            val hasIdentityMethod = authTypesContains(it.authTypes, "email") ||
                authTypesContains(it.authTypes, "phone") ||
                authTypesContains(it.authTypes, "google") ||
                authTypesContains(it.authTypes, "apple") ||
                authTypesContains(it.authTypes, "solana")
            !hasIdentityMethod
        } ?: false
    }

    init {

        networkNameValidationVc = Sdk.newNetworkNameValidationViewController(
            networkSpaceManagerProvider.getNetworkSpace()?.api
        )

    }

    override fun onCleared() {
        super.onCleared()

        networkNameValidationVc?.close()
    }

}
```

Note: `ChangeNetworkNameResult`/`ClaimNetworkNameResult`'s `error` field type
(`ChangeNetworkNameError`/`ClaimNetworkNameError`) is a distinct Kotlin
class per SDK method (not a shared `Error` type) — this matches the
existing `AddAuthResult`/`RemoveAuthResult` pattern from PR2 exactly
(each has its own `XError` class with a `message: String` field), so
`result?.error?.message` resolves the same way for both.

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/profile/ProfileViewModel.kt
git commit -m "feat: migrate ProfileViewModel off legacy NetworkUserUpdate onto changeNetworkName/claimNetworkName"
```

---

### Task 2: `ProfileScreen.kt` — rebuild the edit UI from iOS's flow + add the string resource

**Files:**
- Modify: `app/app/src/main/res/values/strings.xml`
- Modify (full-file replace): `app/app/src/main/java/com/bringyour/network/ui/profile/ProfileScreen.kt`

**Interfaces:**
- Consumes: `ProfileViewModel.needsNameClaim`/`.isSavingNetworkName`/`.networkNameError`/`.saveNetworkName`/`.claimNetworkName` (Task 1), `AccountViewModel.networkUser`/`.refreshNetworkUser` (existing), `URButton`/`URTextInput`/`URInlineErrorText` (existing, `ui/components/`).

- [ ] **Step 1: Add the new string resource**

Find `<string name="edit_device_name">Edit device name</string>` (around line 257) in `app/app/src/main/res/values/strings.xml`. Immediately after it, add:

```xml
    <string name="edit_network_name">Edit network name</string>
    <string name="claim_network_name_hint">Claim a custom network name to replace your auto-generated one</string>
    <string name="change_network_name_hint">Tap the edit icon to change your network name</string>
```

- [ ] **Step 2: Replace the full `ProfileScreen.kt` file**

```kotlin
package com.bringyour.network.ui.profile

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.bringyour.network.R
import com.bringyour.network.ui.account.AccountViewModel
import com.bringyour.network.ui.components.AccountSwitcher
import com.bringyour.network.ui.components.LoginMode
import com.bringyour.network.ui.components.URButton
import com.bringyour.network.ui.components.URInlineErrorText
import com.bringyour.network.ui.components.URTextInput
import com.bringyour.network.ui.components.overlays.OverlayMode
import com.bringyour.network.ui.shared.viewmodels.OverlayViewModel
import com.bringyour.network.ui.shared.viewmodels.ResetPasswordFunction
import com.bringyour.network.ui.shared.viewmodels.ResetPasswordViewModel
import com.bringyour.network.ui.theme.Black
import com.bringyour.network.ui.theme.BlueMedium
import com.bringyour.network.ui.theme.TextMuted
import com.bringyour.network.ui.theme.TopBarTitleTextStyle
import com.bringyour.network.ui.theme.URNetworkTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun ProfileScreen(
    navController: NavController,
    accountViewModel: AccountViewModel,
    profileViewModel: ProfileViewModel,
    overlayViewModel: OverlayViewModel,
    resetPasswordViewModel: ResetPasswordViewModel = hiltViewModel()
) {

    val networkUser by accountViewModel.networkUser.collectAsState()
    val isSavingNetworkName by profileViewModel.isSavingNetworkName.collectAsState()
    val networkNameError by profileViewModel.networkNameError.collectAsState()
    val needsNameClaim by profileViewModel.needsNameClaim.collectAsState()

    LaunchedEffect(networkUser) {
        profileViewModel.setNetworkUser(networkUser)
    }

    ProfileScreen(
        navController = navController,
        loginMode = accountViewModel.loginMode,
        isSendingResetPassLink = resetPasswordViewModel.isSendingResetPassLink,
        sendResetLink = resetPasswordViewModel.sendResetLink,
        networkName = networkUser?.networkName ?: "",
        networkNameTextFieldValue = profileViewModel.networkNameTextFieldValue,
        setNetworkName = profileViewModel.setNetworkNameTextFieldValue,
        userAuth = networkUser?.userAuth,
        isEditingProfile = profileViewModel.isEditingProfile,
        setIsEditingProfile = profileViewModel.setIsEditingProfile,
        cancelEdits = profileViewModel.cancelEdits,
        needsNameClaim = needsNameClaim,
        saveNetworkName = profileViewModel.saveNetworkName,
        claimNetworkName = profileViewModel.claimNetworkName,
        isSavingNetworkName = isSavingNetworkName,
        networkNameError = networkNameError,
        networkNameIsValid = profileViewModel.networkNameIsValid,
        networkNameIsValidating = profileViewModel.isValidatingNetworkName,
        validateNetworkName = profileViewModel.validateNetworkName,
        onSaved = {
            accountViewModel.refreshNetworkUser()
            profileViewModel.setIsEditingProfile(false)
        },
        launchOverlay = overlayViewModel.launch
    )

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    loginMode: LoginMode,
    isSendingResetPassLink: Boolean,
    sendResetLink: ResetPasswordFunction,
    networkName: String,
    networkNameTextFieldValue: TextFieldValue,
    setNetworkName: (TextFieldValue) -> Unit,
    userAuth: String?,
    isEditingProfile: Boolean,
    setIsEditingProfile: (Boolean) -> Unit,
    cancelEdits: () -> Unit,
    needsNameClaim: Boolean,
    saveNetworkName: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    claimNetworkName: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
    onSaved: () -> Unit,
    isSavingNetworkName: Boolean,
    networkNameError: String?,
    validateNetworkName: (String) -> Unit,
    networkNameIsValid: Boolean,
    networkNameIsValidating: Boolean,
    launchOverlay: (OverlayMode) -> Unit
) {

    val context = LocalContext.current
    var lastResetTime by remember { mutableStateOf(0L) }
    var cooldownTrigger by remember { mutableStateOf(0) }
    val cooldownPeriod = 15_000L // 15 seconds

    // disable send reset email for 15 seconds after successfully sending
    LaunchedEffect(lastResetTime) {
        if (lastResetTime > 0L) {
            delay(cooldownPeriod)
            cooldownTrigger++ // trigger recomposition
        }
    }

    val resendBtnEnabled by remember {
        derivedStateOf {
            cooldownTrigger
            userAuth != null &&
                    !isSendingResetPassLink &&
                    (System.currentTimeMillis() - lastResetTime > cooldownPeriod)
        }
    }

    var debounceJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val resetPasswordErr = stringResource(id = R.string.something_went_wrong)
    val resetPasswordEmailSentMsg = stringResource(id = R.string.reset_password_email_sent, userAuth ?: stringResource(id = R.string.unknown))

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(id = R.string.profile),
                        style = TopBarTitleTextStyle
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Black
                ),
                actions = {},
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(id = R.string.profile),
                    style = MaterialTheme.typography.headlineSmall
                )
                AccountSwitcher(
                    loginMode = loginMode,
                    // todo - this should be the current network name, not the one being edited
                    networkName = networkName,
                    launchOverlay = launchOverlay
                )
            }
            Spacer(modifier = Modifier.height(64.dp))

            if (isEditingProfile) {
                URTextInput(
                    value = networkNameTextFieldValue,
                    onValueChange = {
                        setNetworkName(it)

                        debounceJob?.cancel()
                        debounceJob = coroutineScope.launch {
                            delay(500L)
                            validateNetworkName(it.text)
                        }

                    },
                    enabled = !isSavingNetworkName,
                    label = stringResource(id = R.string.network_name_label),
                    isValidating = networkNameIsValidating,
                    isValid = networkNameIsValid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                )

                if (networkNameError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    URInlineErrorText(networkNameError)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        URButton(
                            onClick = {
                                if (needsNameClaim) {
                                    claimNetworkName(onSaved) { }
                                } else {
                                    saveNetworkName(onSaved) { }
                                }
                            },
                            enabled = !isSavingNetworkName && networkNameTextFieldValue.text.isNotBlank(),
                            isProcessing = isSavingNetworkName
                        ) { buttonTextStyle ->
                            Text(stringResource(id = R.string.save), style = buttonTextStyle)
                        }
                    }
                    TextButton(onClick = cancelEdits) {
                        Text(stringResource(id = R.string.cancel))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { setIsEditingProfile(true) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        networkName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(id = R.string.edit_network_name),
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (needsNameClaim) {
                        stringResource(id = R.string.claim_network_name_hint)
                    } else {
                        stringResource(id = R.string.change_network_name_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            URTextInput(
                value = TextFieldValue(""),
                onValueChange = {},
                enabled = false,
                label = stringResource(id = R.string.password_label),
                isPassword = true,
            )

            if (userAuth != null) {

                Text(
                    stringResource(id = R.string.change_password),
                    modifier = Modifier
                        .clickable(enabled = resendBtnEnabled) {
                            sendResetLink(
                                userAuth,
                                {
                                    lastResetTime = System.currentTimeMillis()
                                    Toast.makeText(
                                        context,
                                        resetPasswordEmailSentMsg,
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                {
                                    Toast.makeText(
                                        context,
                                        resetPasswordErr,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    ,
                    style = TextStyle(
                        color = if (resendBtnEnabled) BlueMedium else TextMuted
                    )
                )

            }
        }
    }
}

@Preview
@Composable
fun ProfileScreenPreview() {
    val navController = rememberNavController()
    URNetworkTheme {
        ProfileScreen(
            navController,
            loginMode = LoginMode.Authenticated,
            isSendingResetPassLink = false,
            sendResetLink = {_, _, _ ->},
            userAuth = "hello@bringyour.com",
            networkName = "my_network",
            networkNameTextFieldValue = TextFieldValue("my_network"),
            setNetworkName = {},
            isEditingProfile = false,
            setIsEditingProfile = {},
            cancelEdits = {},
            needsNameClaim = false,
            saveNetworkName = { _, _ -> },
            claimNetworkName = { _, _ -> },
            onSaved = {},
            isSavingNetworkName = false,
            networkNameError = null,
            networkNameIsValid = true,
            networkNameIsValidating = false,
            validateNetworkName = {},
            launchOverlay = {}
        )
    }
}

@Preview
@Composable
fun ProfileScreenEditingPreview() {
    val navController = rememberNavController()
    URNetworkTheme {
        ProfileScreen(
            navController,
            loginMode = LoginMode.Authenticated,
            isSendingResetPassLink = false,
            sendResetLink = {_, _, _ ->},
            userAuth = "hello@bringyour.com",
            networkName = "my_network",
            networkNameTextFieldValue = TextFieldValue("my_network"),
            setNetworkName = {},
            isEditingProfile = true,
            setIsEditingProfile = {},
            cancelEdits = {},
            needsNameClaim = false,
            saveNetworkName = { _, _ -> },
            claimNetworkName = { _, _ -> },
            onSaved = {},
            isSavingNetworkName = false,
            networkNameError = null,
            networkNameIsValid = false,
            networkNameIsValidating = false,
            validateNetworkName = {},
            launchOverlay = {}
        )
    }
}

@Preview
@Composable
fun ProfileScreenClaimingPreview() {
    val navController = rememberNavController()
    URNetworkTheme {
        ProfileScreen(
            navController,
            loginMode = LoginMode.Authenticated,
            isSendingResetPassLink = false,
            sendResetLink = {_, _, _ ->},
            userAuth = null,
            networkName = "auto_generated_name_123",
            networkNameTextFieldValue = TextFieldValue("auto_generated_name_123"),
            setNetworkName = {},
            isEditingProfile = false,
            setIsEditingProfile = {},
            cancelEdits = {},
            needsNameClaim = true,
            saveNetworkName = { _, _ -> },
            claimNetworkName = { _, _ -> },
            onSaved = {},
            isSavingNetworkName = false,
            networkNameError = null,
            networkNameIsValid = true,
            networkNameIsValidating = false,
            validateNetworkName = {},
            launchOverlay = {}
        )
    }
}

@Preview
@Composable
fun ProfileScreenErrorPreview() {
    val navController = rememberNavController()
    URNetworkTheme {
        ProfileScreen(
            navController,
            loginMode = LoginMode.Authenticated,
            isSendingResetPassLink = false,
            sendResetLink = {_, _, _ ->},
            userAuth = "hello@bringyour.com",
            networkName = "my_network",
            networkNameTextFieldValue = TextFieldValue("my_network"),
            setNetworkName = {},
            isEditingProfile = true,
            setIsEditingProfile = {},
            cancelEdits = {},
            needsNameClaim = false,
            saveNetworkName = { _, _ -> },
            claimNetworkName = { _, _ -> },
            onSaved = {},
            isSavingNetworkName = false,
            networkNameError = "That name is already taken",
            networkNameIsValid = true,
            networkNameIsValidating = false,
            validateNetworkName = {},
            launchOverlay = {}
        )
    }
}
```

Note on removed preview: `ProfileScreenErrorUpdatingPreview` (which
exercised the old `errorUpdatingProfile` boolean + snackbar) is replaced
by `ProfileScreenErrorPreview` above (exercises the new
`networkNameError` inline-text path) and `ProfileScreenClaimingPreview`
(new — exercises the `needsNameClaim = true` read-only-mode caption,
which had no preview coverage before since the concept didn't exist).

Note on `R.string.save`/`R.string.cancel`/`R.string.claim_network_name_hint`/
`R.string.change_network_name_hint`: `save` (line 102) and `cancel` (line
195) already exist in `strings.xml` — confirmed directly, no new entries
needed for those two. `claim_network_name_hint`/`change_network_name_hint`
are new; add them in Step 1 alongside `edit_network_name`, with copy
matching iOS exactly:

```xml
    <string name="claim_network_name_hint">Claim a custom network name to replace your auto-generated one</string>
    <string name="change_network_name_hint">Tap the edit icon to change your network name</string>
```

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/res/values/strings.xml app/app/src/main/java/com/bringyour/network/ui/profile/ProfileScreen.kt
git commit -m "feat: rebuild Profile name-edit UI from iOS's pencil-icon flow, wire claim-vs-change"
```

---

### Task 3: JWT-refresh-on-app-foreground hook

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/MainNavViewModel.kt`
- Modify: `app/app/src/main/java/com/bringyour/network/ui/MainNavHost.kt`

**Interfaces:**
- Produces: `MainNavViewModel.refreshTokenOnForeground: () -> Unit` — consumed by `MainNavHost.kt`'s new lifecycle observer (same file, added in this task).

- [ ] **Step 1: Add the method to `MainNavViewModel`**

Find the `setIntroFunnelLastPrompted` property (`val setIntroFunnelLastPrompted: () -> Unit = { deviceManager.canPromptIntroFunnel = false }`). Immediately after it, add:

```kotlin
    val refreshTokenOnForeground: () -> Unit = {
        deviceManager.device?.refreshToken(0)
    }
```

- [ ] **Step 2: Add the lifecycle observer to `MainNavHost.kt`**

Find the existing `DisposableEffect(lifecycleOwner, pendingSolanaSubReference) { ... }` block (around line 356, the Solana pending-subscription poll). Immediately after that whole block closes, add a sibling block:

```kotlin
    DisposableEffect(lifecycleOwner) {

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainNavViewModel.refreshTokenOnForeground()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
```

`lifecycleOwner` is already in scope (the existing block above uses it),
and `mainNavViewModel` is already a parameter of this composable — no new
imports needed (`DisposableEffect`, `Lifecycle`, `LifecycleEventObserver`
are all already imported in this file for the existing block).

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/MainNavViewModel.kt app/app/src/main/java/com/bringyour/network/ui/MainNavHost.kt
git commit -m "feat: refresh JWT on app foreground (matches iOS scenePhase hook)"
```

---

### Task 4: Checkpoint — push and verify all 4 flavors build clean

**Do not merge or invoke `superpowers:finishing-a-development-branch` after this task.** The user wants to do a live/manual preview check of the built APK first — stop after CI is green and report the draft PR (#8) is ready, then wait for explicit go-ahead.

- [ ] **Step 1: Push**

```bash
git push
```

- [ ] **Step 2: Watch CI**

```bash
gh run watch --repo Ryanmello07/urnetwork-android $(gh run list --repo Ryanmello07/urnetwork-android --branch feat/seedphrase-auth-android-pr3 --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: all 4 `Build Android app (github|play|solana_dapp|ethos_dapp)` jobs green. Fix any failures before proceeding.

- [ ] **Step 3: Report readiness**

Report to the user that PR #8 (already open as draft) is CI-green and ready for their live preview check, with a pointer to the CI run's uploaded APK artifacts per flavor. Do not mark the PR ready-for-review or merge until the user confirms after their manual check.

## Self-review notes

- **Spec coverage:** `ProfileViewModel.kt` migration (Task 1) covers the
  Verified SDK Contract + Global Constraints' cooldown-migration
  requirement. `ProfileScreen.kt` rebuild (Task 2) covers the
  iOS-flow-not-dead-code requirement and the `needsNameClaim` UX. Task 3
  covers the foreground-refresh requirement. All three of the spec's
  "Components" sections have a matching task.
- **Placeholder scan:** no TBD/TODO left in code steps; the one open
  item (confirming `save`/`cancel` string keys already exist) is a
  verify-before-assume note with a concrete fallback action (add them),
  not an unresolved gap.
- **Type consistency:** `saveNetworkName`/`claimNetworkName`'s signature
  `(onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit` is
  identical between Task 1 (ViewModel, where it's defined) and Task 2
  (Screen, where it's consumed and called as `saveNetworkName(onSaved) {
  }`). `needsNameClaim: StateFlow<Boolean>` (Task 1) →
  `needsNameClaim: Boolean` (Task 2's inner composable, after
  `collectAsState()` unwraps it in the outer composable) — consistent
  with PR2's own `authMethods`/`isAddingAuth` StateFlow-to-plain-param
  pattern.
