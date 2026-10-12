package org.bolusai.next.application

import org.bolusai.dexcomg7.ApprovedPermissionSet
import org.bolusai.dexcomg7.AuthenticatedSender
import org.bolusai.dexcomg7.LatestReadingPresentation
import org.bolusai.engine.candidate.CandidateEngine
import org.bolusai.engine.candidate.CandidateRequest
import org.bolusai.engine.candidate.CandidateResult
import org.bolusai.engine.candidate.CarbsOrigin
import org.bolusai.engine.candidate.CarbsValue
import org.bolusai.engine.candidate.GlucoseOrigin
import org.bolusai.engine.candidate.GlucoseUnitCode
import org.bolusai.engine.candidate.GlucoseValue
import org.bolusai.engine.candidate.Input
import org.bolusai.engine.candidate.IobValue
import org.bolusai.engine.candidate.ProfileSnapshot
import org.bolusai.engine.candidate.Quantity
import org.bolusai.engine.candidate.SegmentValue
import org.bolusai.meals.DraftField
import org.bolusai.meals.MealSelection
import org.bolusai.meals.NutritionBasis
import org.bolusai.profile.DecimalInput
import org.bolusai.profile.DecimalInputs
import org.bolusai.profile.GlucoseUnit
import org.bolusai.profile.ParameterSchedule
import org.bolusai.profile.ProfileContent
import org.bolusai.profile.ProfileGateState
import org.bolusai.profile.ProfileLimit
import org.bolusai.profile.ProfileParameter
import org.bolusai.profile.ProfileValue
import org.bolusai.profile.Setting
import org.bolusai.profile.VersionConfirmation
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * External inputs a result depends on; equality decides whether a stored result is still current. The profile
 * snapshot carries version, fingerprint, confirmation and the limits of that version, so a new version, a revocation
 * or another limit discards the result.
 */
internal data class TestCalculationDependencies(
    val profile: Input<ProfileSnapshot>,
    /** Null when the form does not take carbohydrates from the meal selection. */
    val meal: Input<CarbsValue>?,
    /** Null when the form does not take glucose from Dexcom. */
    val dexcom: Input<GlucoseValue>?,
)

/** Where the test calculation takes glucose from. Nothing is chosen automatically. */
internal enum class GlucoseChoice { MANUAL, DEXCOM }

/** Where the test calculation takes carbohydrates from. */
internal enum class CarbsChoice { MANUAL, MEAL_SELECTION }

/** Raw text typed in the form. Blank means absent, never zero. */
internal data class TestCalculationForm(
    val carbsChoice: CarbsChoice = CarbsChoice.MANUAL,
    val carbs: String = "",
    val glucoseChoice: GlucoseChoice = GlucoseChoice.MANUAL,
    val glucoseMgDl: String = "",
    /** When the manual glucose text was last edited; part of the input, never replaced by the calculation time. */
    val glucoseEnteredAtEpochMs: Long? = null,
    val iobUnits: String = "",
)

/**
 * Builds the explicit engine request of a test calculation (ADR 0024) from the local profile, the meal selection, the
 * latest Dexcom presentation and the form. Rounding step and both maximums come from the profile version used
 * (ADR 0025); they are never typed per calculation. It only translates: every absent or invalid input becomes
 * [Input.Unavailable] with a stable cause, and the engine blocks what depends on it. Nothing here calculates.
 */
internal object TestCalculation {
    fun request(
        atEpochMs: Long,
        profile: ProfileGateState?,
        meal: MealSelection?,
        dexcom: LatestReadingPresentation?,
        form: TestCalculationForm,
    ): CandidateRequest = CandidateRequest(
        calculationAtEpochMs = atEpochMs,
        localMinuteOfDay = localMinute(profile, atEpochMs),
        profile = profile(profile),
        carbs = when (form.carbsChoice) {
            CarbsChoice.MANUAL -> quantity(form.carbs, "meal.carbs").map { CarbsValue(it, CarbsOrigin.MANUAL_ENTRY) }
            CarbsChoice.MEAL_SELECTION -> mealCarbs(meal)
        },
        glucose = when (form.glucoseChoice) {
            GlucoseChoice.MANUAL -> manualGlucose(form)
            GlucoseChoice.DEXCOM -> dexcomGlucose(dexcom)
        },
        iob = quantity(form.iobUnits, "iob.manual", absent = "iob.unknown").map { IobValue(it) },
    )

