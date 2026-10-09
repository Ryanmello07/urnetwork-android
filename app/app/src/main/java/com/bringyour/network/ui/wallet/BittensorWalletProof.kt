package com.bringyour.network.ui.wallet

import androidx.annotation.StringRes
import com.bringyour.network.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app side of a Bittensor coldkey proof: pick a wallet, show the
 * server-issued challenge, take the pasted address and signature, and hand a
 * proof to the flow that asked (sign-in, the create-network second
 * signature, the Earnings coldkey connect, or adding the wallet as a sign-in
 * method).
 *
 * The protocol lives in the SDK (`sdk/bittensor_wallet.go`,
 * `BittensorWalletSession`): what is signed, and whether an answer is
 * acceptable. Here is only the screen state around it, behind
 * [BittensorProofSession] so the state machine runs in plain JVM tests (the
 * gomobile classes need the native library). [SdkBittensorProofSession]
 * adapts the SDK session.
 *
 * Talisman and TAO.com use manual entry on Android: neither documents a
 * mobile deep link, so the user signs the shown message in their wallet and
 * pastes the result. WalletConnect (Nova, Nightly and other substrate
 * wallets) uses the browser bridge: ur.io/bittensor-connect pairs with the
 * wallet app and returns on ur://bittensor-sign-message, where
 * [BittensorBridgeReturns] hands the return to the waiting session.
 *
 * On the login screen a wallet the sdk lists as an app (Talisman) is opened
 * directly instead: the app holds a connection to the wallet
 * ([BittensorWalletConnection], kept in [BittensorWalletConnections]) and the
 * wallet signs there, with no browser and nothing to paste. The other screens
 * keep the manual row for it.
 *
 * Not safe for concurrent use: call from the main thread.
 */
object BittensorWallets {
    // mirror sdk BittensorWalletTalisman / BittensorWalletTaoCom / BittensorWalletWalletConnect
    const val TALISMAN = "talisman"
    const val TAO_COM = "taocom"
    const val WALLET_CONNECT = "walletconnect"

    // mirror sdk BittensorWalletTransport*
    const val TRANSPORT_MANUAL = "manual"
    const val TRANSPORT_BROWSER_BRIDGE = "browser_bridge"
    // the wallet is opened as an app, through a connection the app holds
    const val TRANSPORT_WALLET_APP = "wallet_app"
    // mirror sdk BittensorWalletPlatformAndroid
    const val PLATFORM = "android"

    // mirror sdk BittensorWalletPurpose*
    const val PURPOSE_LOGIN = "login"
    const val PURPOSE_CREATE = "create"
    const val PURPOSE_CONNECT = "connect"
    // add the wallet as a sign-in method to the signed-in network (/auth/add-auth)
    const val PURPOSE_ADD = "add"

    // the app's registered return link: the bridge page returns here
    const val REDIRECT_LINK = "ur://bittensor-sign-message"

    // mirror sdk BittensorWalletConnectState*: the states of a wallet app connection
    const val CONNECT_CONNECTING = "connecting"
    const val CONNECT_AWAITING_APPROVAL = "awaiting_approval"
    const val CONNECT_AWAITING_SIGNATURE = "awaiting_signature"
    const val CONNECT_SIGNED = "signed"
    const val CONNECT_FAILED = "failed"
    const val CONNECT_CLOSED = "closed"

    /** The supported wallets, in display order (sdk BittensorWalletIdList). */
    val walletIds: List<String> = listOf(TALISMAN, TAO_COM, WALLET_CONNECT)

    /** The chooser line under a wallet's name, or null. */
    @StringRes
    fun subtitleRes(walletId: String): Int? = when (walletId) {
        TAO_COM -> R.string.enter_address_manually
        WALLET_CONNECT -> R.string.bittensor_walletconnect_hint
        else -> null
    }

    // mirror sdk BittensorWalletError* codes
    const val ERROR_WALLET = "wallet_error"
    const val ERROR_NO_CHALLENGE = "no_challenge"
    const val ERROR_INVALID_CHALLENGE = "invalid_challenge"
    const val ERROR_EXPIRED = "challenge_expired"
    const val ERROR_MESSAGE_MISMATCH = "message_mismatch"
    const val ERROR_INVALID_ADDRESS = "invalid_ss58_address"
    const val ERROR_ADDRESS_MISMATCH = "address_mismatch"
    const val ERROR_INVALID_SIGNATURE = "invalid_signature"
    const val ERROR_NOT_RETURN = "not_bittensor_return"
    const val ERROR_PURPOSE_MISMATCH = "purpose_mismatch"
    const val ERROR_UNSUPPORTED_WALLET = "unsupported_wallet"
    const val ERROR_NOT_AWAITING = "not_awaiting_wallet"

