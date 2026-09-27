package org.bolusai.profile

/**
 * Canonical text of [ProfileContent] (ADR 0012, section 4.5). Metadata is excluded, so equal content yields the
 * same fingerprint. Parameters are ordered by code and segments by start minute; "~" marks not configured.
 */
object ProfileCodec {
    private const val HEADER = "bolus-ai-next/clinical-profile/content"
    private const val MISSING = "~"

    fun encode(content: ProfileContent): String = buildString {
        append(HEADER).append('\n')
        append("schema=").append(content.schemaVersion).append('\n')
        append("glucose_unit=").append(when (val unit = content.glucoseUnit) {
            Setting.NotConfigured -> MISSING
            is Setting.Declared -> unit.value.code
        }).append('\n')
        append("time_zone=").append(when (val zone = content.timeZone) {
            Setting.NotConfigured -> MISSING
            is Setting.Declared -> zone.value.id
        }).append('\n')
        content.schedules.sortedBy { it.parameter.code }.forEach { schedule ->
            append("parameter=").append(schedule.parameter.code).append('\n')
            schedule.segments.sortedBy { it.startMinute }.forEach { segment ->
                append("segment=").append(segment.startMinute).append('-').append(segment.endMinute).append(':')
                append(when (val value = segment.value) {
                    ProfileValue.NotConfigured -> MISSING
                    is ProfileValue.Entered -> value.decimal.text
                }).append('\n')
            }
        }
    }

    /** Strict inverse of [encode]; anything else, including a non-canonical spelling, returns null. */
    fun decode(text: String): ProfileContent? = try {
        val lines = text.split('\n')
        require(lines.last().isEmpty() && lines.first() == HEADER)
        var index = 1
        fun field(key: String): String {
            val line = lines[index++]
            require(line.startsWith("$key="))
            return line.substring(key.length + 1)
        }
        val schema = field("schema").toInt()
        val catalog = requireNotNull(ProfileCatalog.parameters(schema))
        val unitCode = field("glucose_unit")
        val unit: Setting<GlucoseUnit> = if (unitCode == MISSING) Setting.NotConfigured
            else Setting.Declared(requireNotNull(GlucoseUnit.fromCode(unitCode)))
        val zoneId = field("time_zone")
        val zone: Setting<ProfileTimeZone> = if (zoneId == MISSING) Setting.NotConfigured
            else Setting.Declared(ProfileTimeZone(zoneId))
        val schedules = mutableMapOf<ProfileParameter, ParameterSchedule>()
        while (index < lines.size - 1) {
            val parameter = requireNotNull(ProfileParameter.fromCode(field("parameter")))
            val segments = mutableListOf<TimeSegment>()
            while (index < lines.size - 1 && lines[index].startsWith("segment=")) {
                val body = field("segment")
                val (range, value) = body.split(':', limit = 2).also { require(it.size == 2) }
                val (start, end) = range.split('-').also { require(it.size == 2) }.map { it.toInt() }
                segments.add(TimeSegment(start, end,
                    if (value == MISSING) ProfileValue.NotConfigured else ProfileValue.Entered(CanonicalDecimal(value))))
            }
            require(schedules.put(parameter, ParameterSchedule(parameter, segments)) == null)
        }
        val content = ProfileContent(schema, unit, zone, catalog.map { requireNotNull(schedules[it]) })
        content.takeIf { encode(it) == text }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    fun sha256(content: ProfileContent): String = Sha256.hex(encode(content).encodeToByteArray())
}

/** FIPS 180-4 SHA-256 in pure Kotlin so Android and iOS derive identical fingerprints without platform crypto. */
object Sha256 {
    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()
    private val INITIAL = longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19).map { it.toInt() }.toIntArray()

    private fun rotr(x: Int, n: Int) = (x ushr n) or (x shl (32 - n))

    fun hex(input: ByteArray): String {
        val h = INITIAL.copyOf()
        val bitLength = input.size.toLong() * 8
        val padded = ByteArray(((input.size + 9 + 63) / 64) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (chunk in padded.indices step 64) {
            for (i in 0 until 16) {
                val o = chunk + 4 * i
                w[i] = ((padded[o].toInt() and 0xff) shl 24) or ((padded[o + 1].toInt() and 0xff) shl 16) or
                    ((padded[o + 2].toInt() and 0xff) shl 8) or (padded[o + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return h.joinToString("") { (it.toLong() and 0xffffffffL).toString(16).padStart(8, '0') }
    }
}
