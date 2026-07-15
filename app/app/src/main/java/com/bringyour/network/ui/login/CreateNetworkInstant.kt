package com.bringyour.network.ui.login

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
                                        seedphrase = result.seedphrase,
                                        jwt = result.network.byJwt
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
