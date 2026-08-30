package org.bolusai.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EngineIdentityTest {
    @Test
    fun scaffoldDeclaresThatClinicalRulesAreNotImplemented(): Unit {
        assertEquals("bolus-engine", EngineIdentity.ARTIFACT_NAME)
        assertFalse(EngineIdentity.IMPLEMENTS_CLINICAL_RULES)
    }
}