    fun calculate(request: CandidateRequest): CandidateResult = CandidateEngine.calculate(request)

    /**
     * The inputs that come from outside the form. A result is kept only while they stay equal, so a new reading,
     * another meal revision or another profile state never sits next to a result computed from the previous one.
     */
    fun dependencies(
        profile: ProfileGateState?,
        meal: MealSelection?,
        dexcom: LatestReadingPresentation?,
        form: TestCalculationForm,
    ) = TestCalculationDependencies(
        profile(profile),
        if (form.carbsChoice == CarbsChoice.MEAL_SELECTION) mealCarbs(meal) else null,
        if (form.glucoseChoice == GlucoseChoice.DEXCOM) dexcomGlucose(dexcom) else null,
    )

    private fun manualGlucose(form: TestCalculationForm): Input<GlucoseValue> =
        when (val value = quantity(form.glucoseMgDl, "glucose.manual")) {
            is Input.Unavailable -> value
            is Input.Provided -> {
                val enteredAt = form.glucoseEnteredAtEpochMs ?: return Input.Unavailable("glucose.manual.entry_time_unknown")
                Input.Provided(GlucoseValue(value.value, GlucoseUnitCode.MG_DL, GlucoseOrigin.MANUAL_ENTRY, enteredAt,
                    measurementTimeVerified = false, sourceReference = "manual_entry"))
            }
        }

    fun profile(state: ProfileGateState?): Input<ProfileSnapshot> = when (state) {
        null, ProfileGateState.ReadPending -> Input.Unavailable("profile.read.pending")
        is ProfileGateState.Unreadable -> Input.Unavailable("profile.unreadable")
        is ProfileGateState.Missing -> Input.Unavailable("profile.missing")
        is ProfileGateState.Evaluated -> {
            val latest = state.latest
            val content = latest.content
            Input.Provided(ProfileSnapshot(
                version = latest.version,
                fingerprintSha256 = latest.contentSha256,
                dataConfirmed = state.confirmation is VersionConfirmation.Active,
                glucoseUnit = when (val unit = content.glucoseUnit) {
                    Setting.NotConfigured -> Input.Unavailable("profile.glucose_unit.not_configured")
                    is Setting.Declared -> Input.Provided(when (unit.value) {
                        GlucoseUnit.MG_DL -> GlucoseUnitCode.MG_DL
                        GlucoseUnit.MMOL_L -> GlucoseUnitCode.MMOL_L
                    })
                },
                timeZoneId = when (val zone = content.timeZone) {
                    Setting.NotConfigured -> Input.Unavailable("profile.time_zone.not_configured")
                    is Setting.Declared -> Input.Provided(zone.value.id)
                },
                carbRatio = segments(content.schedule(ProfileParameter.CARB_RATIO)),
                sensitivity = segments(content.schedule(ProfileParameter.INSULIN_SENSITIVITY)),
                target = segments(content.schedule(ProfileParameter.GLUCOSE_TARGET)),
                roundStep = limit(content, ProfileLimit.ROUND_STEP),
                maxBolus = limit(content, ProfileLimit.MAX_BOLUS),
                maxCorrection = limit(content, ProfileLimit.MAX_CORRECTION),
            ))
        }
    }

    /** The local wall-clock minute in the profile's own zone. A missing or unknown zone blocks segment selection. */
    fun localMinute(state: ProfileGateState?, atEpochMs: Long): Input<Int> {
        val evaluated = state as? ProfileGateState.Evaluated ?: return Input.Unavailable("profile.time_zone.unavailable")
        val zone = when (val declared = evaluated.latest.content.timeZone) {
            Setting.NotConfigured -> return Input.Unavailable("profile.time_zone.not_configured")
            is Setting.Declared -> try {
                ZoneId.of(declared.value.id)
            } catch (_: DateTimeException) {
                return Input.Unavailable("profile.time_zone.unrecognized")
            }
        }
        val local = Instant.ofEpochMilli(atEpochMs).atZone(zone)
        return Input.Provided(local.hour * 60 + local.minute)
    }

