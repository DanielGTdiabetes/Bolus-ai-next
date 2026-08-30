package org.bolusai.engine

/**
 * Identifies the shared engine artifact without implementing clinical behavior.
 *
 * Clinical rule versions will be introduced only after their provenance and
 * approval contracts exist. This technical identifier must not be interpreted
 * as authority to calculate or recommend a dose.
 */
public object EngineIdentity {
    public const val ARTIFACT_NAME: String = "bolus-engine"
    public const val IMPLEMENTS_CLINICAL_RULES: Boolean = false
}
