package com.bringyour.network.ui.login

import com.bringyour.network.ui.wallet.BittensorProof
import com.bringyour.network.ui.wallet.BittensorProofFlow
import com.bringyour.network.ui.wallet.BittensorProofOutcome
import com.bringyour.network.ui.wallet.BittensorWalletApp
import com.bringyour.network.ui.wallet.BittensorWalletConnection
import com.bringyour.network.ui.wallet.BittensorWalletConnections
import com.bringyour.network.ui.wallet.BittensorWallets
import com.bringyour.network.ui.wallet.WalletInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a /auth/login answer to a Bittensor proof leads to (UPGRADE.md 4.6). */
class BittensorLoginTest {

    private val proof = BittensorProof(
        walletId = "taocom",
        purpose = "login",
        address = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY",
        message = "Sign in to URnetwork\nChallenge: abc\nTimestamp: 1757340000",
        signature = "0x" + "ab".repeat(64),
    )

    @Test
    fun `a bound wallet signs in`() {
        assertEquals(BittensorLoginNext.SignedIn("jwt"), bittensorLoginNext(proof, "jwt", false, null))
    }

    @Test
    fun `an unbound wallet signs again with the same wallet to create a network`() {
        assertEquals(
            BittensorLoginNext.CreateNetwork("taocom", proof.address),
            bittensorLoginNext(proof, null, unlinkedWallet = true, errorMessage = null),
        )
        assertEquals(
            BittensorLoginNext.CreateNetwork("taocom", proof.address),
            bittensorLoginNext(proof, "", unlinkedWallet = true, errorMessage = null),
        )
    }

    @Test
    fun `an error wins over anything else`() {
        assertEquals(
            BittensorLoginNext.Failed("401 invalid signature"),
            bittensorLoginNext(proof, "jwt", true, "401 invalid signature"),
        )
        assertEquals(BittensorLoginNext.Failed(null), bittensorLoginNext(proof, null, false, null))
    }

    @Test
    fun `the create bundle carries the proof as a TAO wallet auth`() {
        val bundle = bittensorCreateBundle(proof.copy(purpose = "create"))
        assertEquals(WalletCreateBundle("TAO", proof.address, proof.message, proof.signature, manualWalletId = "taocom"), bundle)
    }
}

/**
 * The login controller's part of the wallet app sign-in on the foreground edge:
 * a connection that still waits for its approval is opened again (a wallet can
 * miss the request on the first open of its link), exactly as the sheet's button
 * does. The wake-up's own open is not repeated, and the edge opens at most once
 * per resume. The connection is a fake; no clock, network or wallet.
 */
class BittensorLoginWalletReopenTest {

    private val talismanPackage = "xyz.talisman.app"
    private val pairingLink = "https://talisman.xyz/wc?uri=wc:topic"

    private class Connection : BittensorWalletConnection {
        var stateNow = BittensorWallets.CONNECT_AWAITING_APPROVAL
        var linkToTake = ""
        var buttonLink = ""
        var closes = 0

        override val walletId: String = BittensorWallets.TALISMAN
        override fun state(): String = stateNow
        override fun purpose(): String = BittensorWallets.PURPOSE_LOGIN
        override fun address(): String = ""
        override fun connected(): Boolean = true
        override fun sign(purpose: String, expectedAddress: String) {}
        override fun takeWalletLink(): String {
            val link = linkToTake
            linkToTake = ""
            return link
        }
        override fun walletLink(): String = buttonLink
        override fun takeProof(): BittensorProof? = null
        override fun failure(): BittensorProofOutcome.Refused? = null
        override fun setForeground(foreground: Boolean) {}
        override fun close() {
            closes += 1
            stateNow = BittensorWallets.CONNECT_CLOSED
        }
        override var onChanged: (() -> Unit)? = null
    }

    /** A controller whose held connection waits for Talisman's approval, with the starts of the wallet. */
    private fun awaitingApproval(
        opens: MutableList<Pair<String, String>>,
        openResult: () -> Boolean = { true },
    ): Pair<BittensorLoginController, Connection> {
        val flow = BittensorProofFlow(
            walletConnections = BittensorWalletConnections(),
            walletAppFor = { walletId ->
                if (walletId == BittensorWallets.TALISMAN) {
                    BittensorWalletApp(walletId, WalletInstall.Installed(talismanPackage))
                } else {
                    null
                }
            },
        ) { 1000L }
        flow.open(BittensorWallets.PURPOSE_LOGIN)
        val request = flow.choose(BittensorWallets.TALISMAN)!!
        val connection = Connection()
        connection.linkToTake = pairingLink
        connection.buttonLink = pairingLink
        assertTrue(flow.walletStarted(request, connection))
        val controller = BittensorLoginController(
            flow = flow,
            scope = CoroutineScope(Dispatchers.Unconfined),
            api = { null },
            setLoginError = {},
            setInProgress = {},
            defaultError = { "error" },
            onNetworkJwt = {},
            onCreateNetwork = {},
            openWallet = { link, pkg -> opens.add(link to pkg); openResult() },
        )
        return controller to connection
    }

    @Test
    fun `the wallet is opened again on the way back, once per resume`() {
        val opens = mutableListOf<Pair<String, String>>()
        val (controller, _) = awaitingApproval(opens)

        // the first open is the wake-up's: this resume itself opens nothing more
        controller.onResumed()
        assertEquals(listOf(pairingLink to talismanPackage), opens)

        // back from the wallet with its approval still pending: opened again
        controller.onStopped()
        controller.onResumed()
        assertEquals(2, opens.size)
        assertEquals(pairingLink to talismanPackage, opens[1])

        // no leave-and-return in between: no open
        controller.onResumed()
        assertEquals(2, opens.size)

        // and again on the next return, for as long as the approval is pending
        controller.onStopped()
        controller.onResumed()
        assertEquals(3, opens.size)
    }

    @Test
    fun `nothing is opened again past the approval, or with no link to open`() {
        val opens = mutableListOf<Pair<String, String>>()
        val (controller, connection) = awaitingApproval(opens)
        controller.onResumed()
        assertEquals(1, opens.size)

        // approved meanwhile: the signature is awaited, nothing is opened again
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_SIGNATURE
        connection.buttonLink = BittensorWallets.WALLET_LINK_LAUNCH_PACKAGE
        controller.onStopped()
        controller.onResumed()
        assertEquals(1, opens.size)

        // no link, and the launch of the package (which is no link): nothing opens
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_APPROVAL
        connection.buttonLink = ""
        controller.onStopped()
        controller.onResumed()
        connection.buttonLink = BittensorWallets.WALLET_LINK_LAUNCH_PACKAGE
        controller.onStopped()
        controller.onResumed()
        assertEquals(1, opens.size)
    }

    @Test
    fun `a reopen that cannot start ends the attempt, as the button does`() {
        val opens = mutableListOf<Pair<String, String>>()
        var openResult = true
        val (controller, connection) = awaitingApproval(opens) { openResult }
        controller.onResumed()
        assertEquals(1, opens.size)

        openResult = false
        controller.onStopped()
        controller.onResumed()
        assertEquals(2, opens.size)
        assertEquals(1, connection.closes)

        // the connection is over: the next resume opens nothing
        controller.onStopped()
        controller.onResumed()
        assertEquals(2, opens.size)
    }
}
