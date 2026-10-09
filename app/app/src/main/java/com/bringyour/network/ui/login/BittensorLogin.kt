package com.bringyour.network.ui.login

import android.util.Log
import com.bringyour.network.ui.wallet.BittensorProof
import com.bringyour.network.ui.wallet.BittensorProofFlow
import com.bringyour.network.ui.wallet.BittensorProofOutcome
import com.bringyour.network.ui.wallet.BittensorProofRequest
import com.bringyour.network.ui.wallet.BittensorProofRoute
import com.bringyour.network.ui.wallet.BittensorWalletAction
import com.bringyour.network.ui.wallet.BittensorWalletStart
import com.bringyour.network.ui.wallet.BittensorWallets
import com.bringyour.network.ui.wallet.bittensorProofRoute
import com.bringyour.network.ui.wallet.bittensorSignatureMismatchWallet
import com.bringyour.network.ui.wallet.startBittensorProofSession
import com.bringyour.sdk.Api
import com.bringyour.sdk.AuthLoginArgs
import com.bringyour.sdk.WalletAuthArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "BittensorLogin"

/** The wallet_auth blockchain for Bittensor (sdk TAO). */
const val BITTENSOR_BLOCKCHAIN = "TAO"

/**
 * The create-network bundle for a create-purpose proof pasted on the manual sheet: it
 * names the wallet for a refusal of a signature from another account.
 */
fun bittensorCreateBundle(proof: BittensorProof): WalletCreateBundle = WalletCreateBundle(
    blockchain = BITTENSOR_BLOCKCHAIN,
    publicKey = proof.address,
    signedMessage = proof.message,
    signature = proof.signature,
    manualWalletId = proof.walletId,
)

/**
 * The create-network bundle for a create-purpose proof a wallet app signed. It names
 * no wallet: nothing was pasted, and the "paste the signature again" wording is for a
 * manual row only.
 */
fun bittensorWalletCreateBundle(proof: BittensorProof): WalletCreateBundle = WalletCreateBundle(
    blockchain = BITTENSOR_BLOCKCHAIN,
    publicKey = proof.address,
    signedMessage = proof.message,
    signature = proof.signature,
)

/** What a /auth/login answer to a Bittensor proof leads to. */
sealed class BittensorLoginNext {
    data class SignedIn(val networkJwt: String) : BittensorLoginNext()
    // the wallet has no network yet: sign a fresh challenge, bound to it, to create one
    data class CreateNetwork(val walletId: String, val address: String) : BittensorLoginNext()
    data class Failed(val message: String?) : BittensorLoginNext()
    // the pasted signature is not from the entered address: sign again in this wallet
    // (bittensor_error_signature_mismatch)
    data class SignatureMismatch(val walletId: String) : BittensorLoginNext()
}

/**
 * The next step after /auth/login answered a proof pasted on the manual sheet.
 * `errorCode` is the result error's code: the server's signature_mismatch names the
 * wallet the proof was pasted from.
 */
fun bittensorLoginNext(
    proof: BittensorProof,
    networkJwt: String?,
    unlinkedWallet: Boolean,
    errorMessage: String?,
    errorCode: String? = null,
): BittensorLoginNext = when {
    bittensorSignatureMismatchWallet(errorCode, proof.walletId) != null ->
        BittensorLoginNext.SignatureMismatch(proof.walletId)
    errorMessage != null -> BittensorLoginNext.Failed(errorMessage)
    !networkJwt.isNullOrEmpty() -> BittensorLoginNext.SignedIn(networkJwt)
    unlinkedWallet -> BittensorLoginNext.CreateNetwork(proof.walletId, proof.address)
    else -> BittensorLoginNext.Failed(null)
}

/**
 * "Sign in with Bittensor" on every flavor's login screen: the wallet
 * chooser and manual proof ([BittensorProofFlow]), then /auth/login with the
 * proof. A wallet with no network signs a second, address-bound challenge
 * (purpose create) and continues to the create-network screen.
 *
 * A wallet the sdk lists as an app (Talisman) is opened directly instead of the
 * manual proof, when the screen gives the flow its wallet-app rows and this
 * controller the `openWallet` and `connectWallet` it needs: the app holds a
 * connection to the wallet, the wallet signs, and the proof takes the same
 * /auth/login path. The second signature for a new network is asked on the
 * same connection.
 *
 * Callbacks run on the main thread except `onNetworkJwt`, which runs on the
 * api callback thread (as the other wallet logins do).
 */