    // mirror sdk WalletAuthErrorCodeSignatureMismatch (and SnErrorCodeSignatureMismatch):
    // sign-in, network create, add-auth and POST /sn/wallet refuse a well-formed
    // signature that is not from the entered address with this server code
    const val SIGNATURE_MISMATCH = "signature_mismatch"

    // a bridge return these codes refuse belongs to another flow (or none):
    // the waiting session ignores it and keeps waiting
    val foreignReturnCodes = setOf(ERROR_NOT_RETURN, ERROR_PURPOSE_MISMATCH, ERROR_UNSUPPORTED_WALLET, ERROR_NOT_AWAITING)

    /** The message for a session refusal code. */
    @StringRes
    fun errorRes(code: String): Int = when (code) {
        ERROR_INVALID_SIGNATURE -> R.string.bittensor_error_invalid_signature
        ERROR_EXPIRED -> R.string.bittensor_error_challenge_expired
        ERROR_MESSAGE_MISMATCH -> R.string.bittensor_error_message_mismatch
        ERROR_ADDRESS_MISMATCH -> R.string.earnings_wallet_mismatch
        ERROR_INVALID_ADDRESS -> R.string.invalid_ss58_address
        else -> R.string.login_error
    }

    // mirror sdk BittensorWalletBridgeError*: the bridge page's own code for
    // the failure behind a wallet_error it hands back
    const val BRIDGE_ERROR_ADDRESS_NOT_IN_WALLET = "address_not_in_wallet"
    const val BRIDGE_ERROR_ADDRESS_MISMATCH = "address_mismatch"
    const val BRIDGE_ERROR_EXTENSION_NOT_FOUND = "extension_not_found"
    const val BRIDGE_ERROR_NO_ACCOUNT = "no_account"
    const val BRIDGE_ERROR_USER_REJECTED = "user_rejected"
    const val BRIDGE_ERROR_WALLETCONNECT_EXPIRED = "walletconnect_expired"
    const val BRIDGE_ERROR_WALLETCONNECT_UNAVAILABLE = "walletconnect_unavailable"

    // the bridge codes whose message takes the wallet's name
    private val bridgeCodesWithWalletName = setOf(
        BRIDGE_ERROR_ADDRESS_NOT_IN_WALLET,
        BRIDGE_ERROR_EXTENSION_NOT_FOUND,
        BRIDGE_ERROR_NO_ACCOUNT,
    )

    /**
     * The message for the bridge page's own code, or null for a code this app
     * does not know (the page's text is shown then).
     */
    @StringRes
    fun bridgeErrorRes(bridgeCode: String?): Int? = when (bridgeCode) {
        BRIDGE_ERROR_ADDRESS_NOT_IN_WALLET -> R.string.bittensor_error_address_not_in_wallet
        BRIDGE_ERROR_ADDRESS_MISMATCH -> R.string.earnings_wallet_mismatch
        BRIDGE_ERROR_EXTENSION_NOT_FOUND -> R.string.bittensor_error_extension_not_found
        BRIDGE_ERROR_NO_ACCOUNT -> R.string.bittensor_error_no_account
        BRIDGE_ERROR_USER_REJECTED -> R.string.bittensor_error_user_rejected
        BRIDGE_ERROR_WALLETCONNECT_EXPIRED -> R.string.bittensor_error_walletconnect_expired
        BRIDGE_ERROR_WALLETCONNECT_UNAVAILABLE -> R.string.bittensor_error_walletconnect_unavailable
        else -> null
    }

    /**
     * The text for a refusal. A wallet_error from the bridge page is shown in
     * this app's words for the page's code when the app knows it, else in the
     * page's own text; any other code by its message ([errorRes]).
     * [getString] loads a string, formatted with the wallet's name when one is
     * given.
     */
    fun refusalText(
        code: String,
        detail: String?,
        bridgeCode: String?,
        walletName: String,
        getString: (res: Int, walletName: String?) -> String,
    ): String {
        if (code == ERROR_WALLET) {
            bridgeErrorRes(bridgeCode)?.let { res ->
                return getString(res, walletName.takeIf { bridgeCode in bridgeCodesWithWalletName })
            }
            if (!detail.isNullOrEmpty()) return detail
        }
        return getString(errorRes(code), null)
    }
}

/**
 * The manual wallet to name when the server refused a signature pasted from it as one
 * from another account than the entered address ([BittensorWallets.SIGNATURE_MISMATCH]),
 * or null: the error then reads as sent. `manualWalletId` is null when the user pasted
 * nothing (the bridge signed). The server cannot say which account signed.
 */
fun bittensorSignatureMismatchWallet(code: String?, manualWalletId: String?): String? =
    if (code == BittensorWallets.SIGNATURE_MISMATCH && !manualWalletId.isNullOrEmpty()) manualWalletId else null

/** A signed challenge, ready for /auth/login, /auth/network-create or POST /sn/wallet. */
data class BittensorProof(
    val walletId: String,
    val purpose: String,
    val address: String,
    val message: String,
    val signature: String,
)

