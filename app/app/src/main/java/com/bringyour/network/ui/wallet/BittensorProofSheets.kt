package com.bringyour.network.ui.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bringyour.network.BuildConfig
import com.bringyour.network.R
import com.bringyour.network.ui.login.bittensorWalletReturnLink
import com.bringyour.network.ui.login.launchBittensorBridge
import com.bringyour.network.ui.login.walletInstall
import com.bringyour.network.ui.components.ButtonStyle
import com.bringyour.network.ui.components.URButton
import com.bringyour.network.ui.components.URSwitch
import com.bringyour.network.ui.components.URTextInput
import com.bringyour.network.ui.components.URTextInputLabel
import com.bringyour.network.ui.theme.Red
import com.bringyour.network.ui.theme.SheetBlack
import com.bringyour.network.ui.theme.TextMuted
import com.bringyour.network.utils.Ss58
import com.bringyour.sdk.Api
import com.bringyour.sdk.BittensorWalletConnect
import com.bringyour.sdk.BittensorWalletSession
import com.bringyour.sdk.Sdk
import com.bringyour.sdk.StringList
import com.bringyour.sdk.Sub
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "BittensorProof"

/** The SDK session behind [BittensorProofSession]. */
class SdkBittensorProofSession(
    private val session: BittensorWalletSession,
) : BittensorProofSession {
    override val walletId: String = session.walletId()
    override val purpose: String = session.purpose()
    override val message: String get() = session.message()
    override val transport: String = session.transport()

    override fun handleSignature(address: String, signature: String, nowMillis: Long): BittensorProofOutcome =
        outcome(session.handleSignature(address, signature, nowMillis))

    override fun bridgeUrl(): String? = try {
        session.bridgeUrl()
    } catch (e: Exception) {
        Log.i(TAG, "bridge url: ${e.message}")
        null
    }

    override fun handleBridgeReturn(uri: String, nowMillis: Long): BittensorProofOutcome =
        outcome(session.handleBridgeReturn(uri, nowMillis))

    private fun outcome(result: com.bringyour.sdk.BittensorWalletResult): BittensorProofOutcome {
        val proof = result.proof
        if (result.errorCode.isNullOrEmpty() && proof != null) {
            return BittensorProofOutcome.Proven(
                BittensorProof(
                    walletId = proof.walletId,
                    purpose = proof.purpose,
                    address = proof.address,
                    message = proof.message,
                    signature = proof.signature,
                )
            )
        }
        return BittensorProofOutcome.Refused(
            result.errorCode ?: "",
            result.errorMessage?.takeIf { it.isNotEmpty() },
            result.bridgeErrorCode?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * Starts the SDK session for a request: the session, then the single-use
 * /auth/wallet-challenge (bound to the expected address when there is one).
 */
suspend fun startBittensorProofSession(
    api: Api,
    request: BittensorProofRequest,
    nowMillis: () -> Long = System::currentTimeMillis,
    // the WalletConnect Cloud project id (local.properties) the bridge page pairs with
    walletConnectProjectId: String = BuildConfig.WALLETCONNECT_PROJECT_ID,
): Result<BittensorProofSession> {
    val session = try {
        Sdk.newBittensorWalletSession(
            request.walletId,
            BittensorWallets.PLATFORM,
            request.purpose,
            BittensorWallets.REDIRECT_LINK,
        )
    } catch (e: Exception) {
        return Result.failure(e)
    }
    session.setWalletConnectProjectId(walletConnectProjectId)
    val args = session.challengeArgs(request.expectedAddress ?: "")
    return suspendCancellableCoroutine { continuation ->
        api.authWalletChallenge(args) { result, err ->
            if (!continuation.isActive) {
                return@authWalletChallenge
            }
            if (err != null) {
                continuation.resume(Result.failure(err))
                return@authWalletChallenge
            }
            try {
                session.setChallenge(result, nowMillis())
                continuation.resume(Result.success(SdkBittensorProofSession(session)))
            } catch (e: Exception) {
                Log.i(TAG, "wallet challenge: ${e.message}")
                continuation.resume(Result.failure(e))
            }
        }
    }
}

/**
 * Starts a browser-bridge proof outside the chooser (the create-network second
 * signature after a WalletConnect sign-in): the session waits in
 * [BittensorBridgeReturns] and the page opens in a Custom Tab.
 */
suspend fun startBittensorBridgeProof(
    context: Context,
    api: Api,
    request: BittensorProofRequest,
    bridgeReturns: BittensorBridgeReturns = BittensorBridgeReturns.shared,
): Boolean {
    val session = startBittensorProofSession(api, request).getOrNull() ?: return false
    val url = session.bridgeUrl() ?: return false
    bridgeReturns.begin(session)
    if (!launchBittensorBridge(context, url)) {
        bridgeReturns.cancel()
        return false
    }
    return true
}

/**
 * The SDK connection behind [BittensorWalletConnection]. It owns the one sdk
 * listener of the connection: the sdk calls it on a thread of the connection, and
 * all it does is wake the main thread.
 */
class SdkBittensorWalletConnection(
    private val connect: BittensorWalletConnect,
    override val debugSetup: String = "",
) : BittensorWalletConnection {
    override val walletId: String = connect.walletId()
    override var onChanged: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sub: Sub? = connect.addBittensorWalletConnectListener { _ ->
        mainHandler.post { onChanged?.invoke() }
    }

    override fun state(): String = connect.state() ?: ""

    override fun purpose(): String = connect.purpose() ?: ""

    override fun address(): String = connect.address() ?: ""

    override fun connected(): Boolean = connect.connected()

    override fun sign(purpose: String, expectedAddress: String) {
        connect.sign(purpose, expectedAddress)
    }

    override fun takeWalletLink(): String = connect.takeWalletLink() ?: ""

    override fun walletLink(): String = connect.walletLink() ?: ""

    override fun takeProof(): BittensorProof? {
        val proof = connect.takeProof() ?: return null
        return BittensorProof(
            walletId = proof.walletId,
            purpose = proof.purpose,
            address = proof.address,
            message = proof.message,
            signature = proof.signature,
        )
    }

    override fun failure(): BittensorProofOutcome.Refused? {
        val result = connect.result() ?: return null
        return BittensorProofOutcome.Refused(
            result.errorCode ?: "",
            result.errorMessage?.takeIf { it.isNotEmpty() },
            result.bridgeErrorCode?.takeIf { it.isNotEmpty() },
        )
    }

    override fun setForeground(foreground: Boolean) {
        connect.setForeground(foreground)
    }

    override fun close() {
        sub?.close()
        connect.close()
    }

    // the sdk keeps a trace only when a debug build asked for one (bittensorWalletDebugSetup)
    override fun traceLines(): List<String> {
        if (!BuildConfig.DEBUG) return emptyList()
        return stringListOf(connect.traceLines())
    }
}

private fun stringListOf(list: StringList?): List<String> {
    if (list == null) {
        return emptyList()
    }
    return List(list.len().toInt()) { i -> list.get(i.toLong()) ?: "" }
}

/**
 * The device-test switches of a debug build (the integration plan, section 7), kept
 * in preferences: what one wallet connection sends, so that a wallet that stalls can
 * be tried another way without another build. A release build never reads them.
 */
data class BittensorWalletDebugOptions(
    // the two members the connection request carries beside those of ur.io's
    val pairingTopic: Boolean = true,
    val expiryTimestamp: Boolean = true,
    // name the app's return link in the connection request (WalletReturnActivity)
    val returnLink: Boolean = false,
    // the form of the link that hands the pairing to the wallet: https, scheme, bare
    val link: String = LINK_HTTPS,
    // a pairing of one hour instead of five minutes
    val longPairing: Boolean = false,
    // go on with a wallet whose signing certificate is not a listed one
    val openUnverified: Boolean = false,
) {
    /** The sdk's option string (SetDeviceTestOptions): what differs from its defaults; "" = nothing. */
    fun optionString(): String {
        val tokens = mutableListOf<String>()
        if (!pairingTopic) {
            tokens.add("-T")
        }
        if (!expiryTimestamp) {
            tokens.add("-E")
        }
        if (link != LINK_HTTPS) {
            tokens.add("link=$link")
        }
        if (longPairing) {
            tokens.add("ttl=3600")
        }
        return tokens.joinToString(",")
    }

    /** The form of the pairing link after this one. */
    fun nextLink(): String = when (link) {
        LINK_HTTPS -> LINK_SCHEME
        LINK_SCHEME -> LINK_BARE
        else -> LINK_HTTPS
    }

    companion object {
        const val LINK_HTTPS = "https"
        const val LINK_SCHEME = "scheme"
        const val LINK_BARE = "bare"

        private const val PREFS = "bittensor_wallet_debug"

        fun load(context: Context): BittensorWalletDebugOptions {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return BittensorWalletDebugOptions(
                pairingTopic = prefs.getBoolean("pairing_topic", true),
                expiryTimestamp = prefs.getBoolean("expiry_timestamp", true),
                returnLink = prefs.getBoolean("return_link", false),
                link = prefs.getString("link", LINK_HTTPS) ?: LINK_HTTPS,
                longPairing = prefs.getBoolean("long_pairing", false),
                openUnverified = prefs.getBoolean("open_unverified", false),
            )
        }

        fun save(context: Context, options: BittensorWalletDebugOptions) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("pairing_topic", options.pairingTopic)
                .putBoolean("expiry_timestamp", options.expiryTimestamp)
                .putBoolean("return_link", options.returnLink)
                .putString("link", options.link)
                .putBoolean("long_pairing", options.longPairing)
                .putBoolean("open_unverified", options.openUnverified)
                .apply()
        }
    }
}

/**
 * A debug build's setup of one wallet connection, before its first sign: the sdk's
 * trace, the device-test options and, when its switch is on, the return link. This
 * is the only place that calls them. A release build does nothing here: its
 * connection sends what the sdk sends by default and names no return link.
 *
 * Returns what was set, for the debug panel: option words and the sdk's refusal of
 * one, never a secret (the return link is a fixed public value). A refusal changes
 * nothing in the sdk, so the connection goes on with the defaults.
 */
private fun bittensorWalletDebugSetup(context: Context, connect: BittensorWalletConnect): String {
    if (!BuildConfig.DEBUG) return ""
    connect.setTrace(true)
    val options = BittensorWalletDebugOptions.load(context)
    val optionString = options.optionString()
    val notes = mutableListOf<String>()
    if (optionString.isEmpty()) {
        notes.add("options=default")
    } else {
        try {
            connect.setDeviceTestOptions(optionString)
            notes.add("options=$optionString")
        } catch (e: Exception) {
            notes.add("options=default (refused $optionString: ${e.message})")
        }
    }
    if (options.returnLink) {
        try {
            connect.setReturnLinks(bittensorWalletReturnLink(context.packageName), "")
            notes.add("return=1")
        } catch (e: Exception) {
            notes.add("return=0 (refused: ${e.message})")
        }
    } else {
        notes.add("return=0")
    }
    return notes.joinToString(" ")
}

/**
 * Starts the connection to a wallet app for a request: a new sdk
 * BittensorWalletConnect for the wallet, then one sign. The wallet itself is opened
 * later, by the wake-up routine ([BittensorProofFlow.wake]), with the link the
 * connection hands out. Nothing here waits for the network.
 */
fun startBittensorWalletConnection(
    context: Context,
    api: Api,
    request: BittensorProofRequest,
    // the WalletConnect Cloud project id (local.properties) the app pairs with
    walletConnectProjectId: String = BuildConfig.WALLETCONNECT_PROJECT_ID,
): BittensorWalletStart {
    val connect = try {
        Sdk.newBittensorWalletConnect(
            api,
            request.walletId,
            BittensorWallets.PLATFORM,
            walletConnectProjectId,
            context.packageName,
        )
    } catch (e: Exception) {
        return BittensorWalletStart(null, sdkErrorCode(e))
    }
    val connection = SdkBittensorWalletConnection(connect, bittensorWalletDebugSetup(context, connect))
    return try {
        connection.sign(request.purpose, request.expectedAddress ?: "")
        BittensorWalletStart(connection)
    } catch (e: Exception) {
        connection.close()
        BittensorWalletStart(null, sdkErrorCode(e))
    }
}

/**
 * The code of an sdk error, the word before its text ("walletconnect_unavailable:
 * no WalletConnect project id"). The text can name an address and is left out.
 */
private fun sdkErrorCode(e: Exception): String =
    (e.message ?: "").substringBefore(':').trim().take(48)

/**
 * The wallet-app row of a wallet on this phone with its install check, or null when
 * the sdk does not offer the wallet as an app here: its row is then as before.
 */
fun bittensorWalletApp(context: Context, walletId: String): BittensorWalletApp? {
    val choice = Sdk.bittensorWalletChoiceFor(walletId, BittensorWallets.PLATFORM) ?: return null
    if (choice.transport != Sdk.BittensorWalletTransportWalletApp) {
        return null
    }
    return BittensorWalletApp(
        walletId = walletId,
        install = walletInstall(context, stringListOf(choice.packages), stringListOf(choice.packageSigners)),
        // A debug build's switch, for a device test only: the tester goes on with a
        // copy of the wallet that has the listed package name and another signing
        // certificate. The package is still named on every start of the wallet.
        openUnverified = BuildConfig.DEBUG && BittensorWalletDebugOptions.load(context).openUnverified,
    )
}

/**
 * The proof flow of the login screen: beside the manual rows it opens the wallets
 * the sdk lists as apps, with the connection held in [BittensorWalletConnections].
 */
fun bittensorLoginProofFlow(context: Context): BittensorProofFlow = BittensorProofFlow(
    walletConnections = BittensorWalletConnections.shared,
    walletAppFor = { walletId -> bittensorWalletApp(context, walletId) },
    nowMillis = System::currentTimeMillis,
)

/** The text for a wallet app connection that failed, in this app's words where it has them. */
fun bittensorWalletFailureText(context: Context, walletId: String, refused: BittensorProofOutcome.Refused): String =
    BittensorWallets.refusalText(
        refused.code,
        refused.detail,
        refused.bridgeCode,
        bittensorWalletDisplayName(walletId),
    ) { res, walletName -> if (walletName == null) context.getString(res) else context.getString(res, walletName) }

/** The text for a wallet app that could not be started. */
fun bittensorWalletOpenFailedText(context: Context, walletId: String): String =
    context.getString(R.string.bittensor_wallet_open_failed, bittensorWalletDisplayName(walletId))

// The words of a debug build's switches and panel. They are shown only under
// BuildConfig.DEBUG, to whoever runs a device test, and so are not in the catalogs.
private const val DEBUG_HEADING = "Wallet test (debug build)"
private const val DEBUG_PAIRING_TOPIC = "pairingTopic"
private const val DEBUG_EXPIRY_TIMESTAMP = "expiryTimestamp"
private const val DEBUG_RETURN_LINK = "return link"
private const val DEBUG_LINK = "link (tap to change)"
private const val DEBUG_LONG_PAIRING = "ttl 3600 (off: 300)"
private const val DEBUG_OPEN_UNVERIFIED = "open an unverified wallet (debug)"
private const val DEBUG_COPY_TRACE = "Copy trace"
private const val DEBUG_CLIP = "URnetwork wallet trace"
// the panel shows the end of the trace; the clipboard gets all of it
private const val DEBUG_TRACE_LINES = 12

/**
 * The lines a debug build shows above the trace: the build, what the connection was
 * told and which copy of the wallet it opens, where it stands, the install check of
 * each row and each start of the wallet. Nothing in them is a secret.
 */
private fun bittensorWalletDebugHead(debug: BittensorWalletDebug): List<String> {
    val head = mutableListOf<String>()
    head.add("build=" + BuildConfig.VERSION_NAME + (if (debug.setup.isEmpty()) "" else " " + debug.setup))
    if (debug.state.isNotEmpty()) {
        head.add("state=" + debug.state + " connected=" + debug.connected)
    }
    debug.installs.forEach { install -> head.add("install $install") }
    debug.notes.forEach { note -> head.add("app $note") }
    return head
}

/**
 * A debug build puts what its panel shows of the login screen's wallet connection on
 * the clipboard, with the whole trace: by the button, and by itself when a proof is
 * taken and when a failure is shown, so that a sign-in that worked and left the
 * screen leaves its trace behind. A release build does nothing here.
 */
fun copyBittensorWalletTrace(
    context: Context,
    connections: BittensorWalletConnections = BittensorWalletConnections.shared,
) {
    if (!BuildConfig.DEBUG) return
    connections.refreshDebug()
    val debug = connections.debug.value
    val lines = bittensorWalletDebugHead(debug) + debug.trace
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(DEBUG_CLIP, lines.joinToString("\n")))
    } catch (e: Exception) {
        // a clipboard that refuses is no reason to fail a sign-in
    }
}