class BittensorLoginController(
    val flow: BittensorProofFlow,
    private val scope: CoroutineScope,
    private val api: () -> Api?,
    private val setLoginError: (String?) -> Unit,
    private val setInProgress: (Boolean) -> Unit,
    private val defaultError: () -> String,
    private val onNetworkJwt: (String) -> Unit,
    private val onCreateNetwork: (WalletCreateBundle) -> Unit,
    // opens a browser-bridge page (WalletConnect); false when no browser opened
    private val openUrl: (String) -> Boolean = { false },
    // the line for a signature pasted from this wallet that is not from the entered
    // address (bittensorSignatureMismatchText)
    private val signatureMismatchText: (walletId: String) -> String = { defaultError() },
    // starts a wallet app: its link, to the package of the install check and to
    // nothing else (launchBittensorWallet); false when it did not start
    private val openWallet: (link: String, packageName: String) -> Boolean = { _, _ -> false },
    // a connection to a wallet app that has begun to sign (startBittensorWalletConnection)
    private val connectWallet: (api: Api, request: BittensorProofRequest) -> BittensorWalletStart =
        { _, _ -> BittensorWalletStart(null) },
    // the text for a wallet app connection that failed (bittensorWalletFailureText)
    private val walletFailureText: (walletId: String, refused: BittensorProofOutcome.Refused) -> String =
        { _, _ -> defaultError() },
    // the text for a wallet app that could not be started (bittensorWalletOpenFailedText)
    private val walletOpenFailedText: (walletId: String) -> String = { defaultError() },
    // a debug build keeps the trace of the wallet connection, when a proof is taken
    // and when a failure is shown (copyBittensorWalletTrace)
    private val copyWalletTrace: () -> Unit = {},
) {
    // the login screen is in front: the proof of a wallet app is taken only then
    private var resumed = false

    fun start() {
        setLoginError(null)
        flow.open(BittensorWallets.PURPOSE_LOGIN)
    }

    /**
     * Back on the login screen: from the browser, or from a wallet app. A wallet app
     * connection of this screen is told, and woken. It is held for the process, so
     * this is also how a controller made after the activity was recreated finds it.
     *
     * A wallet can miss the connection request on the first open of its link (its
     * page was not ready yet): on the foreground edge a connection that still waits
     * for its approval is opened again, exactly as the sheet's button does
     * ([BittensorProofFlow.walletReopen]). Never on the wake-up's own open of this
     * resume, and never more than once per resume.
     */
    fun onResumed() {
        // the foreground edge: the screen comes back only from having left the front
        val edge = !resumed
        resumed = true
        flow.onResumed()
        flow.attachWallet { wakeWallet() }
        val woke = wakeWallet()
        if (edge && woke !is BittensorWalletAction.Open) {
            val reopen = flow.walletReopen()
            if (reopen is BittensorWalletAction.Open) {
                openWalletApp(reopen)
            }
        }
    }

    /** The login screen left the front: the user is in the wallet, or elsewhere. */
    fun onStopped() {
        resumed = false
        flow.onStopped()
    }

    fun choose(walletId: String) {
        flow.choose(walletId)?.let { startSession(it) }
    }

    /** The "Open wallet" button of the sheet that waits for a wallet app. */
    fun openWalletButton() {
        val action = flow.walletButton()
        if (action is BittensorWalletAction.Open) {
            openWalletApp(action)
        }
    }

    fun submit() {
        val proof = flow.submit() ?: return
        when (bittensorProofRoute(proof)) {
            BittensorProofRoute.LOGIN -> login(proof)
            BittensorProofRoute.CREATE_NETWORK -> onCreateNetwork(bittensorCreateBundle(proof))
            else -> setLoginError(defaultError())
        }
    }

    private fun startSession(request: BittensorProofRequest) {
        if (request.walletApp) {
            startWallet(request)
            return
        }
        scope.launch {
            val api = api()
            if (api == null) {
                flow.sessionFailed(request)
                return@launch
            }
            startBittensorProofSession(api, request)
                .onSuccess { session ->
                    flow.sessionReady(request, session)?.let { url ->
                        if (!openUrl(url)) {
                            flow.browserFailed()
                        }
                    }
                }
                .onFailure {
                    Log.i(TAG, "challenge: ${it.message}")
                    flow.sessionFailed(request)
                }
        }
    }

    /**
     * A wallet-app row: a new connection to the wallet signs. It is held for the
     * process until its proof is used, and woken on every change of its state. The
     * wallet itself is opened by the wake-up, with the link the connection hands out.
     */
    private fun startWallet(request: BittensorProofRequest) {
        val api = api()
        if (api == null) {
            flow.sessionFailed(request)
            return
        }
        val start = connectWallet(api, request)
        val connection = start.connection
        if (connection == null) {
            // only the code of the refusal: the text behind it can name an address
            Log.i(TAG, "wallet connection: ${start.errorCode}")
            flow.walletNote("connect failed " + start.errorCode)
            flow.sessionFailed(request)
            return
        }
        if (!flow.walletStarted(request, connection)) {
            return
        }
        if (!resumed) {
            connection.setForeground(false)
        }
        flow.attachWallet { wakeWallet() }
        wakeWallet()
    }

    /**
     * The wake-up of the wallet app connection, on the main thread: on every call of
     * its listener and whenever the screen is in front again. Returns what it did.
     */
    private fun wakeWallet(): BittensorWalletAction {
        val action = flow.wake(resumed)
        when (action) {
            BittensorWalletAction.None -> {}
            is BittensorWalletAction.Open -> openWalletApp(action)
            is BittensorWalletAction.Proven -> walletProven(action.proof)
            is BittensorWalletAction.Failed -> {
                // a debug build keeps the trace of an attempt that failed
                copyWalletTrace()
                flow.walletFailed(walletFailureText(action.walletId, action.refused))
            }
        }
        return action
    }

    /** Starts the wallet app with a link of its connection, for the package of the install check. */
    private fun openWalletApp(action: BittensorWalletAction.Open) {
        val opened = openWallet(action.link, action.packageName)
        flow.walletOpened(opened)?.let { walletId ->
            // it did not start while its approval was awaited: the connection is closed
            copyWalletTrace()
            flow.walletFailed(walletOpenFailedText(walletId))
        }
    }

    /** The proof a wallet app signed. Only the purposes this screen serves are used. */
    private fun walletProven(proof: BittensorProof) {
        // a debug build keeps the trace of a sign-in that worked: it leaves this screen
        copyWalletTrace()
        when (bittensorProofRoute(proof)) {
            BittensorProofRoute.LOGIN -> login(proof, walletApp = true)
            BittensorProofRoute.CREATE_NETWORK -> {
                // the connection has done its work
                flow.closeWallet()
                onCreateNetwork(bittensorWalletCreateBundle(proof))
            }
            else -> {
                flow.closeWallet()
                setLoginError(defaultError())
            }
        }
    }

    /**
     * The wallet a wallet app signed in with has no network yet: it signs a second
     * challenge, bound to its address, on the SAME connection, so the wallet does not
     * ask to approve a connection again. A connection that cannot take the request
     * (it ended meanwhile) is replaced once.
     */
    private fun signCreateOnWallet(walletId: String, address: String) {
        if (flow.signOnWallet(BittensorWallets.PURPOSE_CREATE, address)) {
            flow.attachWallet { wakeWallet() }
            wakeWallet()
            return
        }
        val request = flow.replaceWallet(walletId, BittensorWallets.PURPOSE_CREATE, address)
        if (request == null) {
            setLoginError(defaultError())
            return
        }
        startSession(request)
    }

    // walletApp: the proof is a wallet app's, and its connection is still held
    private fun login(proof: BittensorProof, walletApp: Boolean = false) {
        val api = api()
        if (api == null) {
            if (walletApp) {
                flow.closeWallet()
            }
            setLoginError(defaultError())
            return
        }
        setInProgress(true)
        val walletAuth = WalletAuthArgs()
        walletAuth.blockchain = BITTENSOR_BLOCKCHAIN
        walletAuth.publicKey = proof.address
        walletAuth.message = proof.message
        walletAuth.signature = proof.signature
        val args = AuthLoginArgs()
        args.walletAuth = walletAuth
        // a signature from another account than the address comes back as
        // result.error.code (a 401 error otherwise)
        args.resultErrors = true
        api.authLogin(args) { result, err ->
            val next = bittensorLoginNext(
                proof = proof,
                networkJwt = result?.network?.byJwt,
                unlinkedWallet = result?.walletAuth != null,
                errorMessage = err?.message ?: result?.error?.message,
                errorCode = result?.error?.code,
            )
            if (next is BittensorLoginNext.SignedIn) {
                if (walletApp) {
                    // The connection has done its work. Closed from this thread: the
                    // sign-in leaves the screen, and its scope may not run any more.
                    flow.closeWallet()
                }
                onNetworkJwt(next.networkJwt)
                return@authLogin
            }
            scope.launch {
                setInProgress(false)
                when (next) {
                    is BittensorLoginNext.CreateNetwork -> {
                        if (walletApp) {
                            signCreateOnWallet(next.walletId, next.address)
                        } else {
                            flow.openForWallet(next.walletId, BittensorWallets.PURPOSE_CREATE, next.address)
                                ?.let { startSession(it) }
                        }
                    }
                    is BittensorLoginNext.Failed -> {
                        if (walletApp) {
                            flow.closeWallet()
                        }
                        setLoginError(next.message ?: defaultError())
                    }
                    is BittensorLoginNext.SignatureMismatch -> {
                        if (walletApp) {
                            // nothing was pasted: the wording for a manual row does not fit
                            flow.closeWallet()
                            setLoginError(defaultError())
                        } else {
                            setLoginError(signatureMismatchText(next.walletId))
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