sealed class BittensorProofOutcome {
    data class Proven(val proof: BittensorProof) : BittensorProofOutcome()
    // detail: the wallet's own text for wallet_error, and bridgeCode the
    // bridge page's code for it (sdk BittensorWalletResult.BridgeErrorCode)
    data class Refused(val code: String, val detail: String? = null, val bridgeCode: String? = null) : BittensorProofOutcome()
}

/** One challenge: the SDK session, or a fake in tests. */
interface BittensorProofSession {
    val walletId: String
    val purpose: String
    // the exact message to sign
    val message: String
    // sdk BittensorWalletTransport*
    val transport: String get() = BittensorWallets.TRANSPORT_MANUAL
    fun handleSignature(address: String, signature: String, nowMillis: Long): BittensorProofOutcome
    /** The bridge page to open (browser_bridge only). */
    fun bridgeUrl(): String? = null
    fun handleBridgeReturn(uri: String, nowMillis: Long): BittensorProofOutcome =
        BittensorProofOutcome.Refused(BittensorWallets.ERROR_NOT_RETURN)
}

/** A bridge return handed to the waiting session. */
sealed class BittensorBridgeReturn {
    // no waiting session, or the return belongs to another flow
    object Ignored : BittensorBridgeReturn()
    data class Proven(val proof: BittensorProof) : BittensorBridgeReturn()
    data class Refused(
        val purpose: String,
        val code: String,
        val detail: String?,
        val bridgeCode: String? = null,
        val walletId: String = "",
    ) : BittensorBridgeReturn()
}

/**
 * The browser-bridge session waiting for its return. The return arrives in a
 * new LoginActivity (ur://bittensor-sign-message), not in the screen that
 * opened the page, so the waiting session is held here for the process.
 * A process restart drops it: the return is then ignored and the user starts
 * again (the challenge is single use and short lived anyway).
 */
class BittensorBridgeReturns {
    private var pending: BittensorProofSession? = null

    val waiting: Boolean get() = pending != null

    fun begin(session: BittensorProofSession) {
        pending = session
    }

    fun cancel() {
        pending = null
    }

    fun take(uri: String, nowMillis: Long): BittensorBridgeReturn {
        val session = pending ?: return BittensorBridgeReturn.Ignored
        return when (val outcome = session.handleBridgeReturn(uri, nowMillis)) {
            is BittensorProofOutcome.Proven -> {
                pending = null
                BittensorBridgeReturn.Proven(outcome.proof)
            }
            is BittensorProofOutcome.Refused -> {
                if (outcome.code in BittensorWallets.foreignReturnCodes) {
                    BittensorBridgeReturn.Ignored
                } else {
                    pending = null
                    BittensorBridgeReturn.Refused(session.purpose, outcome.code, outcome.detail, outcome.bridgeCode, session.walletId)
                }
            }
        }
    }

    companion object {
        val shared = BittensorBridgeReturns()
    }
}

/**
 * Whether a wallet app is installed, and whether the installed copy is the genuine
 * one: the answer of the install check (ui.login.walletInstall) for the packages
 * and signing certificates the sdk lists for a wallet.
 */
sealed class WalletInstall {
    // installed, with a signing certificate the sdk lists for the package: the only
    // case in which a wallet link may be sent to it
    data class Installed(val packageName: String) : WalletInstall()
    object NotInstalled : WalletInstall()
    // installed under a listed package name with no listed signing certificate.
    // sha256 is the digest of the certificate it has (several: comma separated)
    data class NotVerified(val packageName: String, val sha256: String) : WalletInstall()

    /** One line for the sdk log and a debug build's panel. Nothing in it is a secret. */
    fun describe(): String = when (this) {
        is Installed -> "verified pkg=$packageName"
        is NotVerified -> "not-verified pkg=$packageName sha256=$sha256"
        NotInstalled -> "not-installed"
    }
}

/**
 * A chooser row that opens a wallet app (sdk BittensorWalletChoice with the
 * transport wallet_app), with the answer of the install check made when the chooser
 * was built.
 */
data class BittensorWalletApp(
    val walletId: String,
    val install: WalletInstall,
    // a debug build's switch, never set in a release build: the tester goes on with a
    // copy of the wallet that is installed under the listed package name and could
    // not be verified
    val openUnverified: Boolean = false,
) {
    /** The only package a wallet link of this row may be sent to; null = the row is disabled. */
    val packageName: String?
        get() = when (install) {
            is WalletInstall.Installed -> install.packageName
            is WalletInstall.NotVerified -> if (openUnverified) install.packageName else null
            WalletInstall.NotInstalled -> null
        }
}

/**
 * One connection to a wallet app that the app holds itself (sdk
 * BittensorWalletConnect), or a fake in tests: it pairs with the wallet, has it sign
 * the server's challenge and hands the proof out once. The wallet link and the proof
 * are secrets: opened or used, never logged, never put into a stage.
 */