/** The product name (not translated). */
fun bittensorWalletDisplayName(walletId: String): String = Sdk.bittensorWalletDisplayName(walletId)

/**
 * The line for a signature pasted from [walletId] that is not from the entered address
 * ([bittensorSignatureMismatchWallet]): sign the message with that address in the wallet.
 */
fun bittensorSignatureMismatchText(context: Context, walletId: String): String =
    context.getString(R.string.bittensor_error_signature_mismatch, bittensorWalletDisplayName(walletId))

/** The line under a wallet-app row; none when its wallet can be opened. */
@Composable
private fun bittensorWalletAppCaption(walletApp: BittensorWalletApp, walletName: String): String? =
    when (walletApp.install) {
        is WalletInstall.Installed -> null
        WalletInstall.NotInstalled -> stringResource(id = R.string.bittensor_wallet_not_installed)
        is WalletInstall.NotVerified -> stringResource(id = R.string.bittensor_wallet_not_verified, walletName)
    }

/** One switch of a debug build's device test. */
@Composable
private fun BittensorWalletDebugSwitch(caption: String, checked: Boolean, toggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        URSwitch(checked = checked, toggle = toggle)
    }
}

/**
 * The device-test switches under the chooser rows (the integration plan, section
 * 7). A debug build only: the caller shows them under BuildConfig.DEBUG. They are
 * read when the next wallet connection is made; `onChanged` when one moved.
 */
