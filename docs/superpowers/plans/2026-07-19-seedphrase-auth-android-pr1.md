# Seedphrase-auth Android Port — PR1 (CI fix + guest-mode removal + seedphrase login/signup) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `Ryanmello07/urnetwork-android#2` with a clean, working port of iOS's seedphrase-login and instant-account-creation flow, and remove the legacy guest-mode entry point it replaces, across all 4 login-capable build flavors (`google`, `ungoogle`, `ethos_dapp`, `solana_dapp`). Land this as a fresh branch off `beta/custom-server`, opened as a new PR replacing #2.

**Architecture:** Three brand-new screens (`LoginSeedphrase`, `CreateNetworkInstant`, `SeedphraseDisplayScreen`) live once in the shared `main` source set and are wired into `LoginNavHost.kt`. Two new buttons ("Sign in with Seedphrase", "Create Instant Account") are added directly to each flavor's `LoginInitial.kt` — this is the one part of the feature that is genuinely per-flavor, because `LoginInitial.kt`/`LoginViewModel.kt`/`LoginActivity.kt` already exist as 4 separate full copies pre-feature (see `docs/superpowers/specs/2026-07-19-seedphrase-auth-android-port-design.md`, "Key architectural finding"). The old guest-mode entry point (a text link that created a nameless "guest" account) is deleted from all 4 flavors and from shared `main`, matching iOS's removal of the same concept.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, `com.bringyour.sdk` gomobile bindings (`Api.authLogin`, `Api.networkCreate`), Gradle product flavors.

**Reference material already extracted into `/tmp/claude-0/-tmp-sandbox-urnetwork-ios/62715240-c9df-4e6a-bb7f-4becf8b234ed/scratchpad/extract/`** (from `git show origin/beta/custom-server:<path>` and `git diff origin/beta/custom-server...origin/feat/seedphrase-auth`) if a worker needs to cross-check current file content beyond what's quoted in this plan: `google_LoginInitial.kt`, `ungoogle_LoginInitial.kt`, `ethos_dapp_LoginInitial.kt`, `solana_dapp_LoginInitial.kt`, `*_LoginViewModel.kt`, `*_LoginActivity.kt`, `GUEST_*.kt`, `DIFF_*_LoginInitial.kt.diff`. These are scratch files, not part of the repo.

## Global Constraints

