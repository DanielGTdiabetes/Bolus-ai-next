package org.bolusai.next.glucose.dexcom

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.engine.UnavailabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 34)
class AndroidDexcomSenderEvidenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sharedIdentityAuthenticatesDifferentUidWithActualCurrentCertificate() {
        val result = exchange(shareIdentity = true)
        assertTrue(result is DexcomSenderAuthenticationResult.Authenticated)
        result as DexcomSenderAuthenticationResult.Authenticated
        assertEquals(FIXTURE_PACKAGE, result.packageName)
        assertNotEquals(Process.myUid(), result.uid)
        assertEquals(fixtureInfo().applicationInfo!!.uid, result.uid)
        assertEquals(fixtureInfo().longVersionCode, result.versionCode)
        assertEquals(fixtureDigests(), result.signerCertificateSha256Digests)
    }

    @Test
    fun omittedIdentitySharingRejectsDespiteInstalledTrustedPackage() {
        assertFailure(exchange(shareIdentity = false), DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED)
    }

    @Test
    fun forgedPackageExtraCannotReplaceSystemAttributedPackage() {
        assertFailure(
            exchange(shareIdentity = true, policy = policy(packageName = "example.forged.package")),
            DexcomSenderAuthenticationFailure.PACKAGE_MISMATCH,
        )
    }

    @Test
    fun realSenderWithUnapprovedCertificateIsRejected() {
        val wrongDigest = if ("0".repeat(64) in fixtureDigests()) "1".repeat(64) else "0".repeat(64)
        assertFailure(
            exchange(shareIdentity = true, policy = policy(digests = setOf(wrongDigest))),
            DexcomSenderAuthenticationFailure.CERTIFICATE_MISMATCH,
        )
    }

    @Test
    fun realSenderWithUnapprovedVersionIsRejected() {
        assertFailure(
            exchange(shareIdentity = true, policy = policy(version = fixtureInfo().longVersionCode + 1)),
            DexcomSenderAuthenticationFailure.VERSION_NOT_APPROVED,
        )
    }

    @Test
    fun pendingPolicyRejectsRealBroadcastBeforeReadingPlatformEvidence() {
        assertFailure(
            exchange(shareIdentity = true, policy = DexcomSenderTrustPolicy.Pending),
            DexcomSenderAuthenticationFailure.POLICY_NOT_APPROVED,
        )
    }

    @Test
    fun nonexistentPackageResolutionIsExplicit() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }
        assertEquals(
            SenderPackageResolution.NotFound,
            AndroidDexcomSenderEvidenceSource(receiver, context.packageManager)
                .resolvePackage("org.bolusai.next.fixture.does.not.exist"),
        )
    }

    @Test
    fun productionCompositionRemainsBlockedAndHasNoReceiverOrClinicalPermissions() {
        val status = org.bolusai.next.glucose.PendingDexcomSource.readLatest()
        assertEquals(
            org.bolusai.next.glucose.LocalGlucoseSourceResult.Unavailable(UnavailabilityReason.POLICY_NOT_APPROVED),
            status,
        )
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of((PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS).toLong()),
        )
        assertTrue(info.receivers.isNullOrEmpty())
        assertTrue(info.requestedPermissions.orEmpty().none {
            it == "android.permission.INTERNET" || it.startsWith("com.dexcom.")
        })
    }

    private fun exchange(
        shareIdentity: Boolean,
        policy: DexcomSenderTrustPolicy = policy(),
    ): DexcomSenderAuthenticationResult {
        val responses = ArrayBlockingQueue<Result<DexcomSenderAuthenticationResult>>(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                responses.offer(runCatching {
                    var payloadReads = 0
                    val result = DexcomAuthenticatedIngress(policy).receive(
                        AndroidDexcomSenderEvidenceSource(this, context.packageManager),
                    ) { sender ->
                        payloadReads++
                        // Only a synthetic, non-clinical marker; never an identity source.
                        assertEquals("example.forged.package", intent.getStringExtra("packageName"))
                        sender
                    }
                    when (result) {
                        is DexcomIngressResult.Rejected -> {
                            assertEquals("Rejected broadcasts must not access extras", 0, payloadReads)
                            result.authentication
                        }
                        is DexcomIngressResult.Handled -> {
                            assertEquals("Authenticated broadcasts dispatch exactly once", 1, payloadReads)
                            result.value
                        }
                    }
                })
            }
        }
        context.registerReceiver(receiver, IntentFilter(PROBE_ACTION), Context.RECEIVER_EXPORTED)
        try {
            context.sendBroadcast(
                Intent("org.bolusai.next.senderfixture.TRIGGER")
                    .setComponent(ComponentName(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.SyntheticSenderReceiver"))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("shareIdentity", shareIdentity),
            )
            return checkNotNull(responses.poll(10, TimeUnit.SECONDS)) {
                "Synthetic sender did not respond; verify the fixture APK is installed"
            }.getOrThrow()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun fixtureInfo() = context.packageManager.getPackageInfo(
        FIXTURE_PACKAGE,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    )

    // Anchors discovered here belong only to this synthetic test, never to production policy.
    private fun fixtureDigests() = fixtureInfo().signingInfo!!.apkContentsSigners.map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.toSet()

    private fun policy(
        packageName: String = FIXTURE_PACKAGE,
        version: Long = fixtureInfo().longVersionCode,
        digests: Set<String> = fixtureDigests(),
    ) = DexcomSenderTrustPolicy.Approved(packageName, listOf(DexcomSenderReleaseAnchor(version, digests)))

    private fun assertFailure(result: DexcomSenderAuthenticationResult, expected: DexcomSenderAuthenticationFailure) {
        assertTrue(result is DexcomSenderAuthenticationResult.Rejected)
        assertEquals(expected, (result as DexcomSenderAuthenticationResult.Rejected).failure)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "org.bolusai.next.senderfixture"
        const val PROBE_ACTION = "org.bolusai.next.senderfixture.IDENTITY_PROBE"
    }
}
