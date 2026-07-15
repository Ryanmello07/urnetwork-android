package com.bringyour.network.ui

import android.net.Uri
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bringyour.network.ui.components.overlays.FullScreenOverlay
import com.bringyour.network.ui.login.AuthCodeLoadingScreen
import com.bringyour.network.ui.login.CreateNetworkInstant
import com.bringyour.network.ui.login.LoginCreateNetwork
import com.bringyour.network.ui.login.LoginCreateNetworkParams
import com.bringyour.network.ui.login.LoginInitial
import com.bringyour.network.ui.login.LoginPassword
import com.bringyour.network.ui.login.LoginPasswordReset
import com.bringyour.network.ui.login.LoginPasswordResetAfterSend
import com.bringyour.network.ui.login.LoginSeedphrase
import com.bringyour.network.ui.login.LoginVerify
import com.bringyour.network.ui.login.SeedphraseDisplayScreen
import com.bringyour.network.ui.login.LoginViewModel
import com.bringyour.network.ui.login.SwitchAccountScreen
import com.bringyour.network.ui.login.toWalletCreateBundle
import com.bringyour.network.ui.login.handleLoginFlow
import com.bringyour.network.ui.shared.viewmodels.OverlayViewModel
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun LoginNavHost(
    loginViewModel: LoginViewModel,
    promptAccountSwitch: Boolean,
    currentNetworkName: String? = null,
    targetJwt: String? = null,
    switchToGuestMode: Boolean,
    isLoadingAuthCode: Boolean,
    referralCode: String?,
    activityResultSender: ActivityResultSender?,
    walletCreateNetworkParams: LoginCreateNetworkParams.LoginCreateWalletParams? = null,
    overlayViewModel: OverlayViewModel = hiltViewModel()
) {
    val navController = rememberNavController()

    var switchAccount by remember { mutableStateOf(promptAccountSwitch) }

    LaunchedEffect(promptAccountSwitch) {
        if (promptAccountSwitch) {
            switchAccount = true
        }
    }

    Box(
       modifier = Modifier.fillMaxSize()
    ) {

        if (isLoadingAuthCode) {
            AuthCodeLoadingScreen()
        } else {

            if (switchAccount && !currentNetworkName.isNullOrEmpty()) {
                SwitchAccountScreen(
                    currentNetworkName = currentNetworkName,
                    targetJwt = targetJwt,
                    switchToGuestMode = switchToGuestMode,
                    setSwitchAccount = { switchAccount = it }
                )
            } else {
                NavHost(
                    navController = navController,
                    startDestination = "login-initial",
                    enterTransition = { slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(durationMillis = 300)
                    ) + fadeIn(animationSpec = tween(300)
                    ) },
                    exitTransition = {
                        ExitTransition.None
                    },
                    popEnterTransition = {
                        fadeIn(animationSpec = tween(300))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(durationMillis = 300)
                        )
                    }
                ) {

                    composable("login-initial") {
                        LoginInitial(
                            navController,
                            loginViewModel,
                            activityResultSender
                        )
                    }

                    composable("login-password/{userAuth}") { backStackEntry ->

                        val userAuth = backStackEntry.arguments?.getString("userAuth") ?: ""

                        LoginPassword(
                            userAuth,
                            navController
                        )
                    }

                    composable("create-network/{userAuth}") { backStackEntry ->

                        val userAuth = backStackEntry.arguments?.getString("userAuth") ?: ""

                        val createNetworkParams = LoginCreateNetworkParams.LoginCreateUserAuthParams(
                            userAuth = userAuth,
                            referralCode = referralCode
                        )

                        LoginCreateNetwork(
                            createNetworkParams,
                            navController
                        )
                    }

                    composable("create-network/{blockchain}/{walletAddress}/{signedMessage}/{signature}") { backStackEntry ->

                        val blockchain = backStackEntry.arguments?.getString("blockchain") ?: ""
                        val walletAddress = backStackEntry.arguments?.getString("walletAddress") ?: ""
                        val signedMessage = backStackEntry.arguments?.getString("signedMessage") ?: ""
                        val signature = backStackEntry.arguments?.getString("signature") ?: ""

                        val createNetworkParams = LoginCreateNetworkParams.LoginCreateWalletParams(
                            blockchain = blockchain,
                            publicKey = walletAddress,
                            signedMessage = signedMessage,
                            signature = signature,
                            referralCode = referralCode
                        )

                        LoginCreateNetwork(
                            createNetworkParams,
                            navController
                        )
                    }

                    composable("create-network-wallet/{bundle}") { backStackEntry ->

                        val bundleArg = backStackEntry.arguments?.getString("bundle") ?: ""
                        val walletBundle = bundleArg.toWalletCreateBundle()

                        if (walletBundle == null) {
                            // invalid/corrupted bundle - don't strand the user on a
                            // create-network screen with blank, unusable wallet params
                            LaunchedEffect(Unit) {
                                navController.popBackStack()
                            }
                        } else {
                            val createNetworkParams = LoginCreateNetworkParams.LoginCreateWalletParams(
                                blockchain = walletBundle.blockchain,
                                publicKey = walletBundle.publicKey,
                                signedMessage = walletBundle.signedMessage,
                                signature = walletBundle.signature,
                                referralCode = referralCode
                            )

                            LoginCreateNetwork(
                                createNetworkParams,
                                navController
                            )
                        }
                    }

                    composable("create-network-jwt/{userAuth}/{authJwt}/{userName}") { backStackEntry ->

                        val userAuth = backStackEntry.arguments?.getString("userAuth") ?: ""
                        val authJwt = backStackEntry.arguments?.getString("authJwt") ?: ""
                        val userName = backStackEntry.arguments?.getString("userName") ?: ""
                        val authJwtType = "google"

                        val createNetworkParams = LoginCreateNetworkParams.LoginCreateAuthJwtParams(
                            userAuth = userAuth,
                            authJwtType = authJwtType,
                            authJwt = authJwt,
                            userName = userName,
                            referralCode = referralCode
                        )

                        LoginCreateNetwork(
                            createNetworkParams,
                            navController
                        )
                    }

                    composable("verify/{userAuth}") { backStackEntry ->

                        val userAuth = backStackEntry.arguments?.getString("userAuth") ?: ""

                        LoginVerify(
                            userAuth,
                            navController
                        )
                    }

                    composable("reset-password/{userAuth}") { backStackEntry ->

                        val userAuth = backStackEntry.arguments?.getString("userAuth") ?: ""

                        LoginPasswordReset(
                            userAuth,
                            navController
                        )
                    }

                    composable("reset-password-after-send/{userAuth}") { backStackEntry ->

                        val userAuth = backStackEntry.arguments?.getString("userAuth") ?: ""

                        LoginPasswordResetAfterSend(
                            userAuth,
                            navController
                        )
                    }

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
                }

                // an unlinked wallet auth was received by the activity (eg the bittensor
                // sign message deep link) -> route into the create network flow
                LaunchedEffect(walletCreateNetworkParams) {
                    walletCreateNetworkParams?.let { params ->

                        val encodedBlockchain = Uri.encode(params.blockchain)
                        val encodedPublicKey = Uri.encode(params.publicKey)
                        val encodedSignedMessage = Uri.encode(params.signedMessage)
                        val encodedSignature = Uri.encode(params.signature)

                        navController.navigate("create-network/${encodedBlockchain}/${encodedPublicKey}/${encodedSignedMessage}/${encodedSignature}")
                    }
                }

                FullScreenOverlay(
                    referralCode = null,
                    overlayViewModel = overlayViewModel
                )
            }

        }

    }

}