- Work in a fresh branch cut from `origin/beta/custom-server` (per the approved design spec, replacing PR #2 entirely — do not reuse or rebase PR #2's branch).
- No local Android build toolchain is available (`./gradlew tasks` fails on a missing `warpctl` binary / SDK sibling checkout). Every checkpoint task in this plan is "push and watch CI" — there is no local `pytest`-equivalent step.
- **CI green only means what Phase 0 makes it mean.** Before Phase 0 lands, CI only builds a stub `github` flavor that shares no code with `google`/`ungoogle`/`ethos_dapp`/`solana_dapp` — this is exactly how a literal duplicated `) {` syntax error shipped undetected in PR #2. Do not treat any pre-Phase-0 CI run as meaningful for this feature.
- Seedphrase word-count validation must require **exactly 12 or 24 words**, not merely "at least 12" — PR #2's `LoginSeedphrase.kt` under-validated this (`words.size < 12`, which silently accepts 13, 20, 23, etc.). Fix this at the point of writing, don't reintroduce it.
- Do not reintroduce PR #2's `google`-flavor bugs: a literal duplicated `) {` in `LoginInitial`'s parameter list, and an `onSeedphraseLogin` function parameter that is immediately shadowed by a same-named local `val` a few lines later (making the parameter's value unreachable). Every flavor's `onSeedphraseLogin` (and the new `onInstantAccountCreate`) must be a plain parameter wired once at the call site, never re-declared inside the callee.
- New buttons use the codebase's existing `URButton(style = ButtonStyle.SECONDARY, ...)` convention (matching the adjacent Google/Bittensor/Solana/Auth-code buttons in the same screen), not iOS's white-capsule styling — this matches the established Android visual language for this button list.
- Use Material icons (`Icons.Filled.Key`, `Icons.Filled.Bolt`) for the two new buttons, not new drawable assets — `material-icons-extended:1.7.8` is already a dependency (`app/app/build.gradle:512`) and already used elsewhere in the app (e.g. `MainNavHost.kt`), so no new binary assets are needed.
- Out of scope for this plan (deferred, not silently dropped): removing the now-unused `commitment_issues` / `try_guest_mode` / `in_guest_mode` / `start_earning_join` string resources from `app/app/src/main/res/values/strings.xml` and its 18 locale variants, and deleting the top-level (non-`app/`) `res/res*/overlay_guest_*.png` staging images. Neither affects whether the app builds or runs correctly; both are pure resource-hygiene cleanup that would roughly double this plan's file count for no functional benefit.
- `ConnectScreen.kt` / `ConnectStatusIndicator.kt`'s `guestMode: Boolean` parameter (driven by `loginMode == LoginMode.Guest`) is untouched by this plan, matching PR #2's own scope — `LoginMode.Guest` still exists as a defensive fallback state (see Task 21), it just becomes unreachable via any user-initiated action after this PR.

---

## Phase 0 — Fix CI to actually build the real flavors

### Task 1: Extend `beta-build.yml` to build all 4 real flavors, not just the `github` stub

**Files:**
- Modify: `.github/workflows/beta-build.yml`

**Interfaces:** None (CI config only).

- [ ] **Step 1: Replace the single-flavor build/upload/release steps with a 4-flavor matrix**

Open `.github/workflows/beta-build.yml`. Replace the `build-android` job's `Build githubDebug APK`, `Upload APK as artifact`, and `Create prerelease` steps (currently lines 88–113) with a matrix that builds `google`, `ungoogle`, `ethos_dapp`, and `solana_dapp` instead of `github`. Full replacement for that job:

```yaml
name: Beta Build — Android

on:
  push:
    branches:
      - beta/custom-server
  pull_request:
    branches:
      - beta/custom-server
  workflow_dispatch:

permissions:
  contents: write

env:
  WARP_VERSION: beta-${{ github.run_number }}

jobs:
  build-android:
    name: Build Android app (${{ matrix.flavor }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        flavor: [google, ungoogle, ethos_dapp, solana_dapp]
    steps:
      - name: Check out Android repo
        uses: actions/checkout@v4

      - name: Check out forked SDK
        uses: actions/checkout@v4
        with:
          repository: Ryanmello07/urnetwork-sdk
          ref: beta/custom-server
          path: urnetwork-sdk

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - name: Generate debug keystore
        run: |
          mkdir -p "$HOME/.android"
          keytool -genkeypair -v \
            -keystore "$HOME/.android/debug.keystore" \
            -storepass android -keypass android \
            -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Android Debug,O=Android,C=US"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Set up Android NDK
        uses: nttld/setup-ndk@v1
        with:
          ndk-version: r27c
          add-to-path: true
          link-to-sdk: true

      - name: Set up Go
        uses: actions/setup-go@v5
        with:
          go-version: stable

      - name: Check out connect dependency
        run: git clone --depth 1 --branch beta/custom-server https://github.com/Ryanmello07/connect.git connect

      - name: Check out glog dependency
        run: git clone --depth 1 https://github.com/urnetwork/glog.git glog

      - name: Build SDK for Android
        working-directory: urnetwork-sdk/build
        env:
          BRINGYOUR_HOME: ${{ github.workspace }}/urnetwork-sdk
          WARP_HOME: ${{ github.workspace }}/urnetwork-sdk
          WARP_VERSION_HOME: ${{ github.workspace }}/urnetwork-sdk/version
          ANDROID_NDK_HOME: ${{ env.ANDROID_NDK_HOME }}
        run: |
          export PATH="$PATH:/usr/local/go/bin:$HOME/go/bin"
          make init build_android

      - name: Write local.properties to bypass warpctl
        working-directory: app
        run: |
          cat > local.properties <<EOF
          warp.version=${{ env.WARP_VERSION }}
          warp.version_code=${{ github.run_number }}
          EOF

      - name: Build ${{ matrix.flavor }}Debug APK
        working-directory: app
        env:
          BRINGYOUR_HOME: ${{ github.workspace }}/urnetwork-sdk
        run: |
          flavor="${{ matrix.flavor }}"
          task="assemble$(echo "${flavor:0:1}" | tr '[:lower:]' '[:upper:]')${flavor:1}Debug"
          # camelCase flavor names with underscores (ethos_dapp -> ethosDapp) need
          # their own capitalization per segment, not just the first letter.
          camelFlavor=$(echo "$flavor" | awk -F'_' '{for(i=1;i<=NF;i++){ $i=(i==1?$i:toupper(substr($i,1,1)) substr($i,2)) } print}' OFS='')
          ./gradlew ":app:assemble$(echo "${camelFlavor:0:1}" | tr '[:lower:]' '[:upper:]')${camelFlavor:1}Debug"

      - name: Upload APK as artifact
        uses: actions/upload-artifact@v4
        with:
          name: urnetwork-android-${{ matrix.flavor }}-apk
          path: app/app/build/outputs/apk/${{ matrix.flavor }}/debug/*.apk
          if-no-files-found: error

      - name: Create prerelease
        if: github.event_name == 'push'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: beta-custom-server-${{ matrix.flavor }}-${{ github.run_number }}-${{ github.run_attempt }}
        run: |
          gh release delete "$TAG" --repo "$GITHUB_REPOSITORY" --yes || true
          gh release create "$TAG" \
            --repo "$GITHUB_REPOSITORY" \
            --prerelease \
            --title "URnetwork Android beta custom server (${{ matrix.flavor }}) ${{ github.run_number }}" \
            --generate-notes \
            app/app/build/outputs/apk/${{ matrix.flavor }}/debug/*.apk
```

Note on the Gradle task name derivation: `ethos_dapp` must become `EthosDapp` (each underscore-separated segment capitalized, underscores dropped), not `Ethos_dapp`. The `camelFlavor` awk line handles this generically for all 4 matrix values; the simpler first `task=` line above it is dead/superseded — remove it, only the `camelFlavor`-based `./gradlew` invocation should remain. Corrected step body:

```yaml
      - name: Build ${{ matrix.flavor }}Debug APK
        working-directory: app
        env:
          BRINGYOUR_HOME: ${{ github.workspace }}/urnetwork-sdk
        run: |
          flavor="${{ matrix.flavor }}"
          camelFlavor=$(echo "$flavor" | awk -F'_' '{for(i=1;i<=NF;i++){ $i=toupper(substr($i,1,1)) substr($i,2) } print}' OFS='')
          ./gradlew ":app:assemble${camelFlavor}Debug"
```

(This produces `assembleGoogleDebug`, `assembleUngoogleDebug`, `assembleEthosDappDebug`, `assembleSolanaDappDebug` for the 4 matrix values.)

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/beta-build.yml
git commit -m "ci: build all 4 real login flavors instead of the github stub"
```

- [ ] **Step 3: Push and verify**

```bash
git push -u origin HEAD
gh run watch --repo Ryanmello07/urnetwork-android $(gh run list --repo Ryanmello07/urnetwork-android --branch <branch-name> --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: 4 parallel `Build Android app (google|ungoogle|ethos_dapp|solana_dapp)` jobs, all green, using the *unmodified* current codebase (this step only proves the CI change itself works before any feature code is touched).

This branch/PR (Phase 0) merges into `beta/custom-server` before Task 2 begins, so every subsequent push in this plan gets real 4-flavor CI feedback.

---

## Phase 1 — PR1: seedphrase login + instant account creation + guest-mode removal

Branch from `beta/custom-server` (now including Phase 0's CI fix). All work below happens on this one branch.

### Task 2: Create `LoginSeedphrase.kt` (shared `main`)

**Files:**
- Create: `app/app/src/main/java/com/bringyour/network/ui/login/LoginSeedphrase.kt`

**Interfaces:**
- Consumes: `com.bringyour.network.ui.login.LoginViewModel.loginWithSeedphrase(ctx, api, seedphrase, onSuccess, onError)` and `.seedphraseAuthInProgress: Boolean` (added per-flavor in Tasks 5, 9, 12, 15).
- Produces: `@Composable fun LoginSeedphrase(onLoginSuccess: (String) -> Unit, onBack: () -> Unit, loginViewModel: LoginViewModel = hiltViewModel())`, consumed by Task 6 (`LoginNavHost.kt`).

- [ ] **Step 1: Write the file**

```kotlin
package com.bringyour.network.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bringyour.network.MainApplication
import com.bringyour.network.ui.components.URButton
import com.bringyour.network.ui.components.URInlineErrorText
import com.bringyour.network.ui.theme.Black
import com.bringyour.network.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSeedphrase(
    onLoginSuccess: (String) -> Unit,
    onBack: () -> Unit,
    loginViewModel: LoginViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val application = context.applicationContext as? MainApplication
    var seedphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Black
                ),
                actions = {}
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "Sign in with Seedphrase",
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier.widthIn(max = 512.dp)
            ) {
                OutlinedTextField(
                    value = seedphrase,
                    onValueChange = {
                        seedphrase = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                    placeholder = {
                        Text(
                            "Paste your 12 or 24-word seedphrase here",
                            color = TextMuted
                        )
                    },
                    label = { Text("Seedphrase") }
                )

                Spacer(modifier = Modifier.height(16.dp))

                URButton(
                    onClick = {
                        val trimmed = seedphrase.trim()
                        if (trimmed.isEmpty()) {
                            error = "Please enter a seedphrase"
                            return@URButton
                        }
                        val normalized = trimmed.lowercase()
                            .replace(Regex("\\s+"), " ")
                        val words = normalized.split(" ")
                        if (words.size != 12 && words.size != 24) {
                            error = "Seedphrase must be 12 or 24 words"
                            return@URButton
                        }
                        loginViewModel.loginWithSeedphrase(
                            ctx = context,
                            api = application?.api,
                            seedphrase = normalized,
                            onSuccess = { jwt ->
                                onLoginSuccess(jwt)
                            },
                            onError = { msg ->
                                error = msg
                            }
                        )
                    },
                    enabled = seedphrase.isNotBlank(),
                    isProcessing = loginViewModel.seedphraseAuthInProgress
                ) { buttonTextStyle ->
                    Text("Sign In", style = buttonTextStyle)
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    URInlineErrorText(error)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/login/LoginSeedphrase.kt
git commit -m "feat: add seedphrase-login screen"
```

### Task 3: Create `CreateNetworkInstant.kt` (shared `main`)

**Files:**
- Create: `app/app/src/main/java/com/bringyour/network/ui/login/CreateNetworkInstant.kt`

**Interfaces:**
- Consumes: `com.bringyour.sdk.NetworkCreateArgs` (SDK type, already used elsewhere), `MainApplication.api: Api?`.
- Produces: `@Composable fun CreateNetworkInstant(onSeedphraseCreated: (seedphrase: String, jwt: String) -> Unit, onBack: () -> Unit)`, consumed by Task 6 (`LoginNavHost.kt`).

- [ ] **Step 1: Write the file**

```kotlin
package com.bringyour.network.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bringyour.network.MainApplication
import com.bringyour.network.ui.components.TermsCheckbox
import com.bringyour.network.ui.components.URButton
import com.bringyour.network.ui.components.URInlineErrorText
import com.bringyour.network.ui.theme.Black
import com.bringyour.sdk.NetworkCreateArgs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateNetworkInstant(
    onSeedphraseCreated: (seedphrase: String, jwt: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as? MainApplication
    val scope = rememberCoroutineScope()

    var termsAgreed by remember { mutableStateOf(false) }
    var inProgress by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Black
                ),
                actions = {}
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 512.dp)
            ) {
                Text(
                    "Create Instant Account",
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "No email or password needed. Your account is secured by a seedphrase.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(32.dp))

                TermsCheckbox(
                    checked = termsAgreed,
                    onCheckChanged = { termsAgreed = it },
                    enabled = !inProgress
                )

                Spacer(modifier = Modifier.height(16.dp))

                URButton(
                    onClick = {
                        if (inProgress) return@URButton
                        inProgress = true
                        error = null

                        val api = application?.api ?: run {
                            inProgress = false
                            error = "Unable to connect. Please try again."
                            return@URButton
                        }

                        val args = NetworkCreateArgs()
                        args.terms = termsAgreed
                        // No userAuth, userName, password, walletAuth — triggers seedphrase path

                        api.networkCreate(args) { result, err ->
                            scope.launch {
                                if (err != null) {
                                    error = err.message ?: "Unable to connect. Please try again."
                                    inProgress = false
                                } else if (result.error != null) {
                                    error = result.error.message ?: "Failed to create account"
                                    inProgress = false
                                } else if (result.seedphrase != null && result.network?.byJwt != null) {
                                    error = null
                                    inProgress = false
                                    onSeedphraseCreated(
                                        result.seedphrase,
                                        result.network.byJwt
                                    )
                                } else {
                                    error = "Failed to create account"
                                    inProgress = false
                                }
                            }
                        }
                    },
                    enabled = termsAgreed && !inProgress,
                    isProcessing = inProgress
                ) { buttonTextStyle ->
                    Text("Create Account", style = buttonTextStyle)
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    URInlineErrorText(error)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/login/CreateNetworkInstant.kt
git commit -m "feat: add instant-account-creation screen"
```

### Task 4: Create `SeedphraseDisplayScreen.kt` (shared `main`)

**Files:**
- Create: `app/app/src/main/java/com/bringyour/network/ui/login/SeedphraseDisplayScreen.kt`

**Interfaces:**
- Produces: `@Composable fun SeedphraseDisplayScreen(seedphrase: String, onConfirmed: () -> Unit, onBack: () -> Unit)`, consumed by Task 6 (`LoginNavHost.kt`) right after `CreateNetworkInstant` succeeds.

- [ ] **Step 1: Write the file**

```kotlin
package com.bringyour.network.ui.login

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bringyour.network.ui.components.URButton
import com.bringyour.network.ui.theme.Black
import com.bringyour.network.ui.theme.TextMuted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedphraseDisplayScreen(
    seedphrase: String,
    onConfirmed: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showBackConfirmDialog by remember { mutableStateOf(false) }

    BackHandler {
        showBackConfirmDialog = true
    }

    if (showBackConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBackConfirmDialog = false },
            title = { Text("Save Your Seedphrase") },
            text = { Text("Are you sure? This is the ONLY time you'll see your seedphrase. Make sure you've saved it before going back.") },
            confirmButton = {
                TextButton(onClick = {
                    showBackConfirmDialog = false
                    onBack()
                }) {
                    Text("Go Back")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackConfirmDialog = false }) {
                    Text("Stay")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = {
                        showBackConfirmDialog = true
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Black
                ),
                actions = {}
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 512.dp)
            ) {
                Text(
                    "Your Account Seedphrase",
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "⚠️ This is the ONLY time you'll see this seedphrase. Save it somewhere safe — you'll need it to sign in.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = TextMuted,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(32.dp))

                SelectionContainer {
                    Text(
                        text = seedphrase,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                URButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Seedphrase", seedphrase)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Seedphrase copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) { buttonTextStyle ->
                    Text("Copy to Clipboard", style = buttonTextStyle)
                }

                Spacer(modifier = Modifier.height(24.dp))

                URButton(
                    onClick = onConfirmed
                ) { buttonTextStyle ->
                    Text("I've Saved My Seedphrase", style = buttonTextStyle)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/login/SeedphraseDisplayScreen.kt
git commit -m "feat: add seedphrase-display confirmation screen"
```

### Task 5: Wire the 3 new screens into `LoginNavHost.kt` (shared `main`)

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/LoginNavHost.kt`

**Interfaces:**
- Consumes: `LoginSeedphrase` (Task 2), `CreateNetworkInstant` (Task 3), `SeedphraseDisplayScreen` (Task 4), the existing `handleLoginFlow` helper already in this file's package (used by other login composables — confirm it's already imported/available; if `handleLoginFlow` is not found by that exact name, grep `app/app/src/main/java/com/bringyour/network/ui/login/` for its definition before proceeding, it is a pre-existing shared helper, not something this plan creates).
- Produces: two new navigation routes, `"login_seedphrase"` and `"create-network-instant"`, consumed by Tasks 8–19 (each flavor's `LoginInitial.kt` navigates to these route strings).

- [ ] **Step 1: Add new imports**

In the existing import block (top of file, alongside `import com.bringyour.network.ui.login.LoginCreateNetwork` etc.), add:

```kotlin
import com.bringyour.network.ui.login.CreateNetworkInstant
import com.bringyour.network.ui.login.LoginSeedphrase
import com.bringyour.network.ui.login.SeedphraseDisplayScreen
import com.bringyour.network.ui.login.handleLoginFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
```

(If any of `LocalContext`, `rememberCoroutineScope`, `launch` are already imported in this file — check first — do not duplicate the import line; Kotlin will fail to compile on a duplicate import.)

- [ ] **Step 2: Add the two new `composable(...)` blocks**

Inside the `NavHost { ... }` block, immediately after the existing `composable("reset-password-after-send/{userAuth}") { ... }` block and before the closing `}` of the `NavHost` content lambda, insert:

```kotlin
                    composable("login_seedphrase") {
                        val context = LocalContext.current
                        val application = context.applicationContext as? com.bringyour.network.MainApplication
                        val loginActivity = context as? com.bringyour.network.LoginActivity
                        val coroutineScope = rememberCoroutineScope()

                        LoginSeedphrase(
                            onLoginSuccess = { jwt ->
                                coroutineScope.launch {
                                    handleLoginFlow(
                                        networkJwt = jwt,
                                        scope = coroutineScope,
                                        appLogin = { application?.login(jwt) },
                                        onContentVisibilityChange = {},
                                        onErr = {
                                            android.widget.Toast.makeText(context, "Error logging in, please try again.", android.widget.Toast.LENGTH_LONG).show()
                                        },
                                        onWelcomeOverlayVisibilityChange = {},
                                        authClientAndFinish = { cb ->
                                            loginActivity?.authClientAndFinish(cb)
                                        }
                                    )
                                }
                            },
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable("create-network-instant") {
                        val context = LocalContext.current
                        val application = context.applicationContext as? com.bringyour.network.MainApplication
                        val loginActivity = context as? com.bringyour.network.LoginActivity
                        val coroutineScope = rememberCoroutineScope()

                        var seedphraseResult by remember { mutableStateOf<Pair<String, String>?>(null) }

                        CreateNetworkInstant(
                            onSeedphraseCreated = { seedphrase, jwt ->
                                seedphraseResult = Pair(seedphrase, jwt)
                            },
                            onBack = {
                                navController.popBackStack()
                            }
                        )

                        seedphraseResult?.let { (sp, jwt) ->
                            SeedphraseDisplayScreen(
                                seedphrase = sp,
                                onConfirmed = {
                                    seedphraseResult = null
                                    application?.login(jwt)
                                    coroutineScope.launch {
                                        loginActivity?.authClientAndFinish { error ->
                                            if (error != null) {
                                                android.util.Log.e("LoginNavHost", "auth client finish err: $error")
                                            }
                                        }
                                    }
                                },
                                onBack = {
                                    seedphraseResult = null
                                }
                            )
                        }
                    }
```

`mutableStateOf` and `remember` used here are already imported in this file (used elsewhere for `switchAccount`/`promptAccountSwitch`) — verify, don't duplicate.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/LoginNavHost.kt
git commit -m "feat: wire seedphrase-login and instant-account routes into LoginNavHost"
```

### Task 6: `google` flavor — `LoginViewModel.kt`

**Files:**
- Modify: `app/app/src/google/java/com/bringyour/network/ui/login/LoginViewModel.kt`

**Interfaces:**
- Produces: `loginWithSeedphrase(ctx: Context, api: Api?, seedphrase: String, onSuccess: (String) -> Unit, onError: (String) -> Unit)`, `seedphraseAuthInProgress: Boolean` — consumed by `LoginSeedphrase.kt` (Task 2, shared across all flavors via each flavor's own `LoginViewModel` class of the same name/shape) and by this flavor's own `LoginInitial.kt` (Task 8).

- [ ] **Step 1: Rename the guest-mode-in-progress flag to a seedphrase one**

Find (near the top of the class, alongside other `mutableStateOf` flags):

```kotlin
    var createGuestModeInProgress by mutableStateOf(false)
        private set

    val setCreateGuestModeInProgress: (Boolean) -> Unit = { inProgress ->
        createGuestModeInProgress = inProgress
    }
```

Replace with:

```kotlin
    var seedphraseAuthInProgress by mutableStateOf(false)
        private set

    val setSeedphraseAuthInProgress: (Boolean) -> Unit = { inProgress ->
        seedphraseAuthInProgress = inProgress
    }
```

- [ ] **Step 2: Add `loginWithSeedphrase`**

Add this method anywhere inside the `LoginViewModel` class body (e.g. directly after the existing `login` property/function):

```kotlin
    fun loginWithSeedphrase(
        ctx: Context,
        api: Api?,
        seedphrase: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (seedphraseAuthInProgress) return

        val authApi = api ?: run {
            setLoginError(ctx.getString(R.string.login_error))
            return
        }

        setLoginError(null)
        seedphraseAuthInProgress = true

        val normalized = seedphrase.lowercase().trim().replace(Regex("\\s+"), " ")
        val args = AuthLoginArgs()
        args.seedphrase = normalized

        authApi.authLogin(args) { result, err ->
            viewModelScope.launch {
                if (err != null) {
                    setLoginError(err.message)
                    onError(err.message ?: "Seedphrase login failed")
                } else if (result.error != null) {
                    setLoginError(result.error.message)
                    onError(result.error.message ?: "Invalid seedphrase")
                } else if (result.network != null && result.network.byJwt.isNotEmpty()) {
                    setLoginError(null)
                    onSuccess(result.network.byJwt)
                } else {
                    val msg = "Invalid seedphrase"
                    setLoginError(msg)
                    onError(msg)
                }
                seedphraseAuthInProgress = false
            }
        }
    }
```

`AuthLoginArgs`, `Context`, `Api`, `viewModelScope`, `R` are all already imported/available in this file (used by the existing `login`/other auth methods) — verify, don't duplicate imports. If `AuthLoginArgs` is not already imported, add `import com.bringyour.sdk.AuthLoginArgs`.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/google/java/com/bringyour/network/ui/login/LoginViewModel.kt
git commit -m "feat(google): add seedphrase login, remove guest-mode flag"
```

### Task 7: `google` flavor — `LoginActivity.kt`

**Files:**
- Modify: `app/app/src/google/java/com/bringyour/network/LoginActivity.kt`

**Interfaces:** None new — internal behavior change only.

- [ ] **Step 1: Stop relying on the `guestMode` JWT flag for the "already fully set up" check**

Find:
```kotlin
                        } else if (jwt.guestMode) {
                            setLinksAndStartMain(targetUrl, defaultLocation)
```

Replace with:
```kotlin
                        } else if (jwt.networkName != null) {
                            setLinksAndStartMain(targetUrl, defaultLocation)
```

- [ ] **Step 2: Stop creating guest-flagged networks in `createGuestNetworkAndFinish`**

Find (inside `private fun createGuestNetworkAndFinish(app: MainApplication)`):
```kotlin
        val args = NetworkCreateArgs()
        args.terms = true
        args.guestMode = true
```

Replace with:
```kotlin
        val args = NetworkCreateArgs()
        args.terms = true
```

(This function is the local-session-recovery fallback used when parsing a stored JWT fails — it still runs, it just now creates a normal seedphrase-backed instant account instead of a guest-flagged one, consistent with guest mode no longer existing as a concept.)

- [ ] **Step 3: Commit**

```bash
git add app/app/src/google/java/com/bringyour/network/LoginActivity.kt
git commit -m "fix(google): stop using the guestMode JWT flag"
```

### Task 8: `google` flavor — `LoginInitial.kt`

**Files:**
- Modify: `app/app/src/google/java/com/bringyour/network/ui/login/LoginInitial.kt`

**Interfaces:**
- Consumes: `LoginViewModel.loginWithSeedphrase`/`.seedphraseAuthInProgress` (Task 6, via `LoginSeedphrase.kt`'s own `hiltViewModel()`, not directly).
- Produces: navigates to `"login_seedphrase"` and `"create-network-instant"` (Task 5's routes).

- [ ] **Step 1: Add new imports**

In the import block, add:
```kotlin
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
```
(`androidx.compose.material.icons.Icons` is already imported in this file.)

- [ ] **Step 2: Remove the `createGuestModeInProgress`/`setCreateGuestModeInProgress` params from the outer `LoginInitial` composable, and add `onSeedphraseLogin`/`onInstantAccountCreate` locals wired to `navController`**

Find (outer `LoginInitial` composable body, right after the `LifecycleResumeEffect`/similar setup and before the inner `LoginInitial(...)` call — locate the exact spot by finding where `val navigateToLoginPassword` or similar locals are declared just above the inner-composable call):

```kotlin
    LoginInitial(
        navController,
        userAuth = loginViewModel.userAuth,
```
(the inner-composable invocation — do not confuse with the outer `fun LoginInitial(...)` declaration a few lines above it)

Immediately **before** this inner-composable call, insert:
```kotlin
    val onSeedphraseLogin: () -> Unit = {
        navController.navigate("login_seedphrase")
    }

    val onInstantAccountCreate: () -> Unit = {
        navController.navigate("create-network-instant")
    }

```

Then, within that same inner-composable call's argument list, find:
```kotlin
        createGuestModeInProgress = loginViewModel.createGuestModeInProgress,
        setCreateGuestModeInProgress = loginViewModel.setCreateGuestModeInProgress,
```
and delete both lines. Find the last argument of that call (the `setWelcomeOverlayVisible = { welcomeOverlayVisible = it }` block) and change its trailing `}` to `},` then add two more named arguments after it:
```kotlin
        setWelcomeOverlayVisible = {
            welcomeOverlayVisible = it
        },
        onSeedphraseLogin = onSeedphraseLogin,
        onInstantAccountCreate = onInstantAccountCreate
    )
```

- [ ] **Step 3: Update the inner `LoginInitial` composable's parameter list**

Find:
```kotlin
    loginError: String?,
    setLoginError: (String?) -> Unit,
    googleAuthInProgress: Boolean,
    createGuestModeInProgress: Boolean,
    setGoogleAuthInProgress: (Boolean) -> Unit,
    setCreateGuestModeInProgress: (Boolean) -> Unit,
    allowGoogleSso: () -> Boolean,
```

Replace with:
```kotlin
    loginError: String?,
    setLoginError: (String?) -> Unit,
    googleAuthInProgress: Boolean,
    setGoogleAuthInProgress: (Boolean) -> Unit,
    allowGoogleSso: () -> Boolean,
```

Find the end of this same parameter list:
```kotlin
    welcomeOverlayVisible: Boolean,
    setWelcomeOverlayVisible: (Boolean) -> Unit,
) {
```
Replace with:
```kotlin
    welcomeOverlayVisible: Boolean,
    setWelcomeOverlayVisible: (Boolean) -> Unit,
    onSeedphraseLogin: () -> Unit,
    onInstantAccountCreate: () -> Unit,
) {
```
(No `= {}` default — this composable is always called with both explicitly, per Step 2. A default here is how PR #2's shadowing bug happened; don't repeat it.)

- [ ] **Step 4: Delete the `guestModeOverlayVisible` state and `createGuestNetwork` function from inside this composable's body**

Find and delete entirely:
```kotlin
    var guestModeOverlayVisible by remember { mutableStateOf(false) }

    val setGuestModeOverlayVisible: (Boolean) -> Unit = { isVisible ->
        if (isVisible) {
            setLoginError(null)
        }
        guestModeOverlayVisible = isVisible
    }

```
and, further down in the same function body, find and delete the entire `createGuestNetwork` block (from `val createGuestNetwork = createGuestNetwork@{` through its closing `}` right before the `LaunchedEffect(Unit) {` that signs the user out of Google on entry — roughly 60 lines, bounded exactly by those two markers).

- [ ] **Step 5: Stop passing the deleted guest-mode props to `LoginInitialActions`, pass the two new callbacks instead**

Find, inside the `LoginInitialActions(...)` call:
```kotlin
                        setGuestModeOverlayVisible = setGuestModeOverlayVisible,
                        googleAuthInProgress = googleAuthInProgress,
                        createGuestModeInProgress = createGuestModeInProgress,
```
Replace with:
```kotlin
                        googleAuthInProgress = googleAuthInProgress,
```
Find the closing of that same call:
```kotlin
                        launchAuthCodeLoginSheet = {
                            setAuthCodeLoginSheetVisible(true)
                        }
                    )
```
Replace with:
```kotlin
                        launchAuthCodeLoginSheet = {
                            setAuthCodeLoginSheetVisible(true)
                        },
                        onSeedphraseLogin = onSeedphraseLogin,
                        onInstantAccountCreate = onInstantAccountCreate
                    )
```

- [ ] **Step 6: Delete the `OnboardingGuestModeSheet(...)` call**

Find and delete (a sibling call at the same level as `AuthCodeLoginSheet(...)`, right before the `if (welcomeOverlayVisible) { ... }` block):
```kotlin
    OnboardingGuestModeSheet(
        isPresenting = guestModeOverlayVisible,
        setIsPresenting = {
            setGuestModeOverlayVisible(it)
        },
        onCreateGuestNetwork = {
            createGuestNetwork()
        },
        createGuestModeInProgress = createGuestModeInProgress,
        errorMessage = if (guestModeOverlayVisible) loginError else null
    )

```

- [ ] **Step 7: Update `LoginInitialActions`'s own parameter list and `isLoginInProgress`**

Find:
```kotlin
fun LoginInitialActions(
    userAuth: TextFieldValue,
    setUserAuth: (TextFieldValue) -> Unit,
    userAuthInProgress: Boolean,
    isValidUserAuth: Boolean,
    setGuestModeOverlayVisible: (Boolean) -> Unit,
    googleAuthInProgress: Boolean,
    createGuestModeInProgress: Boolean,
    loginError: String?,
    onLogin: () -> Unit,
    onGoogleLogin: () -> Unit,
    allowGoogleSso: () -> Boolean,
    onSolanaLogin: () -> Unit,
    solanaAuthInProgress: Boolean,
    onBittensorLogin: () -> Unit,
    bittensorAuthInProgress: Boolean,
    launchAuthCodeLoginSheet: () -> Unit
) {

    val isLoginInProgress = userAuthInProgress || googleAuthInProgress || solanaAuthInProgress || bittensorAuthInProgress || createGuestModeInProgress
```
Replace with:
```kotlin
fun LoginInitialActions(
    userAuth: TextFieldValue,
    setUserAuth: (TextFieldValue) -> Unit,
    userAuthInProgress: Boolean,
    isValidUserAuth: Boolean,
    googleAuthInProgress: Boolean,
    loginError: String?,
    onLogin: () -> Unit,
    onGoogleLogin: () -> Unit,
    allowGoogleSso: () -> Boolean,
    onSolanaLogin: () -> Unit,
    solanaAuthInProgress: Boolean,
    onBittensorLogin: () -> Unit,
    bittensorAuthInProgress: Boolean,
    launchAuthCodeLoginSheet: () -> Unit,
    onSeedphraseLogin: () -> Unit,
    onInstantAccountCreate: () -> Unit,
) {
    val isLoginInProgress = userAuthInProgress || googleAuthInProgress || solanaAuthInProgress || bittensorAuthInProgress
```

- [ ] **Step 8: Replace the `TryGuestMode(...)` call with the two new buttons**

Find:
```kotlin
            TryGuestMode(
                setGuestModeOverlayVisible = setGuestModeOverlayVisible,
                enabled = !isLoginInProgress
            )

            Spacer(modifier = Modifier.height(16.dp))

            NetworkServerSelector(enabled = !isLoginInProgress)
```
Replace with:
```kotlin
            /**
             * Seedphrase Sign in
             */
            URButton(
                style = ButtonStyle.SECONDARY,
                onClick = {
                    onSeedphraseLogin()
                },
                enabled = !isLoginInProgress
            ) { buttonTextStyle ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Sign in with Seedphrase",
                        style = buttonTextStyle
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            /**
             * Instant account creation
             */
            URButton(
                style = ButtonStyle.SECONDARY,
                onClick = {
                    onInstantAccountCreate()
                },
                enabled = !isLoginInProgress
            ) { buttonTextStyle ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Create Instant Account",
                        style = buttonTextStyle
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            NetworkServerSelector(enabled = !isLoginInProgress)
```

- [ ] **Step 9: Delete the now-unused `TryGuestMode` private composable**

Find and delete the entire block, from:
```kotlin
@Composable
private fun TryGuestMode(
    setGuestModeOverlayVisible: (Boolean) -> Unit,
    enabled: Boolean
) {
```
through its matching closing `}` (it ends with the `Row { Text(...) }` block — the last private function before the `@Preview()` section).

- [ ] **Step 10: Fix the two `@Preview` composables so the file still compiles**

In both `LoginInitialPreview` and `LoginInitialLandscapePreview` (or whichever preview functions exist in this file), find:
```kotlin
                    createGuestModeInProgress = false,
                    setGoogleAuthInProgress = {},
                    setCreateGuestModeInProgress = {},
```
Replace with:
```kotlin
                    setGoogleAuthInProgress = {},
```
And add, to the same `LoginInitial(...)` call's argument list (anywhere after `setWelcomeOverlayVisible = {}`):
```kotlin
                    onSeedphraseLogin = {},
                    onInstantAccountCreate = {},
```

- [ ] **Step 11: Commit**

```bash
git add app/app/src/google/java/com/bringyour/network/ui/login/LoginInitial.kt
git commit -m "feat(google): add seedphrase/instant-account buttons, remove guest mode"
```

### Task 9: Checkpoint — push and verify `google` flavor builds clean

- [ ] **Step 1: Push**

```bash
git push -u origin HEAD
```

- [ ] **Step 2: Watch CI**

```bash
gh run watch --repo Ryanmello07/urnetwork-android $(gh run list --repo Ryanmello07/urnetwork-android --branch <branch-name> --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: `Build Android app (google)` succeeds. `ungoogle`/`ethos_dapp`/`solana_dapp` jobs also run (Phase 0 builds all 4 on every push) and should still succeed too, since Tasks 2–5's shared-`main` changes are purely additive and those 3 flavors' own `LoginInitial.kt`/`LoginViewModel.kt`/`LoginActivity.kt` haven't been touched yet. If `google` fails, fix the reported compile error before proceeding — do not move to Task 10 with a red `google` build, since Tasks 10–19 repeat this exact pattern 3 more times and any mistake here will be repeated.

### Task 10: `ungoogle` flavor — `LoginViewModel.kt`

**Files:**
- Modify: `app/app/src/ungoogle/java/com/bringyour/network/ui/login/LoginViewModel.kt`

**Interfaces:** Same as Task 6.

- [ ] **Step 1 & 2: Apply the identical rename + new-method pattern from Task 6**

This file's current `createGuestModeInProgress`/`setCreateGuestModeInProgress` block is at the same relative position (near the top of the class, right after `loginError`). Apply the exact same two edits as Task 6, Steps 1–2, to this file (the `loginWithSeedphrase` method body is byte-identical across all 4 flavors — confirmed via diff against PR #2's version of this same change).

- [ ] **Step 3: Commit**

```bash
git add app/app/src/ungoogle/java/com/bringyour/network/ui/login/LoginViewModel.kt
git commit -m "feat(ungoogle): add seedphrase login, remove guest-mode flag"
```

### Task 11: `ungoogle` flavor — `LoginActivity.kt`

**Files:**
- Modify: `app/app/src/ungoogle/java/com/bringyour/network/LoginActivity.kt`

- [ ] **Step 1 & 2: Apply the identical pattern from Task 7**

Same two find/replace edits as Task 7 (the `jwt.guestMode` → `jwt.networkName != null` check, and dropping `args.guestMode = true` from `createGuestNetworkAndFinish`) — both markers are present verbatim in this file too.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/ungoogle/java/com/bringyour/network/LoginActivity.kt
git commit -m "fix(ungoogle): stop using the guestMode JWT flag"
```

### Task 12: `ungoogle` flavor — `LoginInitial.kt`

**Files:**
- Modify: `app/app/src/ungoogle/java/com/bringyour/network/ui/login/LoginInitial.kt`

**Interfaces:** Same shape as Task 8, minus Google-specific params (this flavor's `LoginInitial` has no `googleLogin`/`googleAuthInProgress`/`allowGoogleSso` params — do not add them, they don't exist here).

- [ ] **Step 1: Add new imports** — identical to Task 8 Step 1.

- [ ] **Step 2: Add `onSeedphraseLogin`/`onInstantAccountCreate` locals in the outer composable, wired to `navController`, and pass them into the inner call**

Find, in the outer `LoginInitial` composable, the point right before the inner-composable invocation begins (`    LoginInitial(\n        navController,\n        userAuth = loginViewModel.userAuth,`). Insert immediately before it:
```kotlin
    val onSeedphraseLogin: () -> Unit = {
        navController.navigate("login_seedphrase")
    }

    val onInstantAccountCreate: () -> Unit = {
        navController.navigate("create-network-instant")
    }

```
In that inner call's argument list, find:
```kotlin
        createGuestModeInProgress = loginViewModel.createGuestModeInProgress,
        setCreateGuestModeInProgress = loginViewModel.setCreateGuestModeInProgress,
```
Delete both lines. Find the trailing `setWelcomeOverlayVisible` argument's closing and change it exactly as in Task 8 Step 2 (add trailing comma, then `onSeedphraseLogin = onSeedphraseLogin,` and `onInstantAccountCreate = onInstantAccountCreate`).

- [ ] **Step 3: Update the inner composable's parameter list**

Find:
```kotlin
    loginError: String?,
    setLoginError: (String?) -> Unit,
    createGuestModeInProgress: Boolean,
    setCreateGuestModeInProgress: (Boolean) -> Unit,
    solanaLogin: () -> Unit,
```
Replace with:
```kotlin
    loginError: String?,
    setLoginError: (String?) -> Unit,
    solanaLogin: () -> Unit,
```
At the end of this same parameter list, find `welcomeOverlayVisible: Boolean,\n    setWelcomeOverlayVisible: (Boolean) -> Unit,\n) {` and replace with the same 2-param addition as Task 8 Step 3 (no defaults).

- [ ] **Step 4: Delete `guestModeOverlayVisible` state and `createGuestNetwork` function**

Same deletions as Task 8 Step 4, applied to this file's own copies of those blocks (present verbatim, confirmed via diff).

- [ ] **Step 5: Update the `LoginInitialActions(...)` call site**

Find:
```kotlin
                        setGuestModeOverlayVisible = setGuestModeOverlayVisible,
                        createGuestModeInProgress = createGuestModeInProgress,
```
Delete both lines. At the call's end, find `launchAuthCodeLoginSheet = {\n                            setAuthCodeLoginSheetVisible(true)\n                        }\n                    )` and replace with the same trailing-comma + two-new-args pattern as Task 8 Step 5.

- [ ] **Step 6: Delete the `OnboardingGuestModeSheet(...)` call** — same as Task 8 Step 6, present verbatim in this file.

- [ ] **Step 7: Update `LoginInitialActions`'s parameter list and `isLoginInProgress`**

Find:
```kotlin
fun LoginInitialActions(
    userAuth: TextFieldValue,
    setUserAuth: (TextFieldValue) -> Unit,
    userAuthInProgress: Boolean,
    isValidUserAuth: Boolean,
    setGuestModeOverlayVisible: (Boolean) -> Unit,
    createGuestModeInProgress: Boolean,
    loginError: String?,
    onLogin: () -> Unit,
    onSolanaLogin: () -> Unit,
    solanaAuthInProgress: Boolean,
    onBittensorLogin: () -> Unit,
    bittensorAuthInProgress: Boolean,
    launchAuthCodeLoginSheet: () -> Unit,
) {
    val isLoginInProgress = userAuthInProgress || solanaAuthInProgress || bittensorAuthInProgress || createGuestModeInProgress
```
Replace with:
```kotlin
fun LoginInitialActions(
    userAuth: TextFieldValue,
    setUserAuth: (TextFieldValue) -> Unit,
    userAuthInProgress: Boolean,
    isValidUserAuth: Boolean,
    loginError: String?,
    onLogin: () -> Unit,
    onSolanaLogin: () -> Unit,
    solanaAuthInProgress: Boolean,
    onBittensorLogin: () -> Unit,
    bittensorAuthInProgress: Boolean,
    launchAuthCodeLoginSheet: () -> Unit,
    onSeedphraseLogin: () -> Unit,
    onInstantAccountCreate: () -> Unit,
) {
    val isLoginInProgress = userAuthInProgress || solanaAuthInProgress || bittensorAuthInProgress
```

- [ ] **Step 8: Replace `TryGuestMode(...)` with the two new buttons** — identical block to Task 8 Step 8.

- [ ] **Step 9: Delete the `TryGuestMode` private composable** — same as Task 8 Step 9.

- [ ] **Step 10: Fix the `@Preview` composables**

Find (appears twice, once per preview function):
```kotlin
                    createGuestModeInProgress = false,
                    setCreateGuestModeInProgress = {},
```
Delete both lines (both occurrences), and add `onSeedphraseLogin = {},` / `onInstantAccountCreate = {},` to each preview's `LoginInitial(...)` call argument list, same as Task 8 Step 10.

- [ ] **Step 11: Commit**

```bash
git add app/app/src/ungoogle/java/com/bringyour/network/ui/login/LoginInitial.kt
git commit -m "feat(ungoogle): add seedphrase/instant-account buttons, remove guest mode"
```

### Task 13: `ethos_dapp` flavor — `LoginViewModel.kt`

**Files:**
- Modify: `app/app/src/ethos_dapp/java/com/bringyour/network/ui/login/LoginViewModel.kt`

- [ ] **Step 1 & 2:** Same pattern as Task 6, applied to this file's own `createGuestModeInProgress` block (near the top of the class) and the same `loginWithSeedphrase` method body (byte-identical across flavors, confirmed by diff).

- [ ] **Step 3: Commit**

```bash
git add app/app/src/ethos_dapp/java/com/bringyour/network/ui/login/LoginViewModel.kt
git commit -m "feat(ethos_dapp): add seedphrase login, remove guest-mode flag"
```

### Task 14: `ethos_dapp` flavor — `LoginActivity.kt`

**Files:**
- Modify: `app/app/src/ethos_dapp/java/com/bringyour/network/LoginActivity.kt`

- [ ] **Step 1 & 2:** Same two edits as Task 7, both markers present verbatim in this file.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/ethos_dapp/java/com/bringyour/network/LoginActivity.kt
git commit -m "fix(ethos_dapp): stop using the guestMode JWT flag"
```

### Task 15: `ethos_dapp` flavor — `LoginInitial.kt`

**Files:**
- Modify: `app/app/src/ethos_dapp/java/com/bringyour/network/ui/login/LoginInitial.kt`

**Interfaces:** Same shape as Task 8, plus this flavor's extra `ethOsLogin`/`hasEthOsWallet`/`ethOsAuthInProgress` params (keep these untouched — they're unrelated to guest mode).

- [ ] **Step 1: Add new imports** — identical to Task 8 Step 1.

- [ ] **Step 2: Add `onSeedphraseLogin`/`onInstantAccountCreate` locals + wire into inner call**

Same pattern as Task 8 Step 2. In the inner call's argument list, find and delete:
```kotlin
        createGuestModeInProgress = loginViewModel.createGuestModeInProgress,
        setCreateGuestModeInProgress = loginViewModel.setCreateGuestModeInProgress,
```
Add the trailing-comma + two new named args after `setWelcomeOverlayVisible`, same as Task 8.

- [ ] **Step 3: Update the inner composable's parameter list**

Find:
```kotlin
    loginError: String?,
    setLoginError: (String?) -> Unit,
    googleAuthInProgress: Boolean,
    createGuestModeInProgress: Boolean,
    setGoogleAuthInProgress: (Boolean) -> Unit,
    setCreateGuestModeInProgress: (Boolean) -> Unit,
    allowGoogleSso: () -> Boolean,
```
Replace with:
```kotlin
    loginError: String?,
    setLoginError: (String?) -> Unit,
    googleAuthInProgress: Boolean,
    setGoogleAuthInProgress: (Boolean) -> Unit,
    allowGoogleSso: () -> Boolean,
```
At the end of the parameter list, add the same 2-param addition as Task 8 Step 3 (no defaults).

- [ ] **Step 4: Delete `guestModeOverlayVisible` state and `createGuestNetwork` function** — same deletions as Task 8 Step 4 (this flavor's error-message variable inside the function is named `createNetworkLoginError` instead of `loginErrorMsg` — delete the whole function regardless of that internal reference, it goes away with it).

- [ ] **Step 5: Update the `LoginInitialActions(...)` call site**

Find:
```kotlin
                        setGuestModeOverlayVisible = setGuestModeOverlayVisible,
                        googleAuthInProgress = googleAuthInProgress,
                        createGuestModeInProgress = createGuestModeInProgress,
```
Replace with:
```kotlin
                        googleAuthInProgress = googleAuthInProgress,
```
At the call's end (`launchAuthCodeLoginSheet = { setAuthCodeLoginSheetVisible(true) }`), apply the same trailing-comma + two-new-args pattern as Task 8 Step 5.

- [ ] **Step 6: Delete the `OnboardingGuestModeSheet(...)` call** — same as Task 8 Step 6.

- [ ] **Step 7: Update `LoginInitialActions`'s parameter list and `isLoginInProgress`**

Find:
```kotlin
    setGuestModeOverlayVisible: (Boolean) -> Unit,
    googleAuthInProgress: Boolean,
    createGuestModeInProgress: Boolean,
    loginError: String?,
```
Replace with:
```kotlin
    googleAuthInProgress: Boolean,
    loginError: String?,
```
Find:
```kotlin
    launchAuthCodeLoginSheet: () -> Unit
) {

    val isLoginInProgress = userAuthInProgress || googleAuthInProgress || solanaAuthInProgress || ethOsAuthInProgress || bittensorAuthInProgress || createGuestModeInProgress
```
Replace with:
```kotlin
    launchAuthCodeLoginSheet: () -> Unit,
    onSeedphraseLogin: () -> Unit,
    onInstantAccountCreate: () -> Unit,
) {
    val isLoginInProgress = userAuthInProgress || googleAuthInProgress || solanaAuthInProgress || ethOsAuthInProgress || bittensorAuthInProgress
```

- [ ] **Step 8: Insert the two new buttons**

This flavor's button list ends differently (there's a trailing `// }` comment line after the last SSO button, from a commented-out `if (allowGoogleSso())` block, confirmed present in the current file). Find:
```kotlin
            // }

            if (!loginError.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                URInlineErrorText(loginError)
            }

            Spacer(modifier = Modifier.height(16.dp))

            TryGuestMode(
                setGuestModeOverlayVisible = setGuestModeOverlayVisible,
                enabled = !isLoginInProgress
            )

            Spacer(modifier = Modifier.height(16.dp))

            NetworkServerSelector(enabled = !isLoginInProgress)
```
Replace with:
```kotlin
            // }

            Spacer(modifier = Modifier.height(16.dp))

            /**
             * Seedphrase Sign in
             */
            URButton(
                style = ButtonStyle.SECONDARY,
                onClick = {
                    onSeedphraseLogin()
                },
                enabled = !isLoginInProgress
            ) { buttonTextStyle ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Sign in with Seedphrase",
                        style = buttonTextStyle
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            /**
             * Instant account creation
             */
            URButton(
                style = ButtonStyle.SECONDARY,
                onClick = {
                    onInstantAccountCreate()
                },
                enabled = !isLoginInProgress
            ) { buttonTextStyle ->
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Create Instant Account",
                        style = buttonTextStyle
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!loginError.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                URInlineErrorText(loginError)
            }

            Spacer(modifier = Modifier.height(16.dp))

            NetworkServerSelector(enabled = !isLoginInProgress)
```

- [ ] **Step 9: Delete the `TryGuestMode` private composable** — same as Task 8 Step 9.

- [ ] **Step 10: Fix the `@Preview` composables**

Find (appears twice):
```kotlin
                    createGuestModeInProgress = false,
                    setGoogleAuthInProgress = {},
                    setCreateGuestModeInProgress = {},
```
Replace both occurrences with:
```kotlin
                    setGoogleAuthInProgress = {},
```
Add `onSeedphraseLogin = {},` / `onInstantAccountCreate = {},` to each preview's call, same as Task 8 Step 10.

- [ ] **Step 11: Commit**

```bash
git add app/app/src/ethos_dapp/java/com/bringyour/network/ui/login/LoginInitial.kt
git commit -m "feat(ethos_dapp): add seedphrase/instant-account buttons, remove guest mode"
```

### Task 16: `solana_dapp` flavor — `LoginViewModel.kt`

**Files:**
- Modify: `app/app/src/solana_dapp/java/com/bringyour/network/ui/login/LoginViewModel.kt`

- [ ] **Step 1 & 2:** Same pattern as Task 6.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/solana_dapp/java/com/bringyour/network/ui/login/LoginViewModel.kt
git commit -m "feat(solana_dapp): add seedphrase login, remove guest-mode flag"
```

### Task 17: `solana_dapp` flavor — `LoginActivity.kt`

**Files:**
- Modify: `app/app/src/solana_dapp/java/com/bringyour/network/LoginActivity.kt`

- [ ] **Step 1 & 2:** Same two edits as Task 7.

- [ ] **Step 3: Commit**

```bash
git add app/app/src/solana_dapp/java/com/bringyour/network/LoginActivity.kt
git commit -m "fix(solana_dapp): stop using the guestMode JWT flag"
```

### Task 18: `solana_dapp` flavor — `LoginInitial.kt`

**Files:**
- Modify: `app/app/src/solana_dapp/java/com/bringyour/network/ui/login/LoginInitial.kt`

**Interfaces:** Same shape as Task 8 (this flavor has `googleLogin`/`googleAuthInProgress`/`allowGoogleSso` too, like `google` — keep those untouched).

- [ ] **Step 1: Add new imports** — identical to Task 8 Step 1.

- [ ] **Step 2: Add `onSeedphraseLogin`/`onInstantAccountCreate` locals + wire into inner call**

Same pattern as Task 8 Step 2. Delete:
```kotlin
        createGuestModeInProgress = loginViewModel.createGuestModeInProgress,
        setCreateGuestModeInProgress = loginViewModel.setCreateGuestModeInProgress,
```
Add trailing-comma + two new args after `setWelcomeOverlayVisible`.

- [ ] **Step 3: Update the inner composable's parameter list**

Find:
```kotlin
    googleAuthInProgress: Boolean,
    createGuestModeInProgress: Boolean,
    setGoogleAuthInProgress: (Boolean) -> Unit,
    setCreateGuestModeInProgress: (Boolean) -> Unit,
    allowGoogleSso: () -> Boolean,
```
Replace with:
```kotlin
    googleAuthInProgress: Boolean,
    setGoogleAuthInProgress: (Boolean) -> Unit,
    allowGoogleSso: () -> Boolean,
```
Add the 2-param addition at the end of the list, same as Task 8 Step 3.

- [ ] **Step 4: Delete `guestModeOverlayVisible` state and `createGuestNetwork` function** — same as Task 8 Step 4 (this flavor's error variable is named `createNetworkError`; delete the whole function regardless).

- [ ] **Step 5: Update the `LoginInitialActions(...)` call site**

Find:
```kotlin
                        setGuestModeOverlayVisible = setGuestModeOverlayVisible,
                        googleAuthInProgress = googleAuthInProgress,
                        createGuestModeInProgress = createGuestModeInProgress,
```
Replace with:
```kotlin
                        googleAuthInProgress = googleAuthInProgress,
```
Apply the trailing-comma + two-new-args pattern at the call's end, same as Task 8 Step 5.

- [ ] **Step 6: Delete the `OnboardingGuestModeSheet(...)` call** — same as Task 8 Step 6 (note: in this flavor the surrounding `AuthCodeLoginSheet` call comes right after — delete only the `OnboardingGuestModeSheet` block, leave `AuthCodeLoginSheet` untouched).

- [ ] **Step 7: Update `LoginInitialActions`'s parameter list and `isLoginInProgress`**

Find:
```kotlin
    setGuestModeOverlayVisible: (Boolean) -> Unit,
    googleAuthInProgress: Boolean,
    createGuestModeInProgress: Boolean,
    loginError: String?,
```
Replace with:
```kotlin
    googleAuthInProgress: Boolean,
    loginError: String?,
```
Find:
```kotlin
    launchAuthCodeLoginSheet: () -> Unit
) {

    val isLoginInProgress = userAuthInProgress || googleAuthInProgress || solanaAuthInProgress || bittensorAuthInProgress || createGuestModeInProgress
```
Replace with:
```kotlin
    launchAuthCodeLoginSheet: () -> Unit,
    onSeedphraseLogin: () -> Unit,
    onInstantAccountCreate: () -> Unit,
) {
    val isLoginInProgress = userAuthInProgress || googleAuthInProgress || solanaAuthInProgress || bittensorAuthInProgress
```

- [ ] **Step 8: Replace `TryGuestMode(...)` with the two new buttons**

Find:
```kotlin
            // }

            if (!loginError.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                URInlineErrorText(loginError)
            }

            Spacer(modifier = Modifier.height(16.dp))

            TryGuestMode(
                setGuestModeOverlayVisible = setGuestModeOverlayVisible,
                enabled = !isLoginInProgress
            )

            Spacer(modifier = Modifier.height(16.dp))

            NetworkServerSelector(enabled = !isLoginInProgress)
```
Replace with the same block as Task 15 Step 8 (identical structure: `// }`, spacer, seedphrase button, spacer, instant-account button, spacer, error text, spacer, `NetworkServerSelector`).

- [ ] **Step 9: Delete the `TryGuestMode` private composable** — same as Task 8 Step 9.

- [ ] **Step 10: Fix the `@Preview` composables**

Find (appears twice):
```kotlin
                    createGuestModeInProgress = false,
                    setGoogleAuthInProgress = {},
                    setCreateGuestModeInProgress = {},
```
Replace both with:
```kotlin
                    setGoogleAuthInProgress = {},
```
Add `onSeedphraseLogin = {},` / `onInstantAccountCreate = {},` to each preview call.

- [ ] **Step 11: Commit**

```bash
git add app/app/src/solana_dapp/java/com/bringyour/network/ui/login/LoginInitial.kt
git commit -m "feat(solana_dapp): add seedphrase/instant-account buttons, remove guest mode"
```

### Task 19: Checkpoint — push and verify all 4 flavors build clean

- [ ] **Step 1: Push**

```bash
git push -u origin HEAD
```

- [ ] **Step 2: Watch CI**

```bash
gh run watch --repo Ryanmello07/urnetwork-android $(gh run list --repo Ryanmello07/urnetwork-android --branch <branch-name> --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: all 4 `Build Android app (<flavor>)` jobs green. Fix any failures before proceeding — Tasks 20–26 delete files that all 4 flavors' `LoginInitial.kt` still reference until this point, so this is the last safe checkpoint before those references are gone everywhere and it becomes safe to delete the shared files.

### Task 20: Delete the now-unused guest-mode shared-`main` files

**Files:**
- Delete: `app/app/src/main/java/com/bringyour/network/ui/login/OnboardingGuestModeSheet.kt`
- Delete: `app/app/src/main/java/com/bringyour/network/ui/components/overlays/GuestModeOverlay.kt`

**Interfaces:** None — after Tasks 8–18, no flavor's `LoginInitial.kt` references `OnboardingGuestModeSheet` anymore, and after Task 21 (next), `FullScreenOverlay.kt` will stop referencing `GuestModeOverlay`.

- [ ] **Step 1: Delete both files**

```bash
git rm app/app/src/main/java/com/bringyour/network/ui/login/OnboardingGuestModeSheet.kt
git rm app/app/src/main/java/com/bringyour/network/ui/components/overlays/GuestModeOverlay.kt
```

- [ ] **Step 2: Commit**

```bash
git commit -m "chore: delete unused guest-mode sheet and overlay"
```

(Do not push yet — Task 21 removes `FullScreenOverlay.kt`'s reference to `GuestModeOverlay` in the same logical unit of work; pushing between them would leave a broken intermediate commit on the shared branch. Local commits are fine; push happens at Task 27.)

### Task 21: `FullScreenOverlay.kt` — remove `GuestMode`/`OnboardingGuestMode` overlay modes

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/components/overlays/FullScreenOverlay.kt`

- [ ] **Step 1: Remove the two enum entries**

Find:
```kotlin
enum class OverlayMode {
    GuestMode,
    Upgrade,
    Refer,
    FeedbackSubmitted,
    Onboarding,
    OnboardingGuestMode,
    // a purchase Play accepted but has not completed -- awaiting approval or an
    // out-of-band payment. Distinct from Upgrade, which means it actually went through.
    PurchasePending,
}
```
Replace with:
```kotlin
enum class OverlayMode {
    Upgrade,
    Refer,
    FeedbackSubmitted,
    Onboarding,
    // a purchase Play accepted but has not completed -- awaiting approval or an
    // out-of-band payment. Distinct from Upgrade, which means it actually went through.
    PurchasePending,
}
```

- [ ] **Step 2: Remove the `GuestMode` `AnimatedVisibility` block**

Find and delete:
```kotlin
    // You're in Guest mode overlay
    AnimatedVisibility(
        visible = overlayMode == OverlayMode.GuestMode,
        enter = enterTransition,
        exit = exitTransition,
    ) {

        GuestModeOverlay(
            onDismiss = {
                overlayViewModel.launch(null)
            }
        )
    }

```
(Leave the `Refer`/`PurchasePending`/`FeedbackSubmitted`/`Onboarding`/`Upgrade` `AnimatedVisibility` blocks untouched.)

- [ ] **Step 3: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/components/overlays/FullScreenOverlay.kt
git commit -m "chore: remove GuestMode/OnboardingGuestMode overlay modes"
```

### Task 22: `AccountScreen.kt` — redirect guest-mode click targets straight to login instead of the deleted overlay

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/account/AccountScreen.kt`

**Interfaces:** Requires `Intent` (`android.content.Intent`) and `com.bringyour.network.LoginActivity` — check existing imports first; `Intent` is very likely already imported elsewhere in this large file (used for share/wallet-connect flows), add `import android.content.Intent` only if genuinely missing.

- [ ] **Step 1: Replace all 5 `launchOverlay(OverlayMode.GuestMode)` call sites**

This exact call appears 5 times in this file, each inside a different `.clickable { if (loginMode == LoginMode.Guest) { ... } else { ... } }` or `if (loginMode == LoginMode.Authenticated) { ... } else { ... }` block (wallet row, profile nav, settings nav, wallets nav, refer nav). Replace **every occurrence** of:
```kotlin
                                                        launchOverlay(OverlayMode.GuestMode)
```
and
```kotlin
                    launchOverlay(OverlayMode.GuestMode)
```
(indentation varies by call site — match on the exact text `launchOverlay(OverlayMode.GuestMode)` regardless of leading whitespace, there are 5 occurrences total) with:
```kotlin
                    context.startActivity(Intent(context, com.bringyour.network.LoginActivity::class.java))
```
(keep each occurrence's original indentation level — only the call itself changes, not its surrounding structure). A `context` local (`LocalContext.current` or similar) must already be in scope at each of these 5 call sites — confirm this before replacing; it is a Composable file so `context` is virtually certain to already be available (used throughout for navigation/intents elsewhere in this same file).

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/account/AccountScreen.kt
git commit -m "chore: route unauthenticated account taps to LoginActivity, not the deleted guest overlay"
```

### Task 23: `AccountViewModel.kt` — stop deriving login mode from the deleted `guestMode` flag

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/account/AccountViewModel.kt`

- [ ] **Step 1: Replace the `guestMode`-based check**

Find:
```kotlin
        localState?.parseByJwt { jwt, success ->
            viewModelScope.launch {
                setLoginMode(if (success && jwt?.guestMode != true) LoginMode.Authenticated else LoginMode.Guest)
               // setNetworkName(jwt?.networkName ?: "guest")
            }
        }
```
Replace with:
```kotlin
        localState?.parseByJwt { jwt, success ->
            viewModelScope.launch {
                setLoginMode(if (success && jwt?.networkName != null) LoginMode.Authenticated else LoginMode.Guest)
               // setNetworkName(jwt?.networkName ?: "guest")
            }
        }
```
(`LoginMode.Guest` stays as the enum's fallback/default value — see Global Constraints — this just changes what evidence is used to decide `Authenticated` vs. that fallback.)

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/account/AccountViewModel.kt
git commit -m "fix: derive account login mode from networkName, not the removed guestMode flag"
```

### Task 24: `SwitchAccountScreen.kt` — stop flagging switched-to accounts as guest

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/login/SwitchAccountScreen.kt`

- [ ] **Step 1: Remove the `guestMode` flag**

Find (inside `onAccept`'s `if (switchToGuestMode) { ... }` branch):
```kotlin
            setCreateGuestNetworkInProgress(true)
            val args = NetworkCreateArgs()
            args.terms = true
            args.guestMode = true
```
Replace with:
```kotlin
            setCreateGuestNetworkInProgress(true)
            val args = NetworkCreateArgs()
            args.terms = true
```
(The rest of this function, including the `switchToGuestMode: Boolean` parameter, the "switch to guest mode" naming, and the `NetworkCreateArgs()` call itself, is intentionally left as-is — this path handles a user with an *existing legacy* guest-flagged session switching accounts, which is a separate concern from the guest-mode *creation entry point* this PR removes. Dropping `args.guestMode = true` here means this path now creates a normal seedphrase-backed instant account when it runs, matching how `LoginActivity.kt`'s `createGuestNetworkAndFinish` was changed in Tasks 7/11/14/17.)

- [ ] **Step 2: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/login/SwitchAccountScreen.kt
git commit -m "chore: stop flagging switch-account fallback networks as guest"
```

### Task 25: Repoint the 3 onboarding-overlay background images off the deleted guest drawable

**Files:**
- Modify: `app/app/src/main/java/com/bringyour/network/ui/components/overlays/OnboardingOverlay.kt`
- Modify: `app/app/src/main/java/com/bringyour/network/ui/components/overlays/WelcomeAnimatedLoginOverlay.kt`
- Modify: `app/app/src/main/java/com/bringyour/network/ui/components/overlays/WelcomeAnimatedMainOverlay.kt`
- Modify: `app/app/src/main/java/com/bringyour/network/ui/components/overlays/OverlayBackground.kt`

**Interfaces:** None — these are cosmetic-only reference changes, required because Task 26 deletes the `overlay_guest_onboarding_bg`/`overlay_guest_mode_bg` drawables these files currently point at.

- [ ] **Step 1: `OnboardingOverlay.kt`**

Find:
```kotlin
    val backgroundBitmap: ImageBitmap = remember { ImageBitmap.imageResource(context.resources, R.drawable.overlay_guest_onboarding_bg) }
```
Replace with:
```kotlin
    val backgroundBitmap: ImageBitmap = remember { ImageBitmap.imageResource(context.resources, R.drawable.overlay_plan_upgraded_bg) }
```

- [ ] **Step 2: `WelcomeAnimatedLoginOverlay.kt`** — identical find/replace as Step 1, same line pattern, present in this file's `WelcomeAnimatedOverlayLogin()` function.

- [ ] **Step 3: `WelcomeAnimatedMainOverlay.kt`** — identical find/replace as Step 1, present in this file's two-arg `WelcomeAnimatedMainOverlay(isVisible, close)` function.

- [ ] **Step 4: `OverlayBackground.kt`** — this file's only reference to the deleted drawable is in its `@Preview` function, not production code. Find:
```kotlin
private fun FullScreenOverlayPreview() {
    URNetworkTheme {
        OverlayBackground(
            onDismiss = {},
            bgImageResourceId = R.drawable.overlay_guest_mode_bg
        ) {
            Text("Hello world")
        }
```
Replace with:
```kotlin
private fun FullScreenOverlayPreview() {
    URNetworkTheme {
        OverlayBackground(
            onDismiss = {},
            bgImageResourceId = R.drawable.overlay_refer_bg
        ) {
            Text("Hello world")
        }
```

Confirm `R.drawable.overlay_plan_upgraded_bg` and `R.drawable.overlay_refer_bg` both already exist in `app/app/src/main/res/drawable*/` (they back the pre-existing `PlanUpgradedOverlay`/`ReferOverlay` composables referenced in `FullScreenOverlay.kt`) — no new drawable assets need to be added for this task.

- [ ] **Step 5: Commit**

```bash
git add app/app/src/main/java/com/bringyour/network/ui/components/overlays/OnboardingOverlay.kt app/app/src/main/java/com/bringyour/network/ui/components/overlays/WelcomeAnimatedLoginOverlay.kt app/app/src/main/java/com/bringyour/network/ui/components/overlays/WelcomeAnimatedMainOverlay.kt app/app/src/main/java/com/bringyour/network/ui/components/overlays/OverlayBackground.kt
git commit -m "chore: repoint onboarding overlays off the deleted guest-mode drawables"
```

### Task 26: Delete the guest-mode drawable assets and fix the stray preview function name

**Files:**
- Delete: `app/app/src/main/res/drawable/overlay_guest_mode_bg.webp`
- Delete: `app/app/src/main/res/drawable/overlay_guest_onboarding_bg.webp`
- Delete: `app/app/src/main/res/drawable-xhdpi/overlay_guest_mode_bg.webp`
- Delete: `app/app/src/main/res/drawable-xhdpi/overlay_guest_onboarding_bg.webp`
- Delete: `app/app/src/main/res/drawable-land/overlay_guest_mode_bg.webp`
- Delete: `app/app/src/main/res/drawable-land/overlay_guest_onboarding_bg.webp`
- Delete: `app/app/src/main/res/drawable-land-xhdpi/overlay_guest_mode_bg.webp`
- Delete: `app/app/src/main/res/drawable-land-xhdpi/overlay_guest_onboarding_bg.webp`
- Modify: `app/app/src/main/java/com/bringyour/network/ui/login/AuthCodeLoginSheet.kt`

**Interfaces:** None. Deletion is safe only after Task 25 (no code references these drawables anymore) and Task 21/Task 20 (no code references `GuestModeOverlay`, the only other consumer of `overlay_guest_mode_bg`, anymore).

- [ ] **Step 1: Delete the 8 drawable files**

```bash
git rm app/app/src/main/res/drawable/overlay_guest_mode_bg.webp
git rm app/app/src/main/res/drawable/overlay_guest_onboarding_bg.webp
git rm app/app/src/main/res/drawable-xhdpi/overlay_guest_mode_bg.webp
git rm app/app/src/main/res/drawable-xhdpi/overlay_guest_onboarding_bg.webp
git rm app/app/src/main/res/drawable-land/overlay_guest_mode_bg.webp
git rm app/app/src/main/res/drawable-land/overlay_guest_onboarding_bg.webp
git rm app/app/src/main/res/drawable-land-xhdpi/overlay_guest_mode_bg.webp
git rm app/app/src/main/res/drawable-land-xhdpi/overlay_guest_onboarding_bg.webp
```

- [ ] **Step 2: Fix `AuthCodeLoginSheet.kt`'s misnamed preview function**

This file has an unrelated pre-existing copy/paste leftover: its own `@Preview` function is still named `OnboardingGuestModeSheetPreview` even though it previews `AuthCodeLoginSheet`, not the (now-deleted) `OnboardingGuestModeSheet`. Find:
```kotlin
@Preview
@Composable
private fun OnboardingGuestModeSheetPreview() {
```
Replace with:
```kotlin
@Preview
@Composable
private fun AuthCodeLoginSheetPreview() {
```

- [ ] **Step 3: Commit**

```bash
git add -u app/app/src/main/res/drawable app/app/src/main/res/drawable-xhdpi app/app/src/main/res/drawable-land app/app/src/main/res/drawable-land-xhdpi app/app/src/main/java/com/bringyour/network/ui/login/AuthCodeLoginSheet.kt
git commit -m "chore: delete unused guest-mode drawables, fix stray preview function name"
```

### Task 27: Final checkpoint — push and verify all 4 flavors are still green

- [ ] **Step 1: Push**

```bash
git push -u origin HEAD
```

- [ ] **Step 2: Watch CI**

```bash
gh run watch --repo Ryanmello07/urnetwork-android $(gh run list --repo Ryanmello07/urnetwork-android --branch <branch-name> --limit 1 --json databaseId --jq '.[0].databaseId')
```

Expected: all 4 `Build Android app (<flavor>)` jobs green — this is the real gate (see Global Constraints), unlike PR #2's `github`-only green check. Fix any failures before opening the PR.

- [ ] **Step 3: Open the PR**

```bash
gh pr create --repo Ryanmello07/urnetwork-android --base beta/custom-server --head <branch-name> \
  --title "feat: seedphrase login, instant account creation, remove guest mode (all 4 flavors)" \
  --body "Replaces #2. Ports iOS's seedphrase-login and instant-account-creation flow to google/ungoogle/ethos_dapp/solana_dapp, and removes the guest-mode entry point it supersedes. See docs/superpowers/specs/2026-07-19-seedphrase-auth-android-port-design.md for the full port plan (PR2: Settings/AddAuthSheet, PR3: Profile name-claim, Phase 4: upstream squash, are separate follow-up PRs)."
```

- [ ] **Step 4: Close PR #2**

```bash
gh pr close 2 --repo Ryanmello07/urnetwork-android --comment "Superseded by <new PR URL> — replaced outright per the approved port design (docs/superpowers/specs/2026-07-19-seedphrase-auth-android-port-design.md)."
```

---

## Self-review notes

- **Spec coverage**: Phase 0 (CI fix) — Task 1. Phase 1 (PR1: guest-mode removal + seedphrase login/signup, all 4 flavors, google first) — Tasks 2–27. PR2 (Settings/AddAuthSheet) and PR3 (Profile name-claim) are explicitly out of scope for this plan, to be planned just-in-time after this PR merges, per the user's approved staging decision.
- **Deviation from PR #2 flagged explicitly**: this plan does not port PR #2's `LoginCreateNetwork.kt` "Instant | Email/Phone | Wallet" tab row or `LoginCreateNetworkViewModel.kt`'s `createInstantAccount` method — that method was dead code in PR #2 (nothing called it; `CreateNetworkInstant.kt` calls the API directly), and the tab-row UX required typing a throwaway email/phone first before instant-account creation was reachable. iOS instead puts both "Sign in with Seedphrase" and "Create Instant Account" as direct buttons on the initial login screen (confirmed via `app/network/Authenticate/LoginInitial/LoginInitialView.swift:652-699`), which is what this plan ports instead — a deliberate, checked improvement over blindly copying PR #2, not an oversight.
- **Placeholder scan**: no "TBD"/"similar to Task N"/undefined-symbol references remain — Tasks 10–18 explicitly say "same pattern as Task N" only where the substituted code block is fully reproduced or trivially identical (confirmed via diff against each flavor's actual current file), never as a substitute for showing code.
- **Type/signature consistency**: `onSeedphraseLogin: () -> Unit` and `onInstantAccountCreate: () -> Unit` have matching names and signatures at every call site across Tasks 5, 8, 12, 15, 18. `LoginViewModel.loginWithSeedphrase(ctx: Context, api: Api?, seedphrase: String, onSuccess: (String) -> Unit, onError: (String) -> Unit)` and `.seedphraseAuthInProgress: Boolean` are identical across Tasks 6, 10, 13, 16, matching what `LoginSeedphrase.kt` (Task 2) expects from whichever flavor's `LoginViewModel` Hilt injects.
