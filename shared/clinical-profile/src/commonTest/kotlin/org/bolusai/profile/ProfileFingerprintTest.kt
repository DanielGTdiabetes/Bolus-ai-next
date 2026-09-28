package org.bolusai.profile

import kotlin.test.*

class ProfileFingerprintTest {
    @Test fun sha256MatchesFips180Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
        assertEquals("cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            Sha256.hex(("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrs" +
                "mnopqrstnopqrstu").encodeToByteArray()))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }))
    }

    @Test fun canonicalTextIsStableAndRoundTrips() {
        val profile = content(ratio = entered("0"), sensitivity = ProfileValue.NotConfigured)
        val expected = """
            bolus-ai-next/clinical-profile/content
            schema=1
            glucose_unit=mg/dL
            time_zone=Europe/Madrid
            parameter=carb_ratio
            segment=0-1440:0
            parameter=glucose_target
            segment=0-1440:110
            parameter=insulin_sensitivity
            segment=0-1440:~

        """.trimIndent()
        assertEquals(expected, ProfileCodec.encode(profile))
        assertEquals(profile, ProfileCodec.decode(expected))
        assertEquals(ProfileContent.empty(), ProfileCodec.decode(ProfileCodec.encode(ProfileContent.empty())))
    }

    @Test fun decodingIsStrict() {
        val text = ProfileCodec.encode(content())
        listOf(
            text.replace("segment=0-1440:10\n", "segment=0-1440:10.0\n"),
            text.replace("schema=1", "schema=2"),
            text.replace("glucose_unit=mg/dL", "glucose_unit=mgdl"),
            text.replace("parameter=carb_ratio", "parameter=dia"),
            text.replace("segment=0-1440:10\n", "segment=0-600:10\n"),
            text.replace("segment=0-1440:10\n", "segment=+0-1440:10\n"),
            text.removeSuffix("\n"),
            text + "extra\n",
            "",
        ).forEach { assertNull(ProfileCodec.decode(it), it) }
    }

    @Test fun fingerprintIgnoresMetadataAndSeesEveryContentField() {
        val base = content()
        val sha = ProfileCodec.sha256(base)
        assertEquals(sha, ProfileCodec.sha256(content()))
        listOf(
            content(unit = mmol), content(zone = Setting.NotConfigured),
            content(zone = Setting.Declared(ProfileTimeZone("UTC"))), content(ratio = entered("10.5")),
            content(sensitivity = ProfileValue.NotConfigured), content(target = entered("0")),
            ProfileContent(1, mgdl, madrid, listOf(
                ParameterSchedule(ProfileParameter.CARB_RATIO, listOf(TimeSegment(0, 720, entered("10")),
                    TimeSegment(720, 1440, entered("10")))),
                ParameterSchedule.allDay(ProfileParameter.INSULIN_SENSITIVITY, entered("40")),
                ParameterSchedule.allDay(ProfileParameter.GLUCOSE_TARGET, entered("110")))),
        ).forEach { assertNotEquals(sha, ProfileCodec.sha256(it), it.toString()) }
        val one = ProfileVersion(1, base, sha, ProfileOrigin.MANUAL, null, 1, "a")
        val other = ProfileVersion(2, base, sha, ProfileOrigin.MANUAL, null, 999, "b")
        assertEquals(one.contentSha256, other.contentSha256)
    }

    @Test fun versionRejectsForeignFingerprintAndReservedOrigin() {
        val base = content()
        assertFailsWith<IllegalArgumentException> {
            ProfileVersion(1, base, ProfileCodec.sha256(content(unit = mmol, sensitivity = ProfileValue.NotConfigured,
                target = ProfileValue.NotConfigured)), ProfileOrigin.MANUAL, null, 1, "w")
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileVersion(1, base, ProfileCodec.sha256(base), ProfileOrigin.SYSTEM_PROPOSAL_ACCEPTED, null, 1, "w")
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileVersion(3, base, ProfileCodec.sha256(base), ProfileOrigin.RESTORED, null, 1, "w")
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileVersion(3, base, ProfileCodec.sha256(base), ProfileOrigin.RESTORED, 2, 1, "w")
        }
        assertFailsWith<IllegalArgumentException> {
            ProfileVersion(1, base, ProfileCodec.sha256(base), ProfileOrigin.MANUAL, null, 1, "línea\n")
        }
    }
}