interface BittensorWalletConnection {
    val walletId: String
    // sdk BittensorWalletConnectState*
    fun state(): String
    // of the current or the last sign
    fun purpose(): String
    // the wallet account in use ("" until the wallet approved the connection)
    fun address(): String
    // the relay is reached and what the wallet sent has been read
    fun connected(): Boolean
    /** Asks the wallet for one proof. Throws when the connection cannot take the request. */
    fun sign(purpose: String, expectedAddress: String)
    /** The link to open without a tap: at most once per waiting state; "" = nothing to open. */
    fun takeWalletLink(): String
    /** The link behind the "Open wallet" button; "" = nothing to open. */
    fun walletLink(): String
    /** The proof of the last sign, handed out exactly once. */
    fun takeProof(): BittensorProof?
    /** Why the last sign failed or the connection ended; null otherwise. */
    fun failure(): BittensorProofOutcome.Refused?
    fun setForeground(foreground: Boolean)
    fun close()
    // called on the main thread whenever the connection changed: a wake-up, nothing
    // more ([BittensorProofFlow.wake] reads the details)
    var onChanged: (() -> Unit)?
    // a debug build: what the connection was told to send (option words, error codes)
    val debugSetup: String get() = ""
    // a debug build: the sdk's trace of the connection, oldest line first. By the
    // sdk's construction it holds no key, link, challenge, signature or wallet text.
    fun traceLines(): List<String> = emptyList()
}

/** What a debug build shows of the login screen's wallet app connection. No secret is in it. */
data class BittensorWalletDebug(
    // the install check of each wallet-app row
    val installs: List<String> = emptyList(),
    // what the connection was told to send, and which copy of the wallet it opens
    val setup: String = "",
    val state: String = "",
    val connected: Boolean = false,
    // the app's own lines: each start of the wallet
    val notes: List<String> = emptyList(),
    // the sdk's trace of the connection, oldest line first
    val trace: List<String> = emptyList(),
)

/**
 * The wallet app connection of the login screen, held for the process. The
 * screen's flow is remembered in the composition and is lost when the activity is
 * recreated, while the connection has to live until its proof is used: a new flow
 * attaches to the held connection again ([attach]). The mark that a wallet link was
 * opened and the proof live in the sdk object, so attaching again neither opens the
 * wallet a second time nor hands a proof out twice.
 *
 * One slot: the login screen is the only owner in this build. A process restart
 * drops it, as it drops the connection (nothing of it is stored).
 *
 * [open], [close] and the debug state are safe from any thread; the rest is called
 * on the main thread.
 */
class BittensorWalletConnections {
    /** The held connection, and the only package its wallet links may be sent to. */
    class Slot(val connection: BittensorWalletConnection, val packageName: String)

    @Volatile
    private var slot: Slot? = null

    private val _debug = MutableStateFlow(BittensorWalletDebug())
    val debug: StateFlow<BittensorWalletDebug> = _debug.asStateFlow()

    /** A connection is held: its sign-in is still running. */
    val waiting: Boolean get() = slot != null

    fun get(): Slot? = slot

    /** Holds a connection in place of the one held before, which is closed. */
    @Synchronized
    fun open(connection: BittensorWalletConnection, packageName: String, setup: String) {
        closeHeld()
        slot = Slot(connection, packageName)
        _debug.value = BittensorWalletDebug(installs = _debug.value.installs, setup = setup)
        read(connection)
    }

    /** The wake-ups of the held connection go to `onChanged`, and to no flow that attached before. */
    fun attach(onChanged: () -> Unit) {
        slot?.connection?.onChanged = onChanged
    }

    /** Ends the held connection, if there is one. What a debug build shows of it stays. */
    @Synchronized
    fun close() {
        closeHeld()
    }

    private fun closeHeld() {
        val held = slot ?: return
        slot = null
        held.connection.onChanged = null
        held.connection.close()
        // its trace stays readable, and now ends with the close
        read(held.connection)
    }

    /** A debug build's panel: the state and the trace as they are now. */
    @Synchronized
    fun refreshDebug() {
        slot?.let { read(it.connection) }
    }

    /** A debug build's panel: the install check of each wallet-app row. */
    @Synchronized
    fun setInstalls(installs: List<String>) {
        _debug.value = _debug.value.copy(installs = installs)
    }

    /** A debug build's panel: a line of the app's own. Never a link. */
    @Synchronized
    fun note(line: String) {
        _debug.value = _debug.value.copy(notes = (_debug.value.notes + line).takeLast(MAX_NOTES))
    }

    private fun read(connection: BittensorWalletConnection) {
        _debug.value = _debug.value.copy(
            state = connection.state(),
            connected = connection.connected(),
            trace = connection.traceLines(),
        )
    }

    companion object {
        private const val MAX_NOTES = 32
        val shared = BittensorWalletConnections()
    }
}

