"""Capture fibre and Warsaw vectors from Legacy `_calculate_core` (ADR 0029).

Read-only audit tool, same loader and rules as `capture_candidate_vectors.py`: it evaluates the pure function of a
local, detached checkout of `DanielGTdiabetes/bolus_ai` pinned to an exact SHA on synthetic inputs and never writes
to Legacy.

Next has no switches for these rules: a factor of 0 switches a rule off. Every case runs with both Legacy switches
on, and the script itself proves the equivalence it relies on:
  - with a factor of 0, Legacy gives the same result as with the switch off;
  - the total does not depend on dual delivery (`dual_bolus_enabled`), which Next does not apply.

Usage (run with `python3 -I` from outside the checkout):
    python3 -I capture_nutrition_vectors.py <legacy-checkout> <expected-sha> > CandidateNutritionLegacyVectors.kt
"""
import pathlib
import subprocess
import sys

sys.dont_write_bytecode = True  # never leave a generated file next to the scripts
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from capture_candidate_vectors import kotlin_double, load_core  # noqa: E402

FIELDS = ("name", "carbs", "fiber", "fat", "protein", "cr", "isf", "target", "bg", "iob", "max_corr", "step",
          "max_bolus", "fiber_factor", "fiber_threshold", "warsaw_simple", "warsaw_dual", "trigger", "strategy")

