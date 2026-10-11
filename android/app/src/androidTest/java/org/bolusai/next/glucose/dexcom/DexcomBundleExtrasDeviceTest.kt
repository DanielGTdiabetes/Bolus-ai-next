package org.bolusai.next.glucose.dexcom

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.PayloadValue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0019: the Android Bundle view keeps the runtime types the producer sent, also after a Parcel round trip like
 * the one a broadcast crosses between processes. Synthetic values only; no receiver is registered.
 */
@RunWith(AndroidJUnit4::class)
class DexcomBundleExtrasDeviceTest {
    @Test
    fun bundleTypesMapWithoutConversion() {
        assertEquals(DexcomExtrasRead.Read(expected()), intent().readDexcomPayload())
    }

    @Test
    fun parcelRoundTripKeepsTheSameTypes() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(extras())
            parcel.setDataPosition(0)
            val copy = checkNotNull(parcel.readBundle(javaClass.classLoader))
            val read = Intent(ACTION).putExtras(copy).readDexcomPayload()
            assertEquals(DexcomExtrasRead.Read(expected()), read)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun intentWithoutExtrasHasAnEmptyPayload() {
        assertEquals(DexcomExtrasRead.Read(DexcomG7Payload(ACTION, emptyMap())), Intent(ACTION).readDexcomPayload())
    }

    private fun intent(): Intent = Intent(ACTION).putExtras(extras())

    private fun extras(): Bundle = Bundle().apply {
        putString("text", "value")
        putInt("int", 7)
        putLong("long", 7L)
        putString("null", null)
        putShort("short", 7)
        putDouble("double", 7.0)
        putBundle("group", Bundle().apply {
            putInt("i", 1)
            putLong("l", 1L)
            putBundle("deeper", Bundle().apply { putString("s", "x") })
        })
    }

    private fun expected() = DexcomG7Payload(
        ACTION,
        mapOf(
            "text" to PayloadValue.Text("value"),
            "int" to PayloadValue.Int32(7),
            "long" to PayloadValue.Int64(7),
            "null" to PayloadValue.Null,
            "short" to PayloadValue.Unsupported("java.lang.Short"),
            "double" to PayloadValue.Unsupported("java.lang.Double"),
            "group" to PayloadValue.Group(
                mapOf(
                    "i" to PayloadValue.Int32(1),
                    "l" to PayloadValue.Int64(1),
                    "deeper" to PayloadValue.Group(mapOf("s" to PayloadValue.Text("x"))),
                ),
            ),
        ),
    )

    private companion object {
        const val ACTION = "org.bolusai.next.test.SYNTHETIC_ACTION"
    }
}
