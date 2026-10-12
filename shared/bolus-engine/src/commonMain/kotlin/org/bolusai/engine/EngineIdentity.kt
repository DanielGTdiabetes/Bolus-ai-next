package org.bolusai.engine

/**
 * Identifies the shared engine artifact.
 *
 * Since 2026-10-12 the engine contains candidate rules observed in Legacy (ADR 0024), implemented under the owner's
 * development and testing authorization (AGENTS.md §3). None of them is clinically approved: every result is a test
 * result that cannot be used for treatment. [IMPLEMENTS_APPROVED_CLINICAL_RULES] stays false until a rule is approved.
 */
public object EngineIdentity {
    public const val ARTIFACT_NAME: String = "bolus-engine"
    public const val VERSION: String = "0.2.0-candidate"
    public const val IMPLEMENTS_APPROVED_CLINICAL_RULES: Boolean = false
}