/** The UTC wall clock of a time, as the lines of the sdk's trace begin: 15:04:05.000. */
fun bittensorTraceClock(millis: Long): String {
    val day = 86_400_000L
    val ms = ((millis % day) + day) % day
    return (ms / 3_600_000L).toString().padStart(2, '0') + ":" +
        (ms / 60_000L % 60L).toString().padStart(2, '0') + ":" +
        (ms / 1000L % 60L).toString().padStart(2, '0') + "." +
        (ms % 1000L).toString().padStart(3, '0')
}

/** A session to start: the wallet, why, and the address the challenge is bound to. */
data class BittensorProofRequest(
    val walletId: String,
    val purpose: String,
    val expectedAddress: String?,
    // the wallet is opened as an app, through a connection the app holds
    // ([BittensorWalletConnection]), instead of a session
    val walletApp: Boolean = false,
    // wallet app: the only package a wallet link may be sent to (the install check's)
    val walletPackage: String? = null,
)

sealed class BittensorProofStage {
    object Hidden : BittensorProofStage()
    // errorText: why a wallet app connection ended, in words (they can name the wallet)
    data class Choosing(@StringRes val errorRes: Int? = null, val errorText: String? = null) : BittensorProofStage()
    data class Loading(val request: BittensorProofRequest) : BittensorProofStage()
    // the bridge page is open in the browser; the return comes back through LoginActivity
    data class AwaitingBrowser(val walletId: String) : BittensorProofStage()
    // a wallet app has the connection request: the user approves it there
    data class AwaitingWalletApproval(val walletId: String) : BittensorProofStage()
    // a wallet app has the sign request, or gets it next: the user approves it there
    data class AwaitingWalletSignature(
        val walletId: String,
        val address: String,
        val purpose: String,
    ) : BittensorProofStage()
    data class Signing(
        val session: BittensorProofSession,
        val address: String,
        val signature: String,
        @StringRes val errorRes: Int? = null,
    ) : BittensorProofStage()
}

/**
 * What the caller does after a wake-up of the wallet app connection
 * ([BittensorProofFlow.wake]). Not data classes on purpose: a link or a proof never
 * ends up in a printed text.
 */
sealed class BittensorWalletAction {
    object None : BittensorWalletAction()
    // start the wallet app: `link`, to `packageName` and to nothing else
    class Open(val link: String, val packageName: String) : BittensorWalletAction()
    class Proven(val proof: BittensorProof) : BittensorWalletAction()
    // the connection failed or ended and is closed. The chooser is up with a general
    // line, which the caller replaces with the text for `refused`
    class Failed(val walletId: String, val refused: BittensorProofOutcome.Refused) : BittensorWalletAction()
}

/**
 * The chooser + manual proof sheets. [open] shows the chooser; [choose]
 * returns the session to start (the caller fetches the challenge); then
 * [sessionReady] / [sessionFailed]; then [submit] returns the proof once the
 * session accepts the pasted answer. [openForWallet] skips the chooser (the
 * create-network second signature reuses the wallet that signed in).
 *
 * A flow that was given [BittensorWalletConnections] also opens wallet apps:
 * [choose] then returns a request marked `walletApp`, the caller starts a
 * connection for it ([walletStarted]), and [wake] moves the sheets with the
 * connection's states until it hands the proof out.
 */
