package org.experimentalmachines.mobileagent.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * The launcher's apps (visible through the manifest's `<queries>` for MAIN/LAUNCHER; no QUERY_ALL_PACKAGES).
 * Their labels are the prompt's "Installed apps" line (v4), sorted case-insensitively and de-duplicated like the
 * benchmark's list (`app_resolver.display_names`), without this app itself.
 */
object InstalledApps {
    data class App(val label: String, val packageName: String, val activity: String)

    fun list(context: Context): List<App> {
        val pm = context.packageManager
        val infos: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        )
        return infos.map { App(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName != context.packageName && it.label.isNotEmpty() }
    }

    /** The prompt's app names: unique labels, sorted like Python's `sorted(set(names), key=str.lower)`. */
    fun promptNames(apps: List<App>): List<String> = apps.map { it.label }.toSortedSet().sortedBy { it.lowercase() }

    /**
     * The app an `open_app` name means: the exact label (ignoring case), then a label starting with it, then one
     * containing it, then a package name containing it.
     */
    fun resolve(apps: List<App>, name: String): App? {
        val want = name.trim().lowercase()
        if (want.isEmpty()) return null
        return apps.firstOrNull { it.label.lowercase() == want }
            ?: apps.firstOrNull { it.label.lowercase().startsWith(want) }
            ?: apps.firstOrNull { want in it.label.lowercase() }
            ?: apps.firstOrNull { want in it.packageName.lowercase() }
    }
}
