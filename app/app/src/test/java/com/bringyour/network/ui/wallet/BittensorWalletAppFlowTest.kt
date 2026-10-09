package com.bringyour.network.ui.wallet

import com.bringyour.network.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wallet the app opens itself (Talisman on the login screen): the chooser row
 * with its install check, the connection held for the process, and the wake-up
 * routine that moves the sheets with the connection's states. The connection is
 * a fake with scripted states; the sdk's own behaviour is covered in
 * sdk/bittensor_wallet_connect_test.go. No clock, network or wallet.
 */
class BittensorWalletAppFlowTest {

    private val alice = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"
    private val message = "Sign in to URnetwork\nChallenge: abc\nTimestamp: 1757340000"
    private val signature = "0x" + "ab".repeat(64)
    private val talismanPackage = "xyz.talisman.app"
    private val pairingLink = "https://talisman.xyz/wc?uri=wc:topic"
    private val forwardLink = "launch-package:"

    private class Connection(override val walletId: String = BittensorWallets.TALISMAN) : BittensorWalletConnection {
        var stateNow = BittensorWallets.CONNECT_CONNECTING
        var purposeNow = BittensorWallets.PURPOSE_LOGIN
        var addressNow = ""
        // what the sdk hands out once per waiting state, and what its button gets
        var linkToTake = ""
        var buttonLink = ""
        var proof: BittensorProof? = null
        var refused: BittensorProofOutcome.Refused? = null
        var refusesSign = false
        var trace = listOf<String>()
        val signs = mutableListOf<Pair<String, String>>()
        val foreground = mutableListOf<Boolean>()
        var closes = 0

        override fun state(): String = stateNow
        override fun purpose(): String = purposeNow
        override fun address(): String = addressNow
        override fun connected(): Boolean = true

        override fun sign(purpose: String, expectedAddress: String) {
            if (refusesSign) {
                throw IllegalStateException("closed: the wallet connection is over")
            }
            signs.add(purpose to expectedAddress)
            purposeNow = purpose
            stateNow = BittensorWallets.CONNECT_CONNECTING
        }

        override fun takeWalletLink(): String {
            val link = linkToTake
            linkToTake = ""
            return link
        }

        override fun walletLink(): String = buttonLink

        override fun takeProof(): BittensorProof? {
            val taken = proof
            proof = null
            return taken
        }

        override fun failure(): BittensorProofOutcome.Refused? = refused

        override fun setForeground(foreground: Boolean) {
            this.foreground.add(foreground)
        }

        override fun close() {
            closes += 1
            stateNow = BittensorWallets.CONNECT_CLOSED
        }

        override var onChanged: (() -> Unit)? = null
        override val debugSetup: String = "options=default return=0"
        override fun traceLines(): List<String> = trace
    }

    private fun proof(purpose: String) = BittensorProof(BittensorWallets.TALISMAN, purpose, alice, message, signature)

    /** A flow that opens Talisman as an app, with the given answer of the install check. */
    private fun walletFlow(
        connections: BittensorWalletConnections = BittensorWalletConnections(),
        install: WalletInstall = WalletInstall.Installed(talismanPackage),
        openUnverified: Boolean = false,
    ) = BittensorProofFlow(
        walletConnections = connections,
        walletAppFor = { walletId ->
            if (walletId == BittensorWallets.TALISMAN) BittensorWalletApp(walletId, install, openUnverified) else null
        },
    ) { 1000L }

    /** Talisman chosen for a sign-in and its connection held: the flow, the holder, the connection. */
    private fun started(): Triple<BittensorProofFlow, BittensorWalletConnections, Connection> {
        val connections = BittensorWalletConnections()
        val flow = walletFlow(connections)
        flow.open(BittensorWallets.PURPOSE_LOGIN)
        val request = flow.choose(BittensorWallets.TALISMAN)!!
        val connection = Connection()
        assertTrue(flow.walletStarted(request, connection))
        return Triple(flow, connections, connection)
    }

