package org.bolusai.next.glucose.dexcom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.DexcomG7RejectionCode
import org.bolusai.dexcomg7.ObservationIds
import org.bolusai.dexcomg7.ObservationRead
import org.bolusai.dexcomg7.ObservedReadingStatus
import org.bolusai.dexcomg7.StoredMessageOutcome
import org.bolusai.next.glucose.dexcom.storage.AndroidTransmitterHasher
import org.bolusai.next.glucose.dexcom.storage.DexcomG7Recording
import org.bolusai.next.glucose.dexcom.storage.SqliteDexcomObservationStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR 0022 and ADR 0023: the receiver is declared disabled and guarded by the Dexcom permission, so a sender without
 * it gets nothing through. The strict path, injected through a dynamically registered probe with a synthetic policy,
 * records only what an authenticated sender sent; the permission gate itself is covered by JVM tests. Synthetic sender APK, synthetic
 * policy, synthetic payloads and a synthetic database file only; the app's own log is never opened (DexcomStorageGuard).
 */
@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 34)
class DexcomG7ReceiverDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val component = ComponentName(context, DexcomG7Receiver::class.java)
    private val receipts = ArrayBlockingQueue<DexcomReceipt>(8)
    private val opens = AtomicInteger()
    private var n = 0
    private val ids = ObservationIds { "receiver-test-${++n}" }

    /** Same receiver code; only the policy, the store and, when given, the sender evidence are synthetic. */
    private inner class ProbeReceiver(
        private val policy: DexcomSenderTrustPolicy,
        private val evidence: ((Context) -> DexcomSenderEvidenceSource)? = null,
        private val open: (Context) -> SqliteDexcomObservationStore = {
            SqliteDexcomObservationStore(it, SYNTHETIC_DB, ids, AndroidTransmitterHasher)
        },
    ) : DexcomG7Receiver() {
        override fun trustPolicy(): DexcomSenderTrustPolicy = policy
        override fun senderEvidence(context: Context): DexcomSenderEvidenceSource =
            evidence?.invoke(context) ?: super.senderEvidence(context)
        override fun openStore(context: Context): SqliteDexcomObservationStore {
            opens.incrementAndGet()
            return open(context)
        }
    }

    @Before fun isolate() {
        context.deleteDatabase(SYNTHETIC_DB)
        DexcomG7Receiver.receipts = { receipts.offer(it) }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, context.packageManager.getComponentEnabledSetting(component))
    }

    @After fun restore() {
        DexcomG7Receiver.receipts = null
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP)
        context.deleteDatabase(SYNTHETIC_DB)
    }

    @Test
    fun manifestDeclaresOneDisabledExportedReceiverForTheObservedActionGuardedByTheDexcomPermission() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(
                (PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS).toLong(),
            ),
        )
        val receiver = info.receivers.orEmpty().single()
        assertEquals(DexcomG7Receiver::class.java.name, receiver.name)
        assertFalse(receiver.enabled)
        assertTrue(receiver.exported)
        // ADR 0023, part I, point 1 and I-6: the receiver requires the producer's permission and Next requests it.
        assertEquals(DexcomApprovedPermissionSet.PERMISSION, receiver.permission)
        assertEquals(listOf(DexcomApprovedPermissionSet.PERMISSION),
            info.requestedPermissions.orEmpty().filter { it.startsWith("com.dexcom.") })
        assertTrue(info.requestedPermissions.orEmpty().none {
            it == "android.permission.INTERNET" || it == "android.permission.QUERY_ALL_PACKAGES"
        })
        val observed = Intent(DexcomReceiverConfiguration.ACTION).setPackage(context.packageName)
        assertTrue(context.packageManager.queryBroadcastReceivers(observed, PackageManager.ResolveInfoFlags.of(0)).isEmpty())
        assertEquals(
            listOf(DexcomG7Receiver::class.java.name),
            context.packageManager.queryBroadcastReceivers(
                observed, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()),
            ).map { it.activityInfo.name },
        )
    }

    @Test
    fun disabledByDefaultReceivesNothing() {
        val existedBefore = realLog().exists()
        context.sendBroadcast(Intent(DexcomReceiverConfiguration.ACTION).setComponent(component).putExtras(payload(FIXTURE_PACKAGE)))
        assertNull(receipts.poll(3, TimeUnit.SECONDS))
        assertEquals(existedBefore, realLog().exists())
    }

    @Test
    fun enabledReceiverGetsNothingFromASenderWithoutTheDexcomPermission() {
        // ADR 0023 acceptance: the synthetic sender does not hold the permission, so Android never delivers, even with
        // the observed action and extras copied. Nothing is read or stored.
        val existedBefore = realLog().exists()
        enable()
        fixture(shareIdentity = true, payload(FIXTURE_PACKAGE))
        assertNull(receipts.poll(5, TimeUnit.SECONDS))
        assertEquals(existedBefore, realLog().exists())
    }

    @Test
    fun enabledReceiverIgnoresAnotherActionWithoutCheckingTheGate() {
        enable()
        context.sendBroadcast(Intent("org.bolusai.next.test.OTHER_ACTION").setComponent(component))
        // The sender here is Next itself: Android delivers only if Next already holds the permission (I-6, by adb).
        if (context.checkSelfPermission(DexcomApprovedPermissionSet.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            assertEquals(DexcomReceipt.IgnoredAction("org.bolusai.next.test.OTHER_ACTION"), next())
        } else {
            assertNull(receipts.poll(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun authenticatedSyntheticMessageIsRecordedAndStaysBlocked() {
        val receipt = probe(policy(), shareIdentity = true, payload(FIXTURE_PACKAGE)) as DexcomReceipt.Recorded
        assertEquals(1, receipt.payloadReads)
        assertEquals(1, opens.get())
        assertEquals("input.glucose.policy_not_approved", (receipt.recording as DexcomG7Recording.Recorded).glucose.code)
        val log = readSynthetic()
        val message = log.messages.single()
        assertEquals(StoredMessageOutcome.OBSERVED, message.outcome)
        val sender = message.provenance as AuthenticatedSender
        assertEquals(FIXTURE_PACKAGE, sender.packageName)
        assertEquals(fixtureVersion(), sender.versionCode)
        assertEquals(fixtureDigests().sorted(), sender.signerCertificateSha256)
        assertEquals(ObservedReadingStatus.NOT_APPROVED_FOR_USE, log.readings.single().status)
    }

    @Test
    fun withoutSharedIdentityTheSenderIsRejectedBeforeOpeningTheStore() {
        val receipt = probe(policy(), shareIdentity = false, payload(FIXTURE_PACKAGE)) as DexcomReceipt.SenderRejected
        assertEquals(0, receipt.payloadReads)
        assertEquals(DexcomSenderAuthenticationFailure.IDENTITY_NOT_SHARED, receipt.outcome.authentication.failure)
        assertEquals(0, opens.get())
        assertFalse(context.getDatabasePath(SYNTHETIC_DB).exists())
    }

    @Test
    fun anUnapprovedVersionIsRejectedBeforeOpeningTheStore() {
        val receipt = probe(policy(version = fixtureVersion() + 1), shareIdentity = true, payload(FIXTURE_PACKAGE))
            as DexcomReceipt.SenderRejected
        assertEquals(DexcomSenderAuthenticationFailure.VERSION_NOT_APPROVED, receipt.outcome.authentication.failure)
        assertEquals(0, receipt.payloadReads)
        assertEquals(0, opens.get())
    }

    @Test
    fun aForgedPackageClaimIsStoredOnlyAsARejection() {
        val receipt = probe(policy(), shareIdentity = true, payload("example.forged.package")) as DexcomReceipt.Recorded
        assertEquals("input.glucose.parse_failed", (receipt.recording as DexcomG7Recording.Recorded).glucose.code)
        val log = readSynthetic()
        assertEquals(StoredMessageOutcome.PAYLOAD_REJECTED, log.messages.single().outcome)
        assertEquals(DexcomG7RejectionCode.PACKAGE_CLAIM_MISMATCH, log.messages.single().rejection?.code)
        assertTrue(log.readings.isEmpty())
    }

    @Test
    fun aStoreThatFailsToOpenIsDeliveredAsStorageFailedAndTheProcessSurvives() {
        val failing = ProbeReceiver(policy(), open = { error("synthetic open failure") })
        val receipt = probe(failing, shareIdentity = true, payload(FIXTURE_PACKAGE))
        assertEquals(DexcomReceipt.StorageFailed(DexcomStorageFailure.OPEN_FAILED, 1), receipt)
        assertEquals("input.glucose.persistence_failed", (receipt as DexcomReceipt.StorageFailed).glucose.code)
        assertFalse(context.getDatabasePath(SYNTHETIC_DB).exists())
        // Same process, same serial writer: had the failure escaped, the instrumentation would have died with it.
        val next = probe(policy(), shareIdentity = true, payload(FIXTURE_PACKAGE)) as DexcomReceipt.Recorded
        assertEquals("input.glucose.policy_not_approved", (next.recording as DexcomG7Recording.Recorded).glucose.code)
        assertEquals(2, opens.get())
    }

    @Test
    fun theBarrierDenyingTheRealLogIsAStorageFailureAndTheRealLogIsUntouched() {
        val existedBefore = realLog().exists()
        val real = ProbeReceiver(policy(), open = {
            SqliteDexcomObservationStore(it, SqliteDexcomObservationStore.DATABASE_NAME, ids, AndroidTransmitterHasher)
        })
        assertEquals(
            DexcomReceipt.StorageFailed(DexcomStorageFailure.OPEN_FAILED, 1),
            probe(real, shareIdentity = true, payload(FIXTURE_PACKAGE)),
        )
        assertEquals(existedBefore, realLog().exists())
    }

    @Test
    fun aSenderEvidenceFailureOnTheMainThreadIsIntakeFailedAndTheProcessSurvives() {
        // R11: onReceive runs on the main thread, where an escaping exception would kill the process.
        val failing = ProbeReceiver(policy(), evidence = { error("synthetic evidence failure") })
        val receipt = probe(failing, shareIdentity = true, payload(FIXTURE_PACKAGE))
        assertEquals(DexcomReceipt.IntakeFailed(DexcomIntakeFailure.AUTHENTICATION, 0), receipt)
        assertEquals("input.glucose.authentication_failed", (receipt as DexcomReceipt.IntakeFailed).glucose.code)
        assertEquals(0, opens.get())
        assertFalse(context.getDatabasePath(SYNTHETIC_DB).exists())
        // Same process: the next message is authenticated, read and recorded normally.
        val next = probe(policy(), shareIdentity = true, payload(FIXTURE_PACKAGE)) as DexcomReceipt.Recorded
        assertEquals("input.glucose.policy_not_approved", (next.recording as DexcomG7Recording.Recorded).glucose.code)
        assertEquals(1, next.payloadReads)
        assertEquals(1, opens.get())
    }

    private fun probe(policy: DexcomSenderTrustPolicy, shareIdentity: Boolean, payload: Bundle): DexcomReceipt =
        probe(ProbeReceiver(policy), shareIdentity, payload)

    private fun probe(receiver: ProbeReceiver, shareIdentity: Boolean, payload: Bundle): DexcomReceipt {
        context.registerReceiver(receiver, IntentFilter(DexcomReceiverConfiguration.ACTION), Context.RECEIVER_EXPORTED)
        try {
            fixture(shareIdentity, payload)
            return next()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun enable() = context.packageManager.setComponentEnabledSetting(
        component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
    )

    private fun fixture(shareIdentity: Boolean, payload: Bundle) = context.sendBroadcast(
        Intent("org.bolusai.next.senderfixture.TRIGGER_G7")
            .setComponent(ComponentName(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.SyntheticSenderReceiver"))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("shareIdentity", shareIdentity)
            .putExtra("payload", payload),
    )

    private fun next(): DexcomReceipt = checkNotNull(receipts.poll(10, TimeUnit.SECONDS)) {
        "No receipt; verify the synthetic sender APK is installed"
    }

    private fun realLog() = context.getDatabasePath(SqliteDexcomObservationStore.DATABASE_NAME)

    private fun readSynthetic(): ObservationRead.Loaded =
        SqliteDexcomObservationStore(context, SYNTHETIC_DB, ids, AndroidTransmitterHasher).use { it.read() } as ObservationRead.Loaded

    /** Synthetic values in the observed shape; never real Dexcom data. */
    private fun payload(packageClaim: String) = Bundle().apply {
        putString("sensorType", "G7")
        putString("packageName", packageClaim)
        putBundle("glucoseValues", Bundle().apply {
            putBundle("0", Bundle().apply {
                putInt("glucoseValue", 123)
                putLong("timestamp", 1_000L)
                putString("trendArrow", "SYNTHETIC_TREND")
                putString("transmitterId", "SYNTH-TX")
                putLong("egvTxTime", 77L)
            })
        })
    }

    private fun fixtureInfo() = context.packageManager.getPackageInfo(
        FIXTURE_PACKAGE,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    )

    private fun fixtureVersion() = fixtureInfo().longVersionCode

    // Anchors discovered here belong only to this synthetic test, never to production policy.
    private fun fixtureDigests() = fixtureInfo().signingInfo!!.apkContentsSigners.map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.toSet()

    private fun policy(version: Long = fixtureVersion()) =
        DexcomSenderTrustPolicy.Approved(FIXTURE_PACKAGE, listOf(DexcomSenderReleaseAnchor(version, fixtureDigests())))

    private companion object {
        const val FIXTURE_PACKAGE = "org.bolusai.next.senderfixture"
        const val SYNTHETIC_DB = "dexcom-receiver-device-test.db"
    }
}
