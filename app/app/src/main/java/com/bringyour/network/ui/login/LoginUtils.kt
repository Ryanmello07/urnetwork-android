package com.bringyour.network.ui.login
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.bringyour.network.BuildConfig
import com.bringyour.network.LoginActivity
import com.bringyour.network.MainApplication
import com.bringyour.network.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// the message signed by the Bittensor wallet-connect bridge flow
// the LoginActivity uses the same message when verifying the returned signature
const val BITTENSOR_SIGN_MESSAGE = "Welcome to URnetwork"

private const val BITTENSOR_SIGN_REDIRECT_LINK = "ur://bittensor-sign-message"

/**
 * Opens the ur.io wallet-connect bridge to sign a message with a Bittensor wallet.
 * The bridge redirects back to the app as
 * `ur://bittensor-sign-message?address=<ss58>&signature=<0xhex>`
 * (or `?errorCode=...&errorMessage=...`), which is handled by the LoginActivity.
 */
fun launchBittensorSignMessage(context: Context): Boolean {
    // the WalletConnect Cloud project id (local.properties) lets the bridge
    // pair with a wallet app; without it the bridge uses injected wallets only
    val walletConnectProjectId = BuildConfig.WALLETCONNECT_PROJECT_ID
    val walletConnectParam = if (walletConnectProjectId.isNotEmpty()) {
        "&wc_project_id=${Uri.encode(walletConnectProjectId)}"
    } else {
        ""
    }

    val uri = Uri.parse(
        "https://ur.io/wallet-connect" +
                "?provider=bittensor" +
                "&method=signMessage" +
                "&message=${Uri.encode(BITTENSOR_SIGN_MESSAGE)}" +
                "&redirect_link=${Uri.encode(BITTENSOR_SIGN_REDIRECT_LINK)}" +
                walletConnectParam
    )

    return try {
        CustomTabsIntent.Builder()
            .build()
            .launchUrl(context, uri)
        true
    } catch (e: Exception) {
        // fall back to a plain browser intent if custom tabs are unavailable
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (e: Exception) {
            Log.i("LoginUtils", "unable to launch bittensor sign message: ${e.message}")
            false
        }
    }
}

/**
 * Used on LoginInitial on individual build flavors
 */
fun handleLoginFlow(
    networkJwt: String,
    scope: CoroutineScope,
    appLogin: (String) -> Unit,
    authClientAndFinish: (
        callback: (String?) -> Unit,
    ) -> Unit,
    onErr: () -> Unit,
    onContentVisibilityChange: (Boolean) -> Unit,
    onWelcomeOverlayVisibilityChange: (Boolean) -> Unit,
) {
    scope.launch {
        appLogin(networkJwt)

        onContentVisibilityChange(false)

        delay(500)

        onWelcomeOverlayVisibilityChange(true)

        delay(2250)

        authClientAndFinish { error ->
            if (error != null) {
                Log.i(TAG, "auth client and finish err: $error")
                onErr()
            }
        }
    }
}
