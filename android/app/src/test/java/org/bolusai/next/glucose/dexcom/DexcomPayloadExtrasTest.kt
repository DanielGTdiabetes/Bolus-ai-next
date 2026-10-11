package org.bolusai.next.glucose.dexcom

import org.bolusai.dexcomg7.DexcomG7Payload
import org.bolusai.dexcomg7.PayloadValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Synthetic extras only (ADR 0019). The Android Bundle view is covered by DexcomBundleExtrasDeviceTest. */
class DexcomPayloadExtrasTest {
    private class MapExtras(private val values: Map<String, Any?>) : ExtrasView {
        val reads = mutableListOf<String>()
        override fun keys(): Set<String> = values.keys
        override fun value(key: String): Any? {
            reads += key
            return values[key]
        }
    }

    @Test
    fun `maps every runtime type without converting between types`() {
        val nested = MapExtras(mapOf("i" to 1, "l" to 1L, "s" to "x", "n" to null))
        val read = DexcomPayloadExtras.read(
            "synthetic.ACTION",
            MapExtras(
                mapOf(
                    "text" to "value",
                    "int" to 7,
                    "long" to 7L,
                    "null" to null,
                    "group" to nested,
                    "short" to 7.toShort(),
                    "byte" to 7.toByte(),
                    "double" to 7.0,
                    "boolean" to true,
                    "chars" to charArrayOf('a'),
                ),
            ),
        )
        val expected = DexcomG7Payload(
            "synthetic.ACTION",
            mapOf(
                "text" to PayloadValue.Text("value"),
                "int" to PayloadValue.Int32(7),
                "long" to PayloadValue.Int64(7),
                "null" to PayloadValue.Null,
                "group" to PayloadValue.Group(
                    mapOf(
                        "i" to PayloadValue.Int32(1),
                        "l" to PayloadValue.Int64(1),
                        "s" to PayloadValue.Text("x"),
                        "n" to PayloadValue.Null,
                    ),
                ),
                "short" to PayloadValue.Unsupported("java.lang.Short"),
                "byte" to PayloadValue.Unsupported("java.lang.Byte"),
                "double" to PayloadValue.Unsupported("java.lang.Double"),
                "boolean" to PayloadValue.Unsupported("java.lang.Boolean"),
                "chars" to PayloadValue.Unsupported("[C"),
            ),
        )
        assertEquals(DexcomExtrasRead.Read(expected), read)
    }

    @Test
    fun `absent extras and absent action stay absent`() {
        assertEquals(DexcomExtrasRead.Read(DexcomG7Payload(null, emptyMap())), DexcomPayloadExtras.read(null, null))
    }

    @Test
    fun `an unreadable extra makes the whole message unreadable`() {
        val unreadable = object : ExtrasView {
            override fun keys(): Set<String> = setOf("a", "b")
            override fun value(key: String): Any? = if (key == "b") throw ExtrasUnreadableException() else 1
        }
        assertEquals(DexcomExtrasRead.Unreadable, DexcomPayloadExtras.read("a", unreadable))
        val unreadableKeys = object : ExtrasView {
            override fun keys(): Set<String> = throw ExtrasUnreadableException()
            override fun value(key: String): Any? = error("unexpected")
        }
        assertEquals(DexcomExtrasRead.Unreadable, DexcomPayloadExtras.read("a", unreadableKeys))
        val nestedUnreadable = MapExtras(mapOf("group" to unreadable))
        assertEquals(DexcomExtrasRead.Unreadable, DexcomPayloadExtras.read("a", nestedUnreadable))
    }

    @Test
    fun `other defects propagate unchanged`() {
        val defect = IllegalStateException("synthetic defect")
        val broken = object : ExtrasView {
            override fun keys(): Set<String> = setOf("a")
            override fun value(key: String): Any? = throw defect
        }
        assertSame(defect, assertThrows(IllegalStateException::class.java) { DexcomPayloadExtras.read("a", broken) })
    }

    @Test
    fun `each extra is read once`() {
        val extras = MapExtras(mapOf("a" to 1, "b" to "x"))
        DexcomPayloadExtras.read("a", extras)
        assertEquals(listOf("a", "b"), extras.reads.sorted())
    }
}
