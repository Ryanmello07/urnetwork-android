package com.bringyour.network.ui.login

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
        androidx.compose.material3.AlertDialog(
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
