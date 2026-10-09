package com.bringyour.network

import android.app.Activity
import android.os.Bundle

/**
 * Opened by a wallet app to show this app again after a Bittensor wallet
 * request: the return link the app names in its connection request
 * (ui.login.bittensorWalletReturnLink). It reads nothing and does nothing
 * else. Nothing arrives on the link, and whatever a wallet appends to it is
 * never looked at: the login screen finds its wallet connection by itself
 * when it is in front again.
 *
 * Declared singleTask with the app's own task affinity: the system puts this
 * on top of the app's task and brings that task forward, and finishing shows
 * the screen below as it was. With no task to join it is the root of a new
 * one, and the app is opened the normal way. Theme.NoDisplay has to finish
 * inside onCreate.
 */
class WalletReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTaskRoot) {
            QuickConnect.launchAppIntent(this)?.let { startActivity(it) }
        }
        finish()
    }
}
