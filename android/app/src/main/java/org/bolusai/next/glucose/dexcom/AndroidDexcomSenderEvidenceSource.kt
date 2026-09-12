package org.bolusai.next.glucose.dexcom

import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Use synchronously inside onReceive, while Android's sender identity is present. */
internal class AndroidDexcomSenderEvidenceSource(
    private val receiver: BroadcastReceiver,
    private val packageManager: PackageManager,
) : DexcomSenderEvidenceSource {
    override fun attributedIdentity(): AttributedSenderIdentity =
        if (Build.VERSION.SDK_INT >= 34) {
            AttributedSenderIdentity(receiver.sentFromPackage, receiver.sentFromUid)
        } else {
            // Older systems do not provide the approved identity mechanism.
            AttributedSenderIdentity(null, null)
        }

    override fun resolvePackage(packageName: String): SenderPackageResolution {
        if (Build.VERSION.SDK_INT < 34) return SenderPackageResolution.NotFound
        val info = try {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return SenderPackageResolution.NotFound
        } catch (_: SecurityException) {
            return SenderPackageResolution.AccessDenied
        }
        val application = info.applicationInfo ?: return SenderPackageResolution.NotFound
        // Exact release anchors require CURRENT APK signers, not historical rotation certificates.
        val signers = info.signingInfo?.apkContentsSigners
        if (signers.isNullOrEmpty()) return SenderPackageResolution.SigningInfoUnavailable
        val digests = signers.map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }.toSet()
        return SenderPackageResolution.Resolved(
            ResolvedAndroidPackageIdentity(info.packageName, application.uid, info.longVersionCode, digests),
        )
    }
}
