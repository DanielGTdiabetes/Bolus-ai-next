package org.bolusai.next.glucose.dexcom

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import java.security.MessageDigest

/**
 * Reads the current holders of the Dexcom permission for the current user (ADR 0023, part I, points 2 to 4). Each call
 * is a fresh reading; nothing is cached. Other users or profiles are not inspected, and the result is never extended
 * to them. Any exception becomes [HolderSnapshot.QueryFailed] without its text.
 */
internal class AndroidHolderSnapshotSource(private val context: Context) : () -> HolderSnapshot {
    override fun invoke(): HolderSnapshot {
        if (Build.VERSION.SDK_INT < 28) return HolderSnapshot.PlatformUnsupported
        return try {
            read(context.packageManager)
        } catch (_: Exception) {
            HolderSnapshot.QueryFailed
        }
    }

    @RequiresApi(28)
    private fun read(pm: PackageManager): HolderSnapshot.Read {
        val definition = try {
            pm.getPermissionInfo(DexcomApprovedPermissionSet.PERMISSION, 0).let {
                PermissionDefinition(it.packageName, protectionBase(it))
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val infos: List<PackageInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackagesHoldingPermissions(arrayOf(DexcomApprovedPermissionSet.PERMISSION),
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackagesHoldingPermissions(arrayOf(DexcomApprovedPermissionSet.PERMISSION),
                PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val holders = infos.map { info ->
            val uid = checkNotNull(info.applicationInfo).uid
            PermissionHolder(
                packageName = info.packageName,
                uid = uid,
                versionCode = info.longVersionCode,
                signers = signers(info),
                packagesSharingUid = pm.getPackagesForUid(uid).orEmpty().toSet(),
            )
        }
        return HolderSnapshot.Read(fullVisibility(), definition, holders, context.packageName, Process.myUid())
    }

    /** Current APK signers only, as in the strict path: never historical rotation certificates. */
    @RequiresApi(28)
    private fun signers(info: PackageInfo): Set<String> =
        checkNotNull(info.signingInfo?.apkContentsSigners).map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet().also { check(it.isNotEmpty()) }

    @RequiresApi(28)
    private fun protectionBase(info: PermissionInfo): Int = info.protection

    /**
     * Package visibility filtering exists from Android 11. There, only `QUERY_ALL_PACKAGES` proves that no holder is
     * hidden from Next (ADR 0023, I-3). The Pixel test on 2026-10-12 showed that Legacy and the watch were hidden
     * without it, so the manifest now requests it. Missing coverage still closes the gate as `visibility_unproven`.
     */
    private fun fullVisibility(): Boolean = Build.VERSION.SDK_INT < 30 ||
        context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
}

/** Whether Next itself holds the Dexcom permission (ADR 0023, I-6), read without querying other packages. */
internal fun nextHoldsDexcomPermission(context: Context): Boolean =
    context.checkSelfPermission(DexcomApprovedPermissionSet.PERMISSION) == PackageManager.PERMISSION_GRANTED
