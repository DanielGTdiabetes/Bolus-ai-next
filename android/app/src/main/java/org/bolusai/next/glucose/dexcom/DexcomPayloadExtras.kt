package org.bolusai.next.glucose.dexcom

import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.PayloadValue

/** Minimal read-only view of an extras container, so the type mapping is testable without Android (ADR 0019). */
internal interface ExtrasView {
    fun keys(): Set<String>

    /** The runtime value for [key], or a nested [ExtrasView] for a nested container. */
    fun value(key: String): Any?
}

/** Android could not unparcel the extras. Only raised by the Android view, never by the type mapping. */
internal class ExtrasUnreadableException : RuntimeException("dexcom_extras.unreadable")

internal sealed interface DexcomExtrasRead {
    data class Read(val payload: DexcomG7Payload) : DexcomExtrasRead

    /** Android refused to unparcel some extra. The whole message is unreadable (ADR 0019, T3). */
    data object Unreadable : DexcomExtrasRead
}

/**
 * Copies action and extras into the parser's typed payload without converting between types (ADR 0019, T2):
 * `String` → Text, `Int` → Int32, `Long` → Int64, nested container → Group, null → Null, anything else →
 * Unsupported with its technical class name. Call it only inside DexcomAuthenticatedIngress.afterAuthentication.
 */
internal object DexcomPayloadExtras {
    fun read(action: String?, extras: ExtrasView?): DexcomExtrasRead = try {
        DexcomExtrasRead.Read(DexcomG7Payload(action, extras?.let(::group) ?: emptyMap()))
    } catch (_: ExtrasUnreadableException) {
        DexcomExtrasRead.Unreadable
    }

    private fun group(view: ExtrasView): Map<String, PayloadValue> =
        view.keys().associateWith { key -> value(view.value(key)) }

    private fun value(raw: Any?): PayloadValue = when (raw) {
        null -> PayloadValue.Null
        is String -> PayloadValue.Text(raw)
        is Int -> PayloadValue.Int32(raw)
        is Long -> PayloadValue.Int64(raw)
        is ExtrasView -> PayloadValue.Group(group(raw))
        else -> PayloadValue.Unsupported(raw.javaClass.name)
    }
}

/** Reads the Intent of the current onReceive. Constructing it reads nothing. */
internal fun Intent.readDexcomPayload(): DexcomExtrasRead =
    DexcomPayloadExtras.read(action, extras?.let(::BundleExtrasView))

/** Android view of a Bundle. Nested Bundles become nested views; unparcel failures become [ExtrasUnreadableException]. */
internal class BundleExtrasView(private val bundle: Bundle) : ExtrasView {
    override fun keys(): Set<String> = unparcel { bundle.keySet().toSet() }

    override fun value(key: String): Any? {
        // The untyped getter is the only way to observe the runtime type the producer sent without coercing it.
        @Suppress("DEPRECATION")
        val raw = unparcel { bundle.get(key) }
        return if (raw is Bundle) BundleExtrasView(raw) else raw
    }

    private inline fun <T> unparcel(block: () -> T): T = try {
        block()
    } catch (_: BadParcelableException) {
        throw ExtrasUnreadableException()
    }
}
