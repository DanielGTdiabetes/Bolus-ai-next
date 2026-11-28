"""Capture exercise-reduction vectors from Legacy `_calculate_core` (ADR 0031).

Read-only audit tool, same loader and rules as `capture_candidate_vectors.py`. `calculate_exercise_reduction` imports
`app.core.constants` and `app.models.enums` when called; both are plain constants and are loaded from the pinned
checkout. Nothing else of Legacy is imported and nothing is written to it.

Usage (run with `python3 -I` from outside the checkout):
    python3 -I capture_exercise_vectors.py <legacy-checkout> <expected-sha> > CandidateExerciseLegacyVectors.kt
"""
import pathlib
import subprocess
import sys
import types

sys.dont_write_bytecode = True  # never leave a generated file next to the scripts
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from capture_candidate_vectors import kotlin_double, load_core  # noqa: E402

FIELDS = ("name", "carbs", "fat", "protein", "cr", "isf", "target", "bg", "iob", "step", "max_bolus", "minutes",
          "intensity")

CASES = [
    # name, carbs, fat, protein, cr, isf, target, bg, iob, round_step, max_bolus, minutes, intensity
    ("low_30_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 30, "low"),
    ("low_60_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 60, "low"),
    ("low_90_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 90, "low"),
    ("low_120_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 120, "low"),
    ("low_180_min_is_constant", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 180, "low"),
    ("moderate_1_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.05, 15.0, 1, "moderate"),
    ("moderate_45_min", 47.0, 0.0, 0.0, 9.0, 45.0, 105.0, 163.0, 0.0, 0.05, 15.0, 45, "moderate"),
    ("moderate_60_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 60, "moderate"),
    ("moderate_61_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.05, 15.0, 61, "moderate"),
    ("moderate_120_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 120, "moderate"),
    ("moderate_121_min_is_constant", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.05, 15.0, 121, "moderate"),
    ("high_30_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 30, "high"),
    ("high_90_min", 75.0, 0.0, 0.0, 9.0, 45.0, 105.0, 187.0, 0.0, 0.05, 15.0, 90, "high"),
    ("high_120_min", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 120, "high"),
    ("high_240_min_is_constant", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 0.0, 0.1, 15.0, 240, "high"),
    ("reduces_after_iob", 60.0, 0.0, 0.0, 10.0, 40.0, 110.0, 190.0, 1.5, 0.1, 15.0, 60, "moderate"),
    ("reduces_with_negative_correction", 30.0, 0.0, 0.0, 10.0, 40.0, 120.0, 90.0, 2.0, 0.1, 15.0, 45, "low"),
    ("reduces_extended_warsaw_part_too", 40.0, 20.0, 30.0, 10.0, 40.0, 110.0, 110.0, 0.0, 0.1, 15.0, 60, "moderate"),
    ("reduces_simple_warsaw_in_the_meal", 40.0, 10.0, 20.0, 10.0, 40.0, 110.0, 110.0, 0.0, 0.1, 15.0, 90, "high"),
    ("rounded_to_half_units_after_reduction", 33.0, 25.0, 40.0, 11.0, 40.0, 110.0, 140.0, 0.3, 0.5, 15.0, 75, "moderate"),
    ("reduced_before_the_maximum_bolus", 200.0, 0.0, 0.0, 8.0, 30.0, 100.0, 220.0, 0.0, 0.5, 10.0, 30, "low"),
    ("hypo_stop_still_cancels", 40.0, 0.0, 0.0, 10.0, 40.0, 110.0, 65.0, 0.0, 0.1, 15.0, 60, "low"),
    ("without_carbs_reduces_the_correction", 0.0, 0.0, 0.0, 10.0, 40.0, 100.0, 220.0, 0.0, 0.1, 15.0, 60, "high"),
]

WARSAW_SIMPLE, WARSAW_DUAL, WARSAW_TRIGGER = 0.1, 0.2, 300


def text(value):
    return repr(value)[:-2] if repr(value).endswith(".0") else repr(value)


def load_constants(checkout):
    for name, path in (("app.core.constants", "backend/app/core/constants.py"), ("app.models.enums", "backend/app/models/enums.py")):
        module = types.ModuleType(name)
        exec(compile(open(f"{checkout}/{path}", encoding="utf-8").read(), path, "exec"), module.__dict__)
        sys.modules[name] = module
    sys.modules.setdefault("app.core", types.ModuleType("app.core"))


def main():
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    checkout, expected_sha = sys.argv[1], sys.argv[2]
    sha = subprocess.check_output(["git", "-C", checkout, "rev-parse", "HEAD"], text=True).strip()
    if sha != expected_sha:
        raise SystemExit(f"Legacy checkout is at {sha}, expected {expected_sha}")
    core, calculation_input = load_core(checkout)
    load_constants(checkout)

    def run(c, minutes, dual=False):
        return core(calculation_input(
            carbs_g=c["carbs"], target_mgdl=c["target"], cr=c["cr"], isf=c["isf"], fiber_g=0.0, fat_g=c["fat"],
            protein_g=c["protein"], bg_mgdl=c["bg"], bg_trend=None, bg_is_stale=False, bg_age_minutes=0.0,
            iob_u=c["iob"], autosens_ratio=1.0, alcohol_mode=False, exercise_minutes=minutes,
            exercise_intensity=c["intensity"], use_fiber_deduction=False, max_bolus_u=c["max_bolus"],
            max_correction_u=5.0, round_step=c["step"], warsaw_enabled=True, warsaw_factor_simple=WARSAW_SIMPLE,
            warsaw_factor_dual=WARSAW_DUAL, warsaw_trigger=WARSAW_TRIGGER, dual_bolus_enabled=dual,
            techne_enabled=False, strategy="auto", max_iob_u=None, min_bolus_interval_min=0, last_bolus_minutes=None))

    print("package org.bolusai.engine.candidate")
    print()
    print("// GENERATED by scripts/legacy-vectors/capture_exercise_vectors.py. Do not edit by hand.")
    print(f"// Source: DanielGTdiabetes/bolus_ai@{sha} backend/app/services/bolus_engine.py::_calculate_core")
    print(f"// Synthetic inputs. Warsaw on with factors {WARSAW_SIMPLE} and {WARSAW_DUAL} and trigger {WARSAW_TRIGGER} kcal,")
    print("// fibre off, dual delivery off, every other Legacy feature disabled. totalWithoutExerciseU is the same case")
    print("// with 0 minutes. Observed behaviour, not clinical approval.")
    print()
    print("internal class ExerciseVector(")
    print("    val name: String, val carbs: String, val fat: String, val protein: String, val carbRatio: String,")
    print("    val sensitivity: String, val target: String, val glucose: String, val iob: String, val roundStep: String,")
    print("    val maxBolus: String, val minutes: Int, val intensity: String,")
    print("    val mealU: Double, val correctionU: Double, val totalU: Double, val extendedU: Double,")
    print("    val totalWithoutExerciseU: Double,")
    print(")")
    print()
    print(f"internal const val EXERCISE_VECTORS_SHA: String = \"{sha}\"")
    print(f"internal const val EXERCISE_VECTORS_WARSAW_SIMPLE: String = \"{text(WARSAW_SIMPLE)}\"")
    print(f"internal const val EXERCISE_VECTORS_WARSAW_DUAL: String = \"{text(WARSAW_DUAL)}\"")
    print(f"internal const val EXERCISE_VECTORS_WARSAW_TRIGGER: String = \"{WARSAW_TRIGGER}\"")
    print()
    print("internal val EXERCISE_VECTORS: List<ExerciseVector> = listOf(")
    for case in CASES:
        c = dict(zip(FIELDS, case))
        result = run(c, c["minutes"])
        dual = run(c, c["minutes"], dual=True)
        without = run(c, 0)
        assert result.later_u == 0.0 and dual.total_u == result.total_u, c["name"]
        values = ", ".join(f"\"{text(c[key])}\"" for key in FIELDS[1:11])
        print(f"    ExerciseVector(\"{c['name']}\", {values}, {c['minutes']}, \"{c['intensity']}\",")
        print(f"        {kotlin_double(result.meal_u)},")
        print(f"        {kotlin_double(result.corr_u)},")
        print(f"        {kotlin_double(result.total_u)},")
        print(f"        {kotlin_double(dual.later_u)},")
        print(f"        {kotlin_double(without.total_u)}),")
    print(")")


if __name__ == "__main__":
    main()