    @Test
    fun `a wallet app row gives a request for the package of its install check`() {
        val flow = walletFlow()
        flow.open(BittensorWallets.PURPOSE_LOGIN)
        assertEquals(setOf(BittensorWallets.TALISMAN), flow.walletApps.value.keys)

        val request = flow.choose(BittensorWallets.TALISMAN)!!
        assertEquals(
            BittensorProofRequest("talisman", "login", null, walletApp = true, walletPackage = talismanPackage),
            request,
        )
        assertEquals(BittensorProofStage.Loading(request), flow.stage.value)

        // the other rows are as they always were
        flow.open(BittensorWallets.PURPOSE_LOGIN)
        assertEquals(BittensorProofRequest("taocom", "login", null), flow.choose(BittensorWallets.TAO_COM))
    }

    @Test
    fun `a wallet that is not installed or not verified gives no request`() {
        for (install in listOf(WalletInstall.NotInstalled, WalletInstall.NotVerified(talismanPackage, "ab12"))) {
            val flow = walletFlow(install = install)
            flow.open(BittensorWallets.PURPOSE_LOGIN)
            assertNull(flow.walletApps.value[BittensorWallets.TALISMAN]!!.packageName)
            assertNull(flow.choose(BittensorWallets.TALISMAN))
            assertEquals(BittensorProofStage.Choosing(), flow.stage.value)
        }
        // a debug build's switch lets a device test go on with the copy that is installed
        val flow = walletFlow(install = WalletInstall.NotVerified(talismanPackage, "ab12"), openUnverified = true)
        flow.open(BittensorWallets.PURPOSE_LOGIN)
        assertEquals(talismanPackage, flow.choose(BittensorWallets.TALISMAN)!!.walletPackage)
    }

    @Test
    fun `a flow that holds no wallet connection has no wallet app row`() {
        val flow = BittensorProofFlow(
            walletAppFor = { walletId -> BittensorWalletApp(walletId, WalletInstall.Installed(talismanPackage)) },
        ) { 1000L }
        assertFalse(flow.opensWalletApps)
        flow.open(BittensorWallets.PURPOSE_ADD)
        assertTrue(flow.walletApps.value.isEmpty())
        assertEquals(BittensorProofRequest("talisman", "add", null), flow.choose(BittensorWallets.TALISMAN))
    }

    @Test
    fun `the wake-up follows the states and opens each link once for the verified package`() {
        val (flow, connections, connection) = started()
        assertTrue(connections.waiting)

        // the sdk is working: the row keeps spinning
        assertSame(BittensorWalletAction.None, flow.wake(active = true))
        assertTrue(flow.stage.value is BittensorProofStage.Loading)

        // the pairing is at the relay: the wallet is opened with its link
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_APPROVAL
        connection.linkToTake = pairingLink
        val pair = flow.wake(active = true) as BittensorWalletAction.Open
        assertEquals(pairingLink, pair.link)
        assertEquals(talismanPackage, pair.packageName)
        assertEquals(BittensorProofStage.AwaitingWalletApproval("talisman"), flow.stage.value)
        // the link was taken: a second wake-up opens nothing
        assertSame(BittensorWalletAction.None, flow.wake(active = true))

        // approved: the sdk fetches the challenge, the sheet stays up
        connection.stateNow = BittensorWallets.CONNECT_CONNECTING
        connection.addressNow = alice
        assertSame(BittensorWalletAction.None, flow.wake(active = false))
        assertEquals(BittensorProofStage.AwaitingWalletApproval("talisman"), flow.stage.value)

        // the request is with the wallet: it is brought forward
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_SIGNATURE
        connection.linkToTake = forwardLink
        val forward = flow.wake(active = true) as BittensorWalletAction.Open
        assertEquals(forwardLink, forward.link)
        assertEquals(talismanPackage, forward.packageName)
        assertEquals(BittensorProofStage.AwaitingWalletSignature("talisman", alice, "login"), flow.stage.value)

        // signed while the app is behind the wallet: the proof waits
        connection.stateNow = BittensorWallets.CONNECT_SIGNED
        connection.proof = proof("login")
        assertSame(BittensorWalletAction.None, flow.wake(active = false))
        assertEquals(BittensorProofStage.AwaitingWalletSignature("talisman", alice, "login"), flow.stage.value)

        // in front again: the proof is handed off once, and the sheets close
        val proven = flow.wake(active = true) as BittensorWalletAction.Proven
        assertEquals(proof("login"), proven.proof)
        assertEquals(BittensorProofStage.Hidden, flow.stage.value)
        assertSame(BittensorWalletAction.None, flow.wake(active = true))
        // the connection is kept: the caller decides whether it signs again
        assertTrue(connections.waiting)
        assertEquals(0, connection.closes)
    }

