package com.bringyour.network.ui.stats

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * An installed app that can be added to the app split rules
 */
data class InstalledAppUi(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
)

/**
 * Loads the apps installed on the system
 */
@HiltViewModel
class InstalledAppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    var installedApps by mutableStateOf<List<InstalledAppUi>>(listOf())
        private set

    var isLoading by mutableStateOf(true)
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            installedApps = withContext(Dispatchers.IO) {
                loadInstalledApps()
            }
            isLoading = false
        }
    }

    fun refresh() {
        if (isRefreshing) {
            return
        }
        isRefreshing = true
        viewModelScope.launch {
            installedApps = withContext(Dispatchers.IO) {
                loadInstalledApps()
            }
            isRefreshing = false
        }
    }

    private fun loadInstalledApps(): List<InstalledAppUi> {
        val pm = context.packageManager

        // apps the user can launch, excluding this app
        val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)

        val seen = mutableSetOf<String>()
        val apps = mutableListOf<InstalledAppUi>()
        for (resolveInfo in resolveInfos) {
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: continue
            val packageName = appInfo.packageName ?: continue
            if (packageName == context.packageName) {
                continue
            }
            if (!seen.add(packageName)) {
                continue
            }
            val icon = runCatching {
                appInfo.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
            apps.add(
                InstalledAppUi(
                    packageName = packageName,
                    label = appInfo.loadLabel(pm).toString(),
                    icon = icon,
                )
            )
        }

        return apps.sortedBy { it.label.lowercase() }
    }
}