    /** Only a reading the presentation shows with its number can be used; its limitations travel with it. */
    fun dexcomGlucose(presentation: LatestReadingPresentation?): Input<GlucoseValue> = when (presentation) {
        null -> Input.Unavailable("glucose.dexcom.read_pending")
        is LatestReadingPresentation.Reading -> Input.Provided(GlucoseValue(Quantity(presentation.valueMgDl.toString()),
            GlucoseUnitCode.MG_DL, GlucoseOrigin.DEXCOM_PERMISSION_SET, presentation.receivedAtEpochMs,
            measurementTimeVerified = presentation.measurementTimeVerified,
            sourceReference = when (val provenance = presentation.provenance) {
                is ApprovedPermissionSet -> "${provenance.code}:${provenance.policyVersion}:${provenance.holderSetSha256}"
                is AuthenticatedSender -> "${provenance.code}:${provenance.packageName}@${provenance.versionCode}"
            } + ":tx=${presentation.transmitterShort}"))
        is LatestReadingPresentation.Withheld -> Input.Unavailable("glucose.${presentation.cause.code}")
        is LatestReadingPresentation.NoReading -> Input.Unavailable("glucose.${presentation.cause.code}")
    }

    /** Carbohydrates of the pinned meal revision, only when it is current and its basis is total grams. */
    fun mealCarbs(selection: MealSelection?): Input<CarbsValue> = when (selection) {
        is MealSelection.Reviewed -> when {
            selection.obsolete -> Input.Unavailable("meal.selection.obsolete")
            selection.snapshot.content.basis != NutritionBasis.TOTAL_GRAMS -> Input.Unavailable("meal.basis.not_total_grams")
            else -> when (val carbs = selection.snapshot.content.carbs) {
                DraftField.Missing -> Input.Unavailable("meal.carbs.missing")
                is DraftField.Entered -> quantity(carbs.text, "meal.carbs").map { CarbsValue(it, CarbsOrigin.MEAL_REVISION) }
            }
        }
        null -> Input.Unavailable("meal.selection.read_pending")
        MealSelection.Missing -> Input.Unavailable("meal.selection.missing")
        is MealSelection.Failed -> Input.Unavailable("meal.selection.unreadable")
    }

    /** A schema 1 version has no limits: unknown, never filled in from another version or a default. */
    private fun limit(content: ProfileContent, limit: ProfileLimit): Input<Quantity> = when (val value = content.limit(limit)) {
        null -> Input.Unavailable("profile.${limit.code}.not_in_schema")
        ProfileValue.NotConfigured -> Input.Unavailable("profile.${limit.code}.not_configured")
        is ProfileValue.Entered -> Input.Provided(Quantity(value.decimal.text))
    }

    private fun segments(schedule: ParameterSchedule): List<SegmentValue> = schedule.segments.map {
        SegmentValue(it.startMinute, it.endMinute, when (val value = it.value) {
            ProfileValue.NotConfigured -> Input.Unavailable("profile.value.not_configured")
            is ProfileValue.Entered -> Input.Provided(Quantity(value.decimal.text))
        })
    }

    private fun quantity(text: String, field: String, absent: String = "$field.missing"): Input<Quantity> =
        when (val parsed = DecimalInputs.parse(text)) {
            DecimalInput.Blank -> Input.Unavailable(absent)
            is DecimalInput.Invalid -> Input.Unavailable("$field.invalid")
            is DecimalInput.Valid -> Input.Provided(Quantity(parsed.value.text))
        }

    private inline fun <T, R> Input<T>.map(transform: (T) -> R): Input<R> = when (this) {
        is Input.Unavailable -> this
        is Input.Provided -> Input.Provided(transform(value))
    }
}