    @Test
    fun `the button opens what is pending for the verified package`() {
        val (flow, _, connection) = started()
        assertSame(BittensorWalletAction.None, flow.walletButton())
        connection.buttonLink = forwardLink
        val open = flow.walletButton() as BittensorWalletAction.Open
        assertEquals(forwardLink, open.link)
        assertEquals(talismanPackage, open.packageName)
    }

    @Test
    fun `a return to the front reopens the link the wallet is still to approve`() {
        // nothing is held: nothing to open again
        assertSame(BittensorWalletAction.None, walletFlow().walletReopen())

        val (flow, _, connection) = started()
        // the sdk is still working: the wallet has no request to be shown again
        assertSame(BittensorWalletAction.None, flow.walletReopen())

        connection.stateNow = BittensorWallets.CONNECT_AWAITING_APPROVAL
        connection.linkToTake = pairingLink
        connection.buttonLink = pairingLink
        assertTrue(flow.wake(active = true) is BittensorWalletAction.Open)

        // the wake-up's open took the link once; the return opens the button's link again
        val reopen = flow.walletReopen() as BittensorWalletAction.Open
        assertEquals(pairingLink, reopen.link)
        assertEquals(talismanPackage, reopen.packageName)

        // no link, or only the launch of the package (which is no link): nothing opens
        connection.buttonLink = ""
        assertSame(BittensorWalletAction.None, flow.walletReopen())
        connection.buttonLink = BittensorWallets.WALLET_LINK_LAUNCH_PACKAGE
        assertSame(BittensorWalletAction.None, flow.walletReopen())

        // past the approval there is nothing to open again
        connection.buttonLink = pairingLink
        connection.addressNow = alice
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_SIGNATURE
        flow.wake(active = true)
        assertSame(BittensorWalletAction.None, flow.walletReopen())
    }

    @Test
    fun `a failure closes the connection and the chooser is up with the reason`() {
        val (flow, connections, connection) = started()
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_APPROVAL
        flow.wake(active = true)

        connection.stateNow = BittensorWallets.CONNECT_FAILED
        connection.refused = BittensorProofOutcome.Refused("wallet_error", "The request was declined in the wallet.", "user_rejected")
        val failed = flow.wake(active = false) as BittensorWalletAction.Failed
        assertEquals("talisman", failed.walletId)
        assertEquals(connection.refused, failed.refused)
        assertEquals(1, connection.closes)
        assertFalse(connections.waiting)
        assertEquals(BittensorProofStage.Choosing(R.string.login_error), flow.stage.value)

        flow.walletFailed("declined")
        assertEquals(BittensorProofStage.Choosing(errorText = "declined"), flow.stage.value)
        // the next attempt is a new connection
        assertEquals(talismanPackage, flow.choose(BittensorWallets.TALISMAN)!!.walletPackage)
    }

    @Test
    fun `a connection that ended with nothing to tell changes nothing on screen`() {
        val (flow, connections, connection) = started()
        connection.stateNow = BittensorWallets.CONNECT_SIGNED
        connection.proof = proof("login")
        assertTrue(flow.wake(active = true) is BittensorWalletAction.Proven)

        // its work is done and the sdk let it go
        connection.stateNow = BittensorWallets.CONNECT_CLOSED
        assertSame(BittensorWalletAction.None, flow.wake(active = true))
        assertFalse(connections.waiting)
        assertEquals(BittensorProofStage.Hidden, flow.stage.value)
    }

