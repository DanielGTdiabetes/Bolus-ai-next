package org.bolusai.next.glucose.dexcom

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Effective state of the receiver component as Android reports it (ADR 0022, R1 and R9). */
internal enum class DexcomReceiverComponent(val code: String) {
    /** No override: the manifest value applies, which verify.ps1 keeps at `enabled="false"`. */
    DISABLED_BY_DEFAULT("disabled_by_default"),
    ENABLED("enabled"),
    DISABLED("disabled"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun of(setting: Int): DexcomReceiverComponent = when (setting) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> DISABLED_BY_DEFAULT
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> ENABLED
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> DISABLED
            else -> UNKNOWN
        }
    }
}

/**
 * Read-only receiver status for Diagnóstico. It never changes the component, the policy, a permission or a block.
 * [reception] holds the permission gate lines of ADR 0023: Next's own permission, the gate decision of a fresh
 * holder reading, whether visibility is proven and the package names Next can see holding the permission. They are
 * the evidence the Pixel test of I-3 compares with the L1 inventory; no glucose value or transmitter appears.
 */
internal data class DexcomReceiverStatus(
    val component: DexcomReceiverComponent,
    val policy: DexcomSenderTrustPolicy,
    val reception: List<String> = emptyList(),
) {
    fun lines(): List<String> = listOf(
        "dexcom_receiver.component=${component.code}",
        "dexcom_receiver.policy=${if (policy is DexcomSenderTrustPolicy.Pending) "pending" else "approved"}",
    )

    companion object {
        fun read(context: Context): DexcomReceiverStatus = DexcomReceiverStatus(
            DexcomReceiverComponent.of(
                context.packageManager.getComponentEnabledSetting(ComponentName(context, DexcomG7Receiver::class.java)),
            ),
            DexcomReceiverConfiguration.policy,
            receptionLines(nextHoldsDexcomPermission(context), AndroidHolderSnapshotSource(context)()),
        )

        fun receptionLines(nextHolds: Boolean, snapshot: HolderSnapshot): List<String> {
            val gate = when (val decision = DexcomPermissionSetPolicy.evaluate(snapshot)) {
                is PermissionGate.Open -> "open"
                is PermissionGate.Closed -> decision.cause.code
            }
            return listOf(
                "dexcom_reception.policy=${DexcomApprovedPermissionSet.POLICY_VERSION}",
                "dexcom_reception.next_permission=${if (nextHolds) "granted" else "missing"}",
                "dexcom_reception.gate_now=$gate",
            ) + when (snapshot) {
                is HolderSnapshot.Read -> listOf(
                    "dexcom_reception.full_visibility=${snapshot.fullVisibility}",
                    "dexcom_reception.visible_holders=${snapshot.holders.map { it.packageName }.sorted().joinToString(",")}",
                )
                HolderSnapshot.PlatformUnsupported -> listOf("dexcom_reception.snapshot=platform_unsupported")
                HolderSnapshot.QueryFailed -> listOf("dexcom_reception.snapshot=query_failed")
            }
        }
    }
}
