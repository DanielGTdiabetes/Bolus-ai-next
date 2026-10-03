package org.bolusai.profile

/** Stable codes. Storage failures are never presented as a missing profile. */
enum class ProfileFailure(val code: String) {
    READ_FAILED("profile.storage.read_failed"),
    SAVE_FAILED("profile.storage.save_failed"),
    CONFLICT("profile.storage.revision_conflict"),
    INVALID_RECORD("profile.storage.invalid_record"),
    UNSUPPORTED_SCHEMA("profile.storage.unsupported_schema"),
    CORRUPT_STORAGE("profile.storage.corrupt"),
    UNCHANGED("profile.edit.unchanged"),
    INVALID_SEGMENTS("profile.edit.invalid_segments"),
    INVALID_PARAMETERS("profile.edit.invalid_parameters"),
    INVALID_VALUE("profile.edit.invalid_value"),
    AMBIGUOUS_DECIMAL("profile.edit.ambiguous_decimal"),
    VALUE_NOT_REPRESENTABLE("profile.edit.value_not_representable"),
    INVALID_TIME_ZONE("profile.edit.invalid_time_zone"),
    UNIT_REQUIRED("profile.edit.unit_required"),
    UNIT_CHANGE_WITH_VALUES("profile.edit.unit_change_with_values"),
    INVALID_ORIGIN("profile.edit.invalid_origin"),
    ORIGIN_NOT_ENABLED("profile.origin.not_enabled"),
    /** Reserved (ADR 0012 P5, superseded by ADR 0013). Never produced again and never reused. */
    SEGMENTS_UI_UNAVAILABLE("profile.edit.segments_ui_unavailable"),
    SPLIT_OUT_OF_RANGE("profile.edit.split_out_of_range"),
    BOUNDARY_OUT_OF_RANGE("profile.edit.boundary_out_of_range"),
    MERGE_VALUES_DIFFER("profile.edit.merge_values_differ"),
    STALE_SEGMENT("profile.edit.stale_segment"),
    INVALID_TIME("profile.edit.invalid_time"),
    SEGMENT_LIMIT_REACHED("profile.edit.segment_limit_reached"),
    SCHEDULE_LOCKED("profile.edit.schedule_locked"),
    /** Operation errors of data confirmation (ADR 0014, section 7.1). Returned to the caller; never an input state. */
    CONFIRMATION_OPERATION_MISMATCH("profile.confirmation.operation_mismatch"),
    CONFIRMATION_STATE_CHANGED("profile.confirmation.state_changed"),
    CONFIRMATION_STALE_VERSION("profile.confirmation.stale_version"),
    CONFIRMATION_INCOMPLETE("profile.confirmation.incomplete"),
    CONFIRMATION_ALREADY_ACTIVE("profile.confirmation.already_active"),
    CONFIRMATION_NOT_ACTIVE("profile.confirmation.not_active"),
}