CASES = [
    # name, carbs, fiber, fat, protein, cr, isf, target, bg, iob, max_corr, step, max_bolus,
    #   fiber_factor, fiber_threshold, warsaw_simple, warsaw_dual, trigger_kcal, strategy
    ("fiber_deducted", 60.0, 12.0, 0.0, 0.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("fiber_at_threshold_not_deducted", 60.0, 5.0, 0.0, 0.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("fiber_just_above_threshold", 47.0, 5.5, 0.0, 0.0, 9.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.05, 15.0, 0.3, 5.0, 0.1, 0.2, 300, "auto"),
    ("fiber_at_or_above_carbs_not_deducted", 10.0, 12.0, 0.0, 0.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("fiber_equal_to_carbs_not_deducted", 12.0, 12.0, 0.0, 0.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("fiber_factor_zero", 60.0, 12.0, 0.0, 0.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.0, 5.0, 0.1, 0.2, 300, "auto"),
    ("fiber_factor_one", 60.0, 20.0, 0.0, 0.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 1.0, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_simple_below_trigger", 40.0, 0.0, 10.0, 20.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_dual_at_trigger", 40.0, 0.0, 20.0, 30.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_just_below_trigger_is_simple", 40.0, 0.0, 20.0, 29.75, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_above_trigger_strategy_normal", 40.0, 0.0, 20.0, 30.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "normal"),
    ("warsaw_below_50_kcal_not_applied", 40.0, 0.0, 2.0, 5.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_exactly_50_kcal_not_applied", 40.0, 0.0, 0.0, 12.5, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_dual_without_carbs", 0.0, 0.0, 30.0, 20.0, 8.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_simple_without_carbs", 0.0, 0.0, 10.0, 10.0, 8.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("warsaw_dual_rounded_apart", 33.0, 0.0, 25.0, 40.0, 11.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.5, 15.0, 0.5, 5.0, 0.15, 0.35, 250, "auto"),
    ("warsaw_dual_max_bolus_caps_total", 120.0, 0.0, 60.0, 60.0, 8.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.5, 10.0, 0.5, 5.0, 0.1, 1.0, 300, "auto"),
    ("warsaw_dual_hypo_stop", 40.0, 0.0, 20.0, 30.0, 10.0, 40.0, 110.0, 65.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 300, "auto"),
    ("fiber_and_warsaw_with_iob_and_correction", 75.0, 14.0, 22.0, 31.0, 9.0, 45.0, 105.0, 187.0, 0.8, 5.0, 0.05, 15.0, 0.4, 6.0, 0.12, 0.25, 280, "auto"),
    ("warsaw_simple_with_negative_correction", 30.0, 0.0, 12.0, 14.0, 10.0, 40.0, 120.0, 90.0, 1.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.2, 0.3, 400, "auto"),
    ("warsaw_factors_zero", 40.0, 0.0, 40.0, 40.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.0, 0.0, 300, "auto"),
    ("warsaw_dual_factor_zero_above_trigger", 40.0, 0.0, 40.0, 40.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.0, 300, "auto"),
    ("warsaw_trigger_zero_is_always_dual", 40.0, 0.0, 6.0, 4.0, 10.0, 40.0, 110.0, 110.0, 0.0, 5.0, 0.1, 15.0, 0.5, 5.0, 0.1, 0.2, 0, "auto"),
]


def text(value):
    return repr(value)[:-2] if repr(value).endswith(".0") else repr(value)


def main():
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    checkout, expected_sha = sys.argv[1], sys.argv[2]
    sha = subprocess.check_output(["git", "-C", checkout, "rev-parse", "HEAD"], text=True).strip()
    if sha != expected_sha:
        raise SystemExit(f"Legacy checkout is at {sha}, expected {expected_sha}")
    core, calculation_input = load_core(checkout)

    def run(case, dual=False, fiber_switch=True, warsaw_switch=True):
        c = dict(zip(FIELDS, case))
        return core(calculation_input(
            carbs_g=c["carbs"], target_mgdl=c["target"], cr=c["cr"], isf=c["isf"], fiber_g=c["fiber"], fat_g=c["fat"],
            protein_g=c["protein"], bg_mgdl=c["bg"], bg_trend=None, bg_is_stale=False, bg_age_minutes=0.0,
            iob_u=c["iob"], autosens_ratio=1.0, alcohol_mode=False, exercise_minutes=0,
            use_fiber_deduction=fiber_switch, fiber_factor=c["fiber_factor"], fiber_threshold=c["fiber_threshold"],
            max_bolus_u=c["max_bolus"], max_correction_u=c["max_corr"], round_step=c["step"],
            warsaw_enabled=warsaw_switch, warsaw_factor_simple=c["warsaw_simple"], warsaw_factor_dual=c["warsaw_dual"],
            warsaw_trigger=c["trigger"], dual_bolus_enabled=dual, techne_enabled=False, strategy=c["strategy"],
            max_iob_u=None, min_bolus_interval_min=0, last_bolus_minutes=None))

    print("package org.bolusai.engine.candidate")
    print()
    print("// GENERATED by scripts/legacy-vectors/capture_nutrition_vectors.py. Do not edit by hand.")
    print(f"// Source: DanielGTdiabetes/bolus_ai@{sha} backend/app/services/bolus_engine.py::_calculate_core")
    print("// Synthetic inputs, fibre and Warsaw switches on, dual delivery off, every other Legacy feature disabled.")
    print("// extendedU is the rounded later part Legacy reports with dual delivery on. Observed behaviour, not approval.")
    print()
    print("internal class NutritionVector(")
    print("    val name: String, val carbs: String, val fiber: String, val fat: String, val protein: String,")
    print("    val carbRatio: String, val sensitivity: String, val target: String, val glucose: String, val iob: String,")
    print("    val maxCorrection: String, val roundStep: String, val maxBolus: String, val fiberFactor: String,")
    print("    val fiberThreshold: String, val warsawSimple: String, val warsawDual: String, val warsawTrigger: String,")
    print("    val strategy: String, val mealU: Double, val correctionU: Double, val iobAppliedU: Double,")
    print("    val totalU: Double, val extendedU: Double,")
    print(")")
    print()
    print(f"internal const val NUTRITION_VECTORS_SHA: String = \"{sha}\"")
    print()
    print("internal val NUTRITION_VECTORS: List<NutritionVector> = listOf(")
    for case in CASES:
        c = dict(zip(FIELDS, case))
        result = run(case)
        dual = run(case, dual=True)
        # Delivery is metadata only: the total and the components do not depend on it.
        assert result.later_u == 0.0, c["name"]
        assert (dual.total_u, dual.meal_u, dual.corr_u) == (result.total_u, result.meal_u, result.corr_u), c["name"]
        # A factor of 0 is the Legacy switch off.
        if c["fiber_factor"] == 0.0:
            off = run(case, fiber_switch=False)
            assert (off.total_u, off.meal_u) == (result.total_u, result.meal_u), c["name"]
        if c["warsaw_simple"] == 0.0 and c["warsaw_dual"] == 0.0:
            off = run(case, warsaw_switch=False)
            assert (off.total_u, off.meal_u) == (result.total_u, result.meal_u), c["name"]
            assert run(case, dual=True, warsaw_switch=False).later_u == dual.later_u == 0.0, c["name"]
        values = ", ".join(f"\"{text(c[key])}\"" for key in FIELDS[1:18])
        print(f"    NutritionVector(\"{c['name']}\", {values}, \"{c['strategy']}\",")
        print(f"        {kotlin_double(result.meal_u)},")
        print(f"        {kotlin_double(result.corr_u)},")
        print(f"        {kotlin_double(result.iob_devoured)},")
        print(f"        {kotlin_double(result.total_u)},")
        print(f"        {kotlin_double(dual.later_u)}),")
    print(")")


if __name__ == "__main__":
    main()
