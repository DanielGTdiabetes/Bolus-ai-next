package org.bolusai.next.mfp

import android.annotation.SuppressLint
import android.content.Context

/**
 * The switch of the MyFitnessPal link (ADR 0032, "Interruptor"). It is born off. While it is off Next builds no
 * session and opens no connection; the Internet permission of the app is used by nothing else.
 */
internal interface MfpLinkSwitch {
    val enabled: Boolean

    /** Returns false when the choice could not be stored; the switch then keeps its earlier value. */
    fun set(enabled: Boolean): Boolean
}

/** The switch of the installed app, kept in its private preferences and excluded from backups like all its data. */
internal class StoredMfpLinkSwitch(context: Context) : MfpLinkSwitch {
    private val preferences = context.applicationContext.getSharedPreferences("mfp-link", Context.MODE_PRIVATE)

    override val enabled: Boolean get() = preferences.getBoolean(KEY, false)

    @SuppressLint("UseKtx") // The KTX form drops the result of commit(); a switch that was not stored must say so.
    override fun set(enabled: Boolean): Boolean = preferences.edit().putBoolean(KEY, enabled).commit()

    private companion object {
        const val KEY = "enabled"
    }
}

/** A switch that forgets: what instrumentation gets, so no test changes the switch of the installed app. */
internal class VolatileMfpLinkSwitch(@Volatile private var value: Boolean = false) : MfpLinkSwitch {
    override val enabled: Boolean get() = value

    override fun set(enabled: Boolean): Boolean {
        value = enabled
        return true
    }
}
