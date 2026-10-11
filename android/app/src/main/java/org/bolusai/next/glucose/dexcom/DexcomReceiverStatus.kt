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

/** Read-only receiver status for Diagnóstico. It never changes the component or the policy. */
internal data class DexcomReceiverStatus(val component: DexcomReceiverComponent, val policy: DexcomSenderTrustPolicy) {
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
        )
    }
}