class BittensorProofFlow(
    private val bridgeReturns: BittensorBridgeReturns = BittensorBridgeReturns.shared,
    // where this flow's wallet app connection is held. Null: the flow opens no wallet
    // app, and every chooser row is as before (the add sheet, Earnings)
    private val walletConnections: BittensorWalletConnections? = null,
    // the wallet-app row of a wallet id with its install check; null = no such row
    private val walletAppFor: (walletId: String) -> BittensorWalletApp? = { null },
    private val nowMillis: () -> Long,
) {
    private val _stage = MutableStateFlow<BittensorProofStage>(BittensorProofStage.Hidden)
    val stage: StateFlow<BittensorProofStage> = _stage.asStateFlow()

    // the wallet-app rows of the chooser by wallet id, checked when the chooser was built
    private val _walletApps = MutableStateFlow<Map<String, BittensorWalletApp>>(emptyMap())
    val walletApps: StateFlow<Map<String, BittensorWalletApp>> = _walletApps.asStateFlow()

    /** This flow opens wallet apps (the login screen). */
    val opensWalletApps: Boolean get() = walletConnections != null

    private var purpose: String = BittensorWallets.PURPOSE_LOGIN
    private var expectedAddress: String? = null

    fun open(purpose: String, expectedAddress: String? = null) {
        this.purpose = purpose
        this.expectedAddress = expectedAddress?.trim()?.takeIf { it.isNotEmpty() }
        // a new attempt: a wallet app connection held from an earlier one is over
        walletConnections?.close()
        refreshWalletApps()
        _stage.value = BittensorProofStage.Choosing()
    }

    /**
     * Checks which wallet apps are installed: when the chooser is built, when the app
     * comes back to it, and when a debug build's switch changed.
     */
    fun refreshWalletApps() {
        val connections = walletConnections ?: return
        val apps = BittensorWallets.walletIds.mapNotNull { walletId -> walletAppFor(walletId) }
        _walletApps.value = apps.associateBy { it.walletId }
        connections.setInstalls(apps.map { it.walletId + " " + it.install.describe() })
    }

    /**
     * The session to start for the chosen wallet, or null when the chooser is not up.
     * A wallet-app row gives a request marked `walletApp` with the package of its
     * install check; a row whose wallet is not installed, or not verified, gives none.
     */
    fun choose(walletId: String): BittensorProofRequest? {
        if (_stage.value !is BittensorProofStage.Choosing || walletId !in BittensorWallets.walletIds) {
            return null
        }
        val walletApp = _walletApps.value[walletId]
        val request = if (walletApp != null) {
            val packageName = walletApp.packageName ?: return null
            BittensorProofRequest(walletId, purpose, expectedAddress, walletApp = true, walletPackage = packageName)
        } else {
            BittensorProofRequest(walletId, purpose, expectedAddress)
        }
        _stage.value = BittensorProofStage.Loading(request)
        return request
    }

    fun openForWallet(walletId: String, purpose: String, expectedAddress: String?): BittensorProofRequest? {
        open(purpose, expectedAddress)
        return choose(walletId)
    }

    /**
     * The challenge arrived; a stale session (the user moved on) is dropped.
     * Returns the bridge page to open for a browser-bridge session (it now
     * waits in [BittensorBridgeReturns]), else null.
     */
    fun sessionReady(request: BittensorProofRequest, session: BittensorProofSession): String? {
        val s = _stage.value
        if (s !is BittensorProofStage.Loading || s.request != request) {
            return null
        }
        if (session.transport == BittensorWallets.TRANSPORT_BROWSER_BRIDGE) {
            val url = session.bridgeUrl()
            if (url == null) {
                _stage.value = BittensorProofStage.Choosing(R.string.login_error)
                return null
            }
            bridgeReturns.begin(session)
            _stage.value = BittensorProofStage.AwaitingBrowser(session.walletId)
            return url
        }
        _stage.value = BittensorProofStage.Signing(
            session = session,
            address = request.expectedAddress ?: "",
            signature = "",
        )
        return null
    }

    /** The browser could not be opened. */
    fun browserFailed() {
        if (_stage.value !is BittensorProofStage.AwaitingBrowser) {
            return
        }
        bridgeReturns.cancel()
        _stage.value = BittensorProofStage.Choosing(R.string.login_error)
    }

    /**
     * Back on screen: a bridge that already returned (or was dropped) is done waiting.
     * A wallet app connection is told that the app is in front again, which makes it
     * read what the wallet sent meanwhile; the caller wakes it next ([wake]).
     */
    fun onResumed() {
        if (_stage.value is BittensorProofStage.AwaitingBrowser && !bridgeReturns.waiting) {
            _stage.value = BittensorProofStage.Hidden
        }
        walletConnections?.get()?.connection?.setForeground(true)
        if (_stage.value is BittensorProofStage.Choosing) {
            // a wallet may have been installed meanwhile
            refreshWalletApps()
        }
    }

    /** The screen left the front (the user is in the wallet, or elsewhere). */
    fun onStopped() {
        walletConnections?.get()?.connection?.setForeground(false)
    }

    /**
     * The connection of a wallet-app request has begun to sign: it is held for the
     * process, in place of the one held before. A stale one (the user moved on) is
     * closed; false then.
     */
    fun walletStarted(request: BittensorProofRequest, connection: BittensorWalletConnection): Boolean {
        val s = _stage.value
        val packageName = request.walletPackage
        if (walletConnections == null || packageName == null ||
            s !is BittensorProofStage.Loading || s.request != request
        ) {
            connection.close()
            return false
        }
        val install = _walletApps.value[request.walletId]?.install?.describe() ?: "pkg=$packageName"
        walletConnections.open(connection, packageName, (connection.debugSetup + " " + install).trim())
        return true
    }

    /** The wake-ups of the held wallet app connection go to `onChanged` from now on. */
    fun attachWallet(onChanged: () -> Unit) {
        walletConnections?.attach(onChanged)
    }

    /**
     * The wake-up routine of the wallet app connection: on every call of its listener
     * and whenever the screen is in front again. It moves the sheets with the
     * connection's state and opens nothing itself: it returns the wallet link for the
     * caller to start, or the proof, or that the connection failed. `active` = the
     * screen is in front: a proof is taken only then.
     */
    fun wake(active: Boolean): BittensorWalletAction {
        val connections = walletConnections ?: return BittensorWalletAction.None
        val slot = connections.get() ?: return BittensorWalletAction.None
        val connection = slot.connection
        // first, as the sdk asks: it hands a link out at most once per waiting state,
        // and only while the app is in front and what the wallet sent has been read
        val link = connection.takeWalletLink()
        val state = connection.state()
        connections.refreshDebug()
        when (state) {
            BittensorWallets.CONNECT_AWAITING_APPROVAL -> {
                _stage.value = BittensorProofStage.AwaitingWalletApproval(connection.walletId)
            }
            BittensorWallets.CONNECT_AWAITING_SIGNATURE -> {
                _stage.value = BittensorProofStage.AwaitingWalletSignature(
                    connection.walletId,
                    connection.address(),
                    connection.purpose(),
                )
            }
            BittensorWallets.CONNECT_CONNECTING -> {
                // The sdk is working: the row spins before the wallet was opened, and the
                // waiting sheet stays up after it. Only a flow made anew (the activity
                // was recreated) has nothing up yet.
                if (_stage.value is BittensorProofStage.Hidden) {
                    val address = connection.address()
                    _stage.value = if (address.isEmpty()) {
                        BittensorProofStage.AwaitingWalletApproval(connection.walletId)
                    } else {
                        BittensorProofStage.AwaitingWalletSignature(connection.walletId, address, connection.purpose())
                    }
                }
            }
            BittensorWallets.CONNECT_SIGNED -> {
                if (active) {
                    val proof = connection.takeProof()
                    if (proof != null) {
                        // the sheets close; the caller signs in with it, or asks for one more
                        _stage.value = BittensorProofStage.Hidden
                        return BittensorWalletAction.Proven(proof)
                    }
                }
            }
            BittensorWallets.CONNECT_FAILED, BittensorWallets.CONNECT_CLOSED -> {
                val refused = connection.failure()
                val s = _stage.value
                val shown = s is BittensorProofStage.AwaitingWalletApproval ||
                    s is BittensorProofStage.AwaitingWalletSignature ||
                    (s is BittensorProofStage.Loading && s.request.walletApp)
                // every attempt gets a connection of its own
                connections.close()
                if (refused == null && state == BittensorWallets.CONNECT_CLOSED && !shown) {
                    // over with nothing to tell: its work was done
                    return BittensorWalletAction.None
                }
                _stage.value = BittensorProofStage.Choosing(R.string.login_error)
                return BittensorWalletAction.Failed(
                    connection.walletId,
                    refused ?: BittensorProofOutcome.Refused(BittensorWallets.ERROR_WALLET),
                )
            }
        }
        return if (link.isEmpty()) BittensorWalletAction.None else BittensorWalletAction.Open(link, slot.packageName)
    }

    /** The "Open wallet" button: the link of what is pending, for the package of the install check. */
    fun walletButton(): BittensorWalletAction {
        val slot = walletConnections?.get() ?: return BittensorWalletAction.None
        val link = slot.connection.walletLink()
        return if (link.isEmpty()) BittensorWalletAction.None else BittensorWalletAction.Open(link, slot.packageName)
    }

    /**
     * The wallet app was started, or could not be although its row was enabled. A
     * failed start while the connection waits for its approval: nothing can go on, so
     * it is closed and the chooser is up again; the wallet id is returned, for the
     * text. While it waits for a signature nothing changes: the button stays and the
     * user can open the wallet by hand. A debug build notes the start.
     */
    fun walletOpened(opened: Boolean): String? {
        val connections = walletConnections ?: return null
        connections.note(bittensorTraceClock(nowMillis()) + (if (opened) " open ok" else " open failed"))
        if (opened) {
            return null
        }
        val slot = connections.get() ?: return null
        if (slot.connection.state() != BittensorWallets.CONNECT_AWAITING_APPROVAL) {
            return null
        }
        connections.close()
        _stage.value = BittensorProofStage.Choosing(R.string.login_error)
        return slot.connection.walletId
    }

    /**
     * Asks the held wallet app connection for one more proof (the create-network
     * second signature): the wallet is not asked to approve a connection again. False
     * when no connection is held or it cannot take the request; the caller then starts
     * a new one ([replaceWallet]).
     */
    fun signOnWallet(purpose: String, address: String): Boolean {
        val slot = walletConnections?.get() ?: return false
        try {
            slot.connection.sign(purpose, address)
        } catch (e: Exception) {
            return false
        }
        this.purpose = purpose
        this.expectedAddress = address.trim().takeIf { it.isNotEmpty() }
        _stage.value = BittensorProofStage.AwaitingWalletSignature(slot.connection.walletId, address, purpose)
        return true
    }

    /**
     * The request for a new connection to a wallet app, in place of the held one,
     * which is closed: to the package that one was opened with, else to the package
     * of a new install check. Null when the wallet cannot be opened.
     */
    fun replaceWallet(walletId: String, purpose: String, address: String): BittensorProofRequest? {
        val connections = walletConnections ?: return null
        val held = connections.get()
        connections.close()
        var packageName = held?.packageName
        if (packageName == null) {
            refreshWalletApps()
            packageName = _walletApps.value[walletId]?.packageName
        }
        if (packageName == null) {
            return null
        }
        this.purpose = purpose
        this.expectedAddress = address.trim().takeIf { it.isNotEmpty() }
        val request = BittensorProofRequest(walletId, purpose, expectedAddress, walletApp = true, walletPackage = packageName)
        _stage.value = BittensorProofStage.Loading(request)
        return request
    }

    /** The wallet app connection is over: the chooser is up with the reason in words. */
    fun walletFailed(text: String) {
        walletConnections?.close()
        _stage.value = BittensorProofStage.Choosing(errorText = text)
    }

    /**
     * Ends the wallet app connection of this flow, if one is held; the sheets are not
     * touched. Safe from any thread.
     */
    fun closeWallet() {
        walletConnections?.close()
    }

    /** A debug build's panel: a line of the app's own about the wallet app. Never a link. */
    fun walletNote(line: String) {
        walletConnections?.note(bittensorTraceClock(nowMillis()) + " " + line)
    }

    fun sessionFailed(request: BittensorProofRequest) {
        val s = _stage.value
        if (s !is BittensorProofStage.Loading || s.request != request) {
            return
        }
        _stage.value = BittensorProofStage.Choosing(R.string.login_error)
    }

    fun updateAddress(address: String) {
        val s = _stage.value as? BittensorProofStage.Signing ?: return
        _stage.value = s.copy(address = address, errorRes = null)
    }

    fun updateSignature(signature: String) {
        val s = _stage.value as? BittensorProofStage.Signing ?: return
        _stage.value = s.copy(signature = signature, errorRes = null)
    }

    /** The proof when the session accepts the answer (the sheets close); else null with the reason shown. */
    fun submit(): BittensorProof? {
        val s = _stage.value as? BittensorProofStage.Signing ?: return null
        return when (val outcome = s.session.handleSignature(s.address, s.signature, nowMillis())) {
            is BittensorProofOutcome.Proven -> {
                _stage.value = BittensorProofStage.Hidden
                outcome.proof
            }
            is BittensorProofOutcome.Refused -> {
                _stage.value = s.copy(errorRes = BittensorWallets.errorRes(outcome.code))
                null
            }
        }
    }

    fun dismiss() {
        if (_stage.value is BittensorProofStage.AwaitingBrowser) {
            bridgeReturns.cancel()
        }
        // its own wallet app connection only: a flow that opens no wallet app holds none
        walletConnections?.close()
        _stage.value = BittensorProofStage.Hidden
    }
}

