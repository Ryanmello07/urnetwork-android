package com.bringyour.network.ui.wallet

import com.bringyour.network.ui.login.bittensorWalletReturnLink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three properties of the wallet app sign-in that are kept by how the source is
 * written, checked on the source text (as SignedOutNetworkStateTest does for the
 * sign-out order): the return link is inert, it has one handler, and the device
 * test switches are reached only by a debug build. Reads the module's sources; no
 * device.
 */
class BittensorWalletAppGuardsTest {

    private val main = "src/main/java/com/bringyour/network"

    // block comments, then line comments; no string of these files holds "//" or "/*"
    private fun code(path: String): String = File(path).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")

    // a wallet may open the return link at any time, and so may any other app: its
    // handler reads nothing of what it is given and touches nothing of the app
    @Test
    fun `the return activity reads nothing and only shows the app`() {
        val source = code("$main/WalletReturnActivity.kt")
        val forbidden = listOf(
            "intent", "getIntent", "data", "getData", "extras", "getStringExtra", "getQueryParameter",
            "Sdk", "api", "BittensorBridgeReturns", "BittensorWalletConnections", "MainApplication",
        )
        for (word in forbidden) {
            assertFalse("WalletReturnActivity names `$word`", Regex("""\b$word\b""").containsMatchIn(source))
        }
        val calls = Regex("""\b(\w+)\s*\(""").findAll(source).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("Activity", "onCreate", "if", "launchAppIntent", "startActivity", "finish"), calls)
    }

    @Test
    fun `the return link is the app's own, carries nothing, and has one handler`() {
        val link = bittensorWalletReturnLink("com.bringyour.network")
        assertEquals("com.bringyour.network.wallet://return", link)
        assertFalse(link.contains("?"))
        assertFalse(link.contains("#"))

        val manifest = File("src/main/AndroidManifest.xml").readText()
        val scheme = "android:scheme=\"\${applicationId}.wallet\""
        assertEquals(1, Regex(Regex.escape(scheme)).findAll(manifest).count())
        val activities = Regex("""<activity\b.*?</activity>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest).map { it.value }.toList()
        val handlers = activities.filter { it.contains(scheme) }
        assertEquals(1, handlers.size)
        assertTrue(handlers[0].contains("android:name=\".WalletReturnActivity\""))
        // LoginActivity acts on the links it takes: it takes none of this scheme
        val login = activities.single { it.contains("android:name=\".LoginActivity\"") }
        assertEquals(
            listOf("http", "https", "ur"),
            Regex("""android:scheme="([^"]+)"""").findAll(login).map { it.groupValues[1] }.toList(),
        )
    }

    // what a release build sends is what the sdk sends by default: the trace, the
    // device-test options and the return link are set in one function, which a
    // release build leaves at its first line
    @Test
    fun `only the debug setup reaches the device test switches`() {
        val source = code("$main/ui/wallet/BittensorProofSheets.kt")
        val start = source.indexOf("private fun bittensorWalletDebugSetup(")
        assertTrue(start >= 0)
        val bodyStart = source.indexOf("{", start) + 1
        val end = source.indexOf("\n}\n", start)
        val body = source.substring(bodyStart, end)
        assertTrue(body.trimStart().startsWith("if (!BuildConfig.DEBUG) return"))
        for (call in listOf("setTrace(", "setDeviceTestOptions(", "setReturnLinks(")) {
            assertEquals("`$call` in BittensorProofSheets.kt", 1, Regex(Regex.escape(".$call")).findAll(source).count())
            assertTrue("`$call` outside the debug setup", body.contains(".$call"))
        }
        // and no other file of the app calls them
        val others = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "BittensorProofSheets.kt" }
            .filter { !it.path.replace('\\', '/').contains("/test/") }
            .filter { file ->
                val text = file.readText()
                listOf(".setTrace(", ".setDeviceTestOptions(", ".setReturnLinks(").any { text.contains(it) }
            }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), others)
    }
}