    @Test
    fun `a wallet that does not start ends the attempt only while its approval is awaited`() {
        val (flow, connections, connection) = started()
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_APPROVAL
        flow.wake(active = true)
        assertNull(flow.walletOpened(true))
        assertTrue(connections.waiting)

        assertEquals("talisman", flow.walletOpened(false))
        assertEquals(1, connection.closes)
        assertFalse(connections.waiting)
        assertEquals(BittensorProofStage.Choosing(R.string.login_error), flow.stage.value)

        // while a signature is awaited the user can still open the wallet by hand
        val (signingFlow, signingConnections, signing) = started()
        signing.stateNow = BittensorWallets.CONNECT_AWAITING_SIGNATURE
        signing.addressNow = alice
        signingFlow.wake(active = true)
        assertNull(signingFlow.walletOpened(false))
        assertTrue(signingConnections.waiting)
        assertEquals(0, signing.closes)
        assertEquals(BittensorProofStage.AwaitingWalletSignature("talisman", alice, "login"), signingFlow.stage.value)
    }

    @Test
    fun `the create signature is asked on the same connection`() {
        val (flow, connections, connection) = started()
        connection.stateNow = BittensorWallets.CONNECT_SIGNED
        connection.addressNow = alice
        connection.proof = proof("login")
        assertTrue(flow.wake(active = true) is BittensorWalletAction.Proven)

        assertTrue(flow.signOnWallet(BittensorWallets.PURPOSE_CREATE, alice))
        assertEquals(listOf("create" to alice), connection.signs)
        assertEquals(0, connection.closes)
        assertSame(connection, connections.get()!!.connection)
        assertEquals(BittensorProofStage.AwaitingWalletSignature("talisman", alice, "create"), flow.stage.value)

        // the sdk asks, the sheet stays; nothing is opened by itself for this one
        assertSame(BittensorWalletAction.None, flow.wake(active = true))
        connection.stateNow = BittensorWallets.CONNECT_AWAITING_SIGNATURE
        assertSame(BittensorWalletAction.None, flow.wake(active = true))
        assertEquals(BittensorProofStage.AwaitingWalletSignature("talisman", alice, "create"), flow.stage.value)

        connection.stateNow = BittensorWallets.CONNECT_SIGNED
        connection.proof = proof("create")
        val proven = flow.wake(active = true) as BittensorWalletAction.Proven
        assertEquals("create", proven.proof.purpose)
    }

    @Test
    fun `a connection that cannot take the create signature is replaced once`() {
        val (flow, connections, connection) = started()
        connection.refusesSign = true
        assertFalse(flow.signOnWallet(BittensorWallets.PURPOSE_CREATE, alice))
        assertTrue(connection.signs.isEmpty())

        val request = flow.replaceWallet(BittensorWallets.TALISMAN, BittensorWallets.PURPOSE_CREATE, alice)!!
        assertEquals(
            BittensorProofRequest("talisman", "create", alice, walletApp = true, walletPackage = talismanPackage),
            request,
        )
        assertEquals(1, connection.closes)
        assertFalse(connections.waiting)
        assertEquals(BittensorProofStage.Loading(request), flow.stage.value)

        val next = Connection()
        assertTrue(flow.walletStarted(request, next))
        assertSame(next, connections.get()!!.connection)
    }

    @Test
    fun `with no connection held the create signature gets one from a new install check`() {
        val flow = walletFlow()
        assertFalse(flow.signOnWallet(BittensorWallets.PURPOSE_CREATE, alice))
        val request = flow.replaceWallet(BittensorWallets.TALISMAN, BittensorWallets.PURPOSE_CREATE, alice)!!
        assertEquals(talismanPackage, request.walletPackage)

        // and none when the wallet cannot be opened any more
        val gone = walletFlow(install = WalletInstall.NotInstalled)
        assertNull(gone.replaceWallet(BittensorWallets.TALISMAN, BittensorWallets.PURPOSE_CREATE, alice))
    }

    @Test
    fun `dismiss closes the flow's own connection and no other flow's`() {
        val (flow, connections, connection) = started()

        // the add sheet and Earnings use the same sheets and hold no connection
        val other = BittensorProofFlow { 1000L }
        other.open(BittensorWallets.PURPOSE_ADD)
        other.dismiss()
        assertTrue(connections.waiting)
        assertEquals(0, connection.closes)

        flow.dismiss()
        assertEquals(1, connection.closes)
        assertFalse(connections.waiting)
        assertEquals(BittensorProofStage.Hidden, flow.stage.value)
        // a wake-up that was still on its way finds nothing
        assertSame(BittensorWalletAction.None, flow.wake(active = true))
    }