/** Where a proof goes next. */
enum class BittensorProofRoute { LOGIN, CREATE_NETWORK, CONNECT_WALLET, ADD_SIGN_IN }

fun bittensorProofRoute(proof: BittensorProof): BittensorProofRoute? = when (proof.purpose) {
    BittensorWallets.PURPOSE_LOGIN -> BittensorProofRoute.LOGIN
    BittensorWallets.PURPOSE_CREATE -> BittensorProofRoute.CREATE_NETWORK
    BittensorWallets.PURPOSE_CONNECT -> BittensorProofRoute.CONNECT_WALLET
    BittensorWallets.PURPOSE_ADD -> BittensorProofRoute.ADD_SIGN_IN
    else -> null
}

/** What LoginActivity does with a ur://bittensor-sign-message return. */
sealed class BittensorReturnAction {
    // no waiting session: the pre-helper return handling
    object Legacy : BittensorReturnAction()
    data class Proven(val route: BittensorProofRoute, val proof: BittensorProof) : BittensorReturnAction()
    // the text to show: BittensorWallets.refusalText
    data class Failed(
        val purpose: String,
        val code: String,
        val detail: String?,
        val bridgeCode: String? = null,
        val walletId: String = "",
    ) : BittensorReturnAction()
}

fun bittensorReturnAction(
    uri: String,
    nowMillis: Long,
    bridgeReturns: BittensorBridgeReturns = BittensorBridgeReturns.shared,
): BittensorReturnAction = when (val r = bridgeReturns.take(uri, nowMillis)) {
    BittensorBridgeReturn.Ignored -> BittensorReturnAction.Legacy
    is BittensorBridgeReturn.Refused -> BittensorReturnAction.Failed(r.purpose, r.code, r.detail, r.bridgeCode, r.walletId)
    is BittensorBridgeReturn.Proven -> bittensorProofRoute(r.proof)
        ?.let { BittensorReturnAction.Proven(it, r.proof) }
        ?: BittensorReturnAction.Failed(r.proof.purpose, BittensorWallets.ERROR_PURPOSE_MISMATCH, null)
}
