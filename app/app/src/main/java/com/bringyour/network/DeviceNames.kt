package com.bringyour.network

import android.content.Context
import android.os.Build
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Marketing-name lookup for the concise device spec. Android exposes only the
 * internal model code (`Build.MODEL`, e.g. "SM-S928B"); the retail name
 * ("Galaxy S24 Ultra") comes from Google's Play supported-devices catalog,
 * bundled as `assets/device_names.json.gz` (regenerate with
 * `scripts/generate_device_names.py`). Models absent from the map (Pixels,
 * whose model already is the retail name) fall back to `Build.MODEL`.
 */
object DeviceNames {

    private val stateLock = Any()
    private var names: Map<String, String>? = null
    private var loadFailed = false

    fun marketingName(context: Context, model: String = Build.MODEL): String {
        val loaded = synchronized(stateLock) {
            if (names == null && !loadFailed) {
                names = try {
                    load(context)
                } catch (e: Exception) {
                    loadFailed = true
                    null
                }
            }
            names
        }
        return loaded?.get(model) ?: model
    }

    private fun load(context: Context): Map<String, String> {
        context.assets.open("device_names.json.gz").use { assetIn ->
            GZIPInputStream(assetIn).use { gzIn ->
                val json = JSONObject(gzIn.readBytes().decodeToString())
                val loaded = HashMap<String, String>(json.length())
                for (key in json.keys()) {
                    loaded[key] = json.getString(key)
                }
                return loaded
            }
        }
    }
}
