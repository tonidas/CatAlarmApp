package com.ton.catalarm

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object AppIdentityDiagnostics {
    fun buildSummary(context: Context): String {
        val packageName = context.packageName
        val sha256 = getSigningSha256(context) ?: "unavailable"
        return "package=$packageName | sha256=$sha256"
    }

    private fun getSigningSha256(context: Context): String? {
        val packageInfo = getPackageInfo(context) ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        } ?: return null

        val certificateBytes = signatures.firstOrNull()?.toByteArray() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(certificateBytes)
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }

    private fun getPackageInfo(context: Context): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

