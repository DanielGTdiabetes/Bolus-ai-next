package org.bolusai.next.profile

import org.bolusai.profile.ProfileCodec
import org.bolusai.profile.Sha256
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/** The shared pure-Kotlin SHA-256 must agree with the platform implementation on arbitrary input. */
class ProfileFingerprintPlatformTest {
    private fun platform(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun sharedSha256MatchesJavaSecurityForEveryPaddingBoundary() {
        val random = Random(12_345)
        for (size in 0..300) {
            val bytes = random.nextBytes(size)
            assertEquals("size $size", platform(bytes), Sha256.hex(bytes))
        }
    }

    @Test fun profileFingerprintIsTheDigestOfTheCanonicalUtf8Text() {
        val content = org.bolusai.profile.ProfileContent.empty()
        assertEquals(platform(ProfileCodec.encode(content).toByteArray(Charsets.UTF_8)), ProfileCodec.sha256(content))
    }
}
