package org.bolusai.next.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Change detector for the frozen schema text (ADR 0014, section 8.4, ADR 0025 and ADR 0027). The v1 digest was computed from the
 * SCHEMA list at e78563a and ddee402 (identical); editing any list breaks the verification of files already on devices.
 */
class ClinicalProfileSchemaTextTest {
    private fun digest(statements: List<String>) = MessageDigest.getInstance("SHA-256")
        .digest(statements.joinToString("\n;\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test fun v1TextIsExactlyTheReleasedOne() {
        assertEquals(7, ClinicalProfileSchema.SCHEMA_V1.size)
        assertEquals("077195d2b5c56e06cf65c45fdadfa989eda4209f652a1237221d3dad2f41dbb8", digest(ClinicalProfileSchema.SCHEMA_V1))
    }

    @Test fun v2AddsOneTableTwoIndexesAndSixTriggersWithoutChangingV1() {
        assertEquals(ClinicalProfileSchema.SCHEMA_V1 + ClinicalProfileSchema.V2_ADDITIONS, ClinicalProfileSchema.SCHEMA_V2)
        val additions = ClinicalProfileSchema.V2_ADDITIONS
        assertEquals(1, additions.count { it.startsWith("CREATE TABLE ") })
        assertEquals(2, additions.count { it.contains(" INDEX ") && it.startsWith("CREATE ") })
        assertEquals(6, additions.count { it.startsWith("CREATE TRIGGER ") })
        assertTrue(additions.none { it.contains("profile_versions (") || it.contains("profile_segments (") })
        assertEquals("3e83172d0b53370ffced94f3cc36444bc785f9f127b09bb034a18efac6461aba", digest(additions))
    }

    @Test fun v3AddsTheLimitsTableAndThreeTriggersWithoutChangingV1OrV2() {
        // Frozen when v3 was introduced (ADR 0025); files written by builds with v3 are verified against it.
        assertEquals(ClinicalProfileSchema.SCHEMA_V2 + ClinicalProfileSchema.V3_ADDITIONS, ClinicalProfileSchema.SCHEMA_V3)
        val additions = ClinicalProfileSchema.V3_ADDITIONS
        assertEquals(1, additions.count { it.startsWith("CREATE TABLE profile_limits (") })
        assertEquals(0, additions.count { it.contains(" INDEX ") })
        assertEquals(3, additions.count { it.startsWith("CREATE TRIGGER profile_limits_") })
        assertEquals("077195d2b5c56e06cf65c45fdadfa989eda4209f652a1237221d3dad2f41dbb8", digest(ClinicalProfileSchema.SCHEMA_V1))
        assertEquals("3e83172d0b53370ffced94f3cc36444bc785f9f127b09bb034a18efac6461aba", digest(ClinicalProfileSchema.V2_ADDITIONS))
        assertEquals("fa4510b5a1340e7b4ac3438ed09218e3c2d115a4b047d0a2ec815345c065052d", digest(additions))
    }

    @Test fun v4AddsTheInsulinCurveTableAndThreeTriggersWithoutChangingEarlierVersions() {
        // Frozen when v4 was introduced (ADR 0027); files written by builds with v4 are verified against it.
        assertEquals(ClinicalProfileSchema.SCHEMA_V3 + ClinicalProfileSchema.V4_ADDITIONS, ClinicalProfileSchema.SCHEMA_V4)
        val additions = ClinicalProfileSchema.V4_ADDITIONS
        assertEquals(1, additions.count { it.startsWith("CREATE TABLE profile_insulin_curve (") })
        assertEquals(0, additions.count { it.contains(" INDEX ") })
        assertEquals(3, additions.count { it.startsWith("CREATE TRIGGER profile_insulin_curve_") })
        assertEquals("077195d2b5c56e06cf65c45fdadfa989eda4209f652a1237221d3dad2f41dbb8", digest(ClinicalProfileSchema.SCHEMA_V1))
        assertEquals("3e83172d0b53370ffced94f3cc36444bc785f9f127b09bb034a18efac6461aba", digest(ClinicalProfileSchema.V2_ADDITIONS))
        assertEquals("fa4510b5a1340e7b4ac3438ed09218e3c2d115a4b047d0a2ec815345c065052d", digest(ClinicalProfileSchema.V3_ADDITIONS))
        assertEquals("4bc0475501b47b4114b9da1042bdb5f9afc13759d3540554524c3020bba0bb6f", digest(additions))
    }
}
