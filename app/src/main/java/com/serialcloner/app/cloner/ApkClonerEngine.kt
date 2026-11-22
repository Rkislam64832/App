package com.serialcloner.app.cloner

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Repackages an installed APK with a new serial package name (e.g., pkg.clone1),
 * updated home-screen label (e.g., "App 1"), and fresh v1+v2+v3 APK signatures.
 */
object ApkClonerEngine {

    data class CloneResult(
        val signedApk: File,
        val clonedPackageName: String,
        val clonedLabel: String
    )

    fun cloneApk(
        context: Context,
        sourceApkPath: String,
        originalPackage: String,
        originalLabel: String,
        serialNumber: Int,
        customLabel: String? = null,
        onProgress: (String) -> Unit = {}
    ): CloneResult {
        val clonedPackage = "$originalPackage.clone$serialNumber"
        val clonedLabel = customLabel?.takeIf { it.isNotBlank() } ?: "$originalLabel $serialNumber"

        val outDir = File(context.cacheDir, "cloned_apks").apply { mkdirs() }
        val unsignedApk = File(outDir, "${clonedPackage}_unsigned.apk")
        val signedApk = File(outDir, "${clonedPackage}.apk")
        if (unsignedApk.exists()) unsignedApk.delete()
        if (signedApk.exists()) signedApk.delete()

        onProgress("Reading original APK & patching manifest...")
        ZipFile(File(sourceApkPath)).use { zipIn ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(unsignedApk))).use { zipOut ->
                val entries = zipIn.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // Skip old signature files so ApkSigner creates clean signatures
                    if (isSignatureFile(name)) continue

                    if (name == "AndroidManifest.xml") {
                        val originalManifest = zipIn.getInputStream(entry).use { it.readBytes() }
                        val patchedManifest = AxmlPatcher.patchManifest(
                            axmlBytes = originalManifest,
                            oldPackage = originalPackage,
                            newPackage = clonedPackage,
                            newLabel = clonedLabel,
                            serialNumber = serialNumber
                        )
                        val newEntry = ZipEntry(name).apply {
                            method = ZipEntry.DEFLATED
                        }
                        zipOut.putNextEntry(newEntry)
                        zipOut.write(patchedManifest)
                        zipOut.closeEntry()
                    } else if (entry.method == ZipEntry.STORED || name == "resources.arsc" || name.endsWith(".so")) {
                        // Keep resources.arsc and native .so uncompressed (required by Android 11+)
                        val data = zipIn.getInputStream(entry).use { it.readBytes() }
                        val crc = CRC32().apply { update(data) }.value
                        val newEntry = ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = data.size.toLong()
                            compressedSize = data.size.toLong()
                            this.crc = crc
                        }
                        zipOut.putNextEntry(newEntry)
                        zipOut.write(data)
                        zipOut.closeEntry()
                    } else {
                        val newEntry = ZipEntry(name).apply {
                            method = ZipEntry.DEFLATED
                        }
                        zipOut.putNextEntry(newEntry)
                        zipIn.getInputStream(entry).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
            }
        }

        onProgress("Signing cloned APK (#$serialNumber)...")
        val material = KeystoreManager.getOrCreateSigningMaterial(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "SERIAL_CLONER",
            material.privateKey,
            listOf(material.certificate)
        ).build()

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        apkSigner.sign()

        unsignedApk.delete()
        onProgress("Ready to install!")

        return CloneResult(
            signedApk = signedApk,
            clonedPackageName = clonedPackage,
            clonedLabel = clonedLabel
        )
    }

    private fun isSignatureFile(entryName: String): Boolean {
        if (!entryName.startsWith("META-INF/")) return false
        val upper = entryName.uppercase()
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC")
    }
}
