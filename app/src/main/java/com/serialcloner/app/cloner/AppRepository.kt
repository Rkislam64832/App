package com.serialcloner.app.cloner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class InstalledAppItem(
    val packageName: String,
    val label: String,
    val sourceApkPath: String,
    val icon: Drawable?,
    val isSplitApk: Boolean,
    val existingCloneSerials: List<Int>,
    val isCloneOf: String? = null,
    val cloneSerial: Int? = null
) {
    val nextSerialNumber: Int
        get() {
            var n = 1
            while (n in existingCloneSerials) n++
            return n
        }
}

object AppRepository {

    private val CLONE_SUFFIX_REGEX = Regex("^(.+)\\.clone(\\d+)$")

    fun loadApps(context: Context): Pair<List<InstalledAppItem>, List<InstalledAppItem>> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        // First pass: map basePackage -> list of installed clone serial numbers
        val cloneSerialMap = mutableMapOf<String, MutableList<Int>>()
        for (info in allApps) {
            val match = CLONE_SUFFIX_REGEX.matchEntire(info.packageName)
            if (match != null) {
                val basePkg = match.groupValues[1]
                val serial = match.groupValues[2].toIntOrNull() ?: continue
                cloneSerialMap.getOrPut(basePkg) { mutableListOf() }.add(serial)
            }
        }
        cloneSerialMap.values.forEach { it.sort() }

        val originalApps = mutableListOf<InstalledAppItem>()
        val clonedApps = mutableListOf<InstalledAppItem>()

        for (info in allApps) {
            if (info.packageName == selfPkg) continue
            // Only show launchable apps (apps that have a launcher icon)
            if (pm.getLaunchIntentForPackage(info.packageName) == null) continue

            val label = pm.getApplicationLabel(info)?.toString() ?: info.packageName
            val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()
            val isSplit = !info.splitSourceDirs.isNullOrEmpty()
            val match = CLONE_SUFFIX_REGEX.matchEntire(info.packageName)

            if (match != null) {
                val basePkg = match.groupValues[1]
                val serial = match.groupValues[2].toIntOrNull()
                clonedApps.add(
                    InstalledAppItem(
                        packageName = info.packageName,
                        label = label,
                        sourceApkPath = info.sourceDir,
                        icon = icon,
                        isSplitApk = isSplit,
                        existingCloneSerials = emptyList(),
                        isCloneOf = basePkg,
                        cloneSerial = serial
                    )
                )
            } else {
                val isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (!isSystemApp || isUpdatedSystem) {
                    originalApps.add(
                        InstalledAppItem(
                            packageName = info.packageName,
                            label = label,
                            sourceApkPath = info.sourceDir,
                            icon = icon,
                            isSplitApk = isSplit,
                            existingCloneSerials = cloneSerialMap[info.packageName].orEmpty()
                        )
                    )
                }
            }
        }

        originalApps.sortBy { it.label.lowercase() }
        clonedApps.sortWith(compareBy<InstalledAppItem> { it.isCloneOf ?: "" }.thenBy { it.cloneSerial ?: 0 })
        return originalApps to clonedApps
    }
}