    @Test
    fun `opening the chooser again ends the held connection`() {
        val (flow, connections, connection) = started()
        flow.open(BittensorWallets.PURPOSE_LOGIN)
        assertEquals(1, connection.closes)
        assertFalse(connections.waiting)
        assertEquals(BittensorProofStage.Choosing(), flow.stage.value)
    }

    @Test
    fun `a connection that arrives after the user moved on is closed`() {
        val connections = BittensorWalletConnections()
        val flow = walletFlow(connections)
        flow.open(BittensorWallets.PURPOSE_LOGIN)
        val request = flow.choose(BittensorWallets.TALISMAN)!!
        flow.dismiss()

        val connection = Connection()
        assertFalse(flow.walletStarted(request, connection))
        assertEquals(1, connection.closes)
        assertFalse(connections.waiting)
    }

    @Test
    fun `the screen tells the connection when it leaves the front and comes back`() {
        val (flow, _, connection) = started()
        flow.onStopped()
        flow.onResumed()
        assertEquals(listOf(false, true), connection.foreground)
    }

    @Test
    fun `a flow made anew attaches to the held connection`() {
        val (first, connections, connection) = started()
        var firstWakes = 0
        var secondWakes = 0
        first.attachWallet { firstWakes += 1 }

        // the activity was recreated: a new flow, the same holder
        val second = walletFlow(connections)
        second.attachWallet { secondWakes += 1 }
        connection.onChanged?.invoke()
        assertEquals(0, firstWakes)
        assertEquals(1, secondWakes)

        // nothing is up yet in the new flow: the state says which sheet
        assertSame(BittensorWalletAction.None, second.wake(active = true))
        assertEquals(BittensorProofStage.AwaitingWalletApproval("talisman"), second.stage.value)

        val later = walletFlow(connections)
        connection.addressNow = alice
        assertSame(BittensorWalletAction.None, later.wake(active = true))
        assertEquals(BittensorProofStage.AwaitingWalletSignature("talisman", alice, "login"), later.stage.value)

        // a proof that waits is handed to the flow that is there now
        connection.stateNow = BittensorWallets.CONNECT_SIGNED
        connection.proof = proof("login")
        assertTrue(later.wake(active = true) is BittensorWalletAction.Proven)
    }

    @Test
    fun `what a debug build shows outlives the connection`() {
        val (flow, connections, connection) = started()
        assertEquals("options=default return=0 verified pkg=xyz.talisman.app", connections.debug.value.setup)
        assertEquals(listOf("talisman verified pkg=xyz.talisman.app"), connections.debug.value.installs)

        connection.stateNow = BittensorWallets.CONNECT_AWAITING_APPROVAL
        connection.trace = listOf("00:00:01.000 sign login first=1", "00:00:01.200 state connecting -> awaiting_approval")
        flow.wake(active = true)
        flow.walletOpened(true)
        assertEquals("awaiting_approval", connections.debug.value.state)
        assertEquals(connection.trace, connections.debug.value.trace)
        assertEquals(listOf("00:00:01.000 open ok"), connections.debug.value.notes)

        flow.dismiss()
        assertEquals("closed", connections.debug.value.state)
        assertEquals(connection.trace, connections.debug.value.trace)
    }

    @Test
    fun `the install check is one line with the certificate digest of an unverified copy`() {
        assertEquals("verified pkg=xyz.talisman.app", WalletInstall.Installed(talismanPackage).describe())
        assertEquals("not-installed", WalletInstall.NotInstalled.describe())
        assertEquals(
            "not-verified pkg=xyz.talisman.app sha256=ab12",
            WalletInstall.NotVerified(talismanPackage, "ab12").describe(),
        )
    }

    @Test
    fun `the app's lines carry the clock of the sdk's trace`() {
        assertEquals("00:00:00.000", bittensorTraceClock(0L))
        assertEquals("01:02:03.004", bittensorTraceClock(3_723_004L))
        assertEquals("23:59:59.999", bittensorTraceClock(86_400_000L * 20_000L - 1L))
    }
}
