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
    SEGMENTS_UI_UNAVAILABLE("profile.edit.segments_ui_unavailable"),
}