@Composable
private fun BittensorWalletDebugSwitches(onChanged: () -> Unit) {
    val context = LocalContext.current
    var options by remember { mutableStateOf(BittensorWalletDebugOptions.load(context)) }
    val change: (BittensorWalletDebugOptions) -> Unit = { next ->
        options = next
        BittensorWalletDebugOptions.save(context, next)
        onChanged()
    }
    Spacer(modifier = Modifier.height(16.dp))
    URTextInputLabel(text = DEBUG_HEADING)
    BittensorWalletDebugSwitch(DEBUG_PAIRING_TOPIC, options.pairingTopic) {
        change(options.copy(pairingTopic = !options.pairingTopic))
    }
    BittensorWalletDebugSwitch(DEBUG_EXPIRY_TIMESTAMP, options.expiryTimestamp) {
        change(options.copy(expiryTimestamp = !options.expiryTimestamp))
    }
    BittensorWalletDebugSwitch(DEBUG_RETURN_LINK, options.returnLink) {
        change(options.copy(returnLink = !options.returnLink))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { change(options.copy(link = options.nextLink())) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(DEBUG_LINK, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Text(options.link, style = MaterialTheme.typography.bodySmall)
    }
    BittensorWalletDebugSwitch(DEBUG_LONG_PAIRING, options.longPairing) {
        change(options.copy(longPairing = !options.longPairing))
    }
    BittensorWalletDebugSwitch(DEBUG_OPEN_UNVERIFIED, options.openUnverified) {
        change(options.copy(openUnverified = !options.openUnverified))
    }
}

/**
 * A debug build's panel under the wallet texts (the integration plan, section 7):
 * what the connection was told, where it stands, the end of the sdk's trace, and a
 * button that copies all of it. A debug build only: the caller shows it under
 * BuildConfig.DEBUG. It is refreshed on every wake-up of the connection, and once
 * a second while it is shown.
 */
@Composable
private fun BittensorWalletDebugPanel(
    connections: BittensorWalletConnections = BittensorWalletConnections.shared,
) {
    val context = LocalContext.current
    val debug by connections.debug.collectAsState()
    LaunchedEffect(connections) {
        while (true) {
            connections.refreshDebug()
            delay(1000)
        }
    }
    val lineStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.height(16.dp))
    SelectionContainer {
        Column {
            bittensorWalletDebugHead(debug).forEach { line ->
                Text(line, style = lineStyle, color = TextMuted)
            }
            debug.trace.takeLast(DEBUG_TRACE_LINES).forEach { line ->
                Text(line, style = lineStyle)
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    URButton(
        onClick = { copyBittensorWalletTrace(context, connections) },
        style = ButtonStyle.OUTLINE,
    ) { buttonTextStyle ->
        Text(DEBUG_COPY_TRACE, style = buttonTextStyle)
    }
}

/**
 * The wallet chooser and the manual proof sheet for a [BittensorProofFlow].
 * `onChoose` starts the session for the chosen wallet; `onSubmit` hands the
 * pasted answer to the session. For a flow that opens wallet apps (the login
 * screen) there is also the sheet that waits for the wallet, with `onOpenWallet`
 * behind its button, and in a debug build the device-test switches and the trace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BittensorProofSheets(
    flow: BittensorProofFlow,
    onChoose: (String) -> Unit,
    onSubmit: () -> Unit,
    displayName: (String) -> String = ::bittensorWalletDisplayName,
    // the "Open wallet" button of the sheet that waits for a wallet app
    onOpenWallet: () -> Unit = {},
) {
    val stage by flow.stage.collectAsState()

    when (val s = stage) {
        BittensorProofStage.Hidden -> {}
        is BittensorProofStage.Choosing, is BittensorProofStage.Loading -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            // a debug build's device test, on the screen that opens wallet apps: with
            // the switches and the trace the chooser is taller than a screen
            val walletDebug = BuildConfig.DEBUG && flow.opensWalletApps
            val scroll: Modifier = if (walletDebug) Modifier.verticalScroll(rememberScrollState()) else Modifier
            ModalBottomSheet(
                onDismissRequest = { flow.dismiss() },
                sheetState = sheetState,
                containerColor = SheetBlack,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .then(scroll)
                ) {
                    Text(
                        stringResource(id = R.string.bittensor_choose_wallet),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val loadingWalletId = (s as? BittensorProofStage.Loading)?.request?.walletId
                    val walletApps by flow.walletApps.collectAsState()
                    BittensorWallets.walletIds.forEach { walletId ->
                        // a wallet the app opens itself; null: the row is as it always was
                        val walletApp = walletApps[walletId]
                        URButton(
                            onClick = { onChoose(walletId) },
                            style = ButtonStyle.SECONDARY,
                            // a wallet app that is not installed, or is not the genuine one, is not opened
                            enabled = loadingWalletId == null && (walletApp == null || walletApp.packageName != null),
                            isProcessing = loadingWalletId == walletId,
                        ) { buttonTextStyle ->
                            Text(displayName(walletId), style = buttonTextStyle)
                        }
                        val caption = if (walletApp != null) {
                            bittensorWalletAppCaption(walletApp, displayName(walletId))
                        } else {
                            BittensorWallets.subtitleRes(walletId)?.let { subtitleRes -> stringResource(id = subtitleRes) }
                        }
                        if (caption != null) {
                            Text(
                                caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    (s as? BittensorProofStage.Choosing)?.errorRes?.let { errorRes ->
                        Text(
                            stringResource(id = errorRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Red
                        )
                    }
                    (s as? BittensorProofStage.Choosing)?.errorText?.let { reason ->
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Red
                        )
                    }
                    if (walletDebug) {
                        BittensorWalletDebugSwitches(onChanged = { flow.refreshWalletApps() })
                        BittensorWalletDebugPanel()
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
        is BittensorProofStage.AwaitingWalletApproval, is BittensorProofStage.AwaitingWalletSignature -> {
            // one sheet for both stages: it stays up while the connection moves from
            // the approval of the connection to the signature
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val signing = s as? BittensorProofStage.AwaitingWalletSignature
            val walletName = displayName(
                signing?.walletId ?: (s as? BittensorProofStage.AwaitingWalletApproval)?.walletId ?: ""
            )
            val waitingFor = if (signing == null) {
                stringResource(id = R.string.bittensor_wallet_approve_connection, walletName)
            } else if (signing.purpose == BittensorWallets.PURPOSE_CREATE) {
                stringResource(id = R.string.bittensor_wallet_approve_create, walletName)
            } else {
                stringResource(id = R.string.bittensor_wallet_approve_request, walletName, Ss58.short(signing.address))
            }
            ModalBottomSheet(
                onDismissRequest = { flow.dismiss() },
                sheetState = sheetState,
                containerColor = SheetBlack,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(walletName, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(waitingFor, style = MaterialTheme.typography.bodyMedium)
                    if (signing == null) {
                        // the wallet can miss the connection prompt on the first open
                        // of its link; the app opens it again when the user comes back
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(id = R.string.bittensor_wallet_reopen_hint, walletName),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    // the app opens the wallet by itself once per step, and again on the
                    // way back while the approval is pending; the button opens it again,
                    // and is the only way for the create-network signature
                    URButton(
                        onClick = onOpenWallet,
                    ) { buttonTextStyle ->
                        Text(stringResource(id = R.string.bittensor_wallet_open, walletName), style = buttonTextStyle)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    URButton(
                        onClick = { flow.dismiss() },
                        style = ButtonStyle.SECONDARY,
                    ) { buttonTextStyle ->
                        Text(stringResource(id = R.string.cancel), style = buttonTextStyle)
                    }
                    if (BuildConfig.DEBUG) {
                        BittensorWalletDebugPanel()
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
        is BittensorProofStage.AwaitingBrowser -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { flow.dismiss() },
                sheetState = sheetState,
                containerColor = SheetBlack,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(displayName(s.walletId), style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(id = R.string.bittensor_walletconnect_continue),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    URButton(
                        onClick = { flow.dismiss() },
                        style = ButtonStyle.SECONDARY,
                    ) { buttonTextStyle ->
                        Text(stringResource(id = R.string.cancel), style = buttonTextStyle)
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
        is BittensorProofStage.Signing -> {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val clipboardManager = LocalClipboardManager.current
            ModalBottomSheet(
                onDismissRequest = { flow.dismiss() },
                sheetState = sheetState,
                containerColor = SheetBlack,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text(
                        stringResource(id = R.string.bittensor_manual_sign_instructions, displayName(s.session.walletId)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        stringResource(id = R.string.bittensor_message_to_sign),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        Text(s.session.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    URButton(
                        onClick = { clipboardManager.setText(AnnotatedString(s.session.message)) },
                        style = ButtonStyle.SECONDARY,
                    ) { buttonTextStyle ->
                        Text(stringResource(id = R.string.copy), style = buttonTextStyle)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    URTextInput(
                        value = TextFieldValue(s.address, TextRange(s.address.length)),
                        onValueChange = { flow.updateAddress(it.text) },
                        label = stringResource(id = R.string.bittensor_wallet),
                        placeholder = stringResource(id = R.string.earnings_address_placeholder),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    URTextInput(
                        value = TextFieldValue(s.signature, TextRange(s.signature.length)),
                        onValueChange = { flow.updateSignature(it.text) },
                        label = stringResource(id = R.string.bittensor_signature_label),
                        placeholder = stringResource(id = R.string.bittensor_signature_placeholder),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        onDone = onSubmit,
                        isValid = s.errorRes == null,
                        supportingText = s.errorRes?.let { stringResource(id = it) },
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    URButton(
                        onClick = onSubmit,
                        enabled = s.address.isNotBlank() && s.signature.isNotBlank(),
                    ) { buttonTextStyle ->
                        Text(stringResource(id = R.string.continue_txt), style = buttonTextStyle)
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
