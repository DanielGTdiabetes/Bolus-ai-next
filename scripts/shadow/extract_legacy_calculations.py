"""Minimise stored Legacy calculations for the shadow replay (ADR 0030).

Offline and read-only: it reads two local files exported from Legacy by the owner and writes the minimised text
the replay tool reads. It never connects to Legacy, a database or the network.

Inputs
  --rows      JSON Lines, one `treatments` row per line, as produced by a read-only query such as:
                SELECT row_to_json(t) FROM (
                  SELECT id, created_at, event_type, insulin, carbs, fat, protein, fiber, glucose, calculation_trace
                  FROM treatments WHERE calculation_trace IS NOT NULL ORDER BY created_at
                ) t;
  --settings  JSON of the current Legacy settings (`user_settings.settings`), used only for the fibre and Warsaw
              rules, which the stored trace does not carry.

Minimisation: the output keeps only what the calculation used and returned. Row ids become a short SHA-256 prefix;
timestamps, notes, names, user ids, Nightscout ids and free text are dropped. The output still holds clinical
values (grams, mg/dL, units): keep it and the two inputs outside the repository and delete them after use.

Legacy keeps a hash of the settings in each trace but does not store it with the settings. Unless --config-hash is
given, the hash of the newest row is taken as the one of the exported settings and the output says so; rows with
another hash are replayed with unverified settings and reported apart.

Usage (run with `python3 -I`):
    python3 -I extract_legacy_calculations.py --rows rows.jsonl --settings settings.json --legacy-sha <sha> > shadow.txt
"""
import argparse
import hashlib
import json
import sys
from decimal import Decimal

FORMAT = "bolus-ai-next/shadow-legacy-calculations/v1"
NULL = "~"


def number(value):
    """Shortest exact decimal text of a JSON number, without exponent or trailing zeros; null stays null."""
    if value is None or isinstance(value, bool):
        return NULL
    if not isinstance(value, (int, float)) or value != value or value in (float("inf"), float("-inf")):
        return NULL
    text = format(Decimal(repr(value)), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def boolean(value):
    return NULL if not isinstance(value, bool) else ("true" if value else "false")


def first(*values):
    return next((v for v in values if v is not None), None)


def record(row):
    trace = row.get("calculation_trace")
    if isinstance(trace, str):
        trace = json.loads(trace)
    if not isinstance(trace, dict) or not isinstance(trace.get("snapshot"), dict):
        return None
    snapshot = trace["snapshot"]
    ratios = trace.get("applied_ratios") if isinstance(trace.get("applied_ratios"), dict) else {}
    glucose = snapshot.get("glucose") if isinstance(snapshot.get("glucose"), dict) else {}
    explain = snapshot.get("explain") if isinstance(snapshot.get("explain"), list) else None
    if snapshot.get("total_u_final") is None:
        return None
    trend = glucose.get("trend")
    return [
        ("record", hashlib.sha256(str(row.get("id")).encode("utf-8")).hexdigest()[:16]),
        ("source", str(snapshot.get("source") or NULL)),
        ("kind", str(snapshot.get("kind") or NULL)),
        ("carbs", number(row.get("carbs"))),
        ("fiber", number(row.get("fiber"))),
        ("fat", number(row.get("fat"))),
        ("protein", number(row.get("protein"))),
        ("cr", number(first(ratios.get("effective_cr_g_per_u"), ratios.get("cr_g_per_u")))),
        ("isf", number(first(ratios.get("effective_isf_mgdl_per_u"), ratios.get("isf_mgdl_per_u")))),
        ("target", number(ratios.get("target_mgdl"))),
        ("glucose", number(glucose.get("mgdl"))),
        ("glucose_stale", boolean(glucose.get("is_stale"))),
        ("trend", str(trend) if isinstance(trend, str) and trend and "\n" not in trend else NULL),
        ("iob", number(snapshot.get("iob_u"))),
        ("round_step", number(ratios.get("round_step_u"))),
        ("max_bolus", number(ratios.get("max_bolus_final"))),
        ("max_correction", number(ratios.get("max_correction_u"))),
        ("max_iob", number(ratios.get("max_iob_u"))),
        ("autosens_ratio", number(ratios.get("autosens_ratio"))),
        ("techne_enabled", boolean(ratios.get("techne_enabled"))),
        ("dual_bolus_enabled", boolean(ratios.get("dual_bolus_enabled"))),
        ("config_hash", str(ratios.get("config_hash") or NULL)),
        # Legacy writes one breakdown line per applied exercise reduction; the trace has no other record of it.
        ("exercise", NULL if explain is None else boolean(any(str(line).startswith("D) Ejercicio") for line in explain))),
        ("expected.meal_u", number(snapshot.get("meal_component_u"))),
        ("expected.correction_u", number(snapshot.get("correction_component_u"))),
        ("expected.iob_applied_u", number(snapshot.get("iob_applied_to_correction_u"))),
        ("expected.total_u", number(snapshot.get("total_u_final"))),
        ("expected.upfront_u", number(snapshot.get("upfront_u"))),
        ("expected.later_u", number(snapshot.get("later_u"))),
        ("accepted_u", number(snapshot.get("accepted_u"))),
    ]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", required=True)
    parser.add_argument("--settings", required=True)
    parser.add_argument("--legacy-sha", required=True)
    parser.add_argument("--config-hash")
    parser.add_argument("--quiet", action="store_true", help="do not print the row counts to stderr")
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")

    with open(args.settings, encoding="utf-8") as handle:
        settings = json.load(handle)
    calculator = settings.get("calculator") or {}
    warsaw = settings.get("warsaw") or {}

    records, skipped = [], 0
    with open(args.rows, encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            item = record(json.loads(line))
            if item is None:
                skipped += 1
            else:
                records.append(item)

    config_hash, source = args.config_hash, "given"
    if not config_hash:
        newest = next((dict(r).get("config_hash") for r in reversed(records) if dict(r).get("config_hash") != NULL), None)
        config_hash, source = (newest, "newest_row_assumed") if newest else (NULL, "none")

    header = [
        ("format", FORMAT),
        ("legacy_sha", args.legacy_sha),
        ("settings.config_hash", config_hash),
        ("settings.config_hash_source", source),
        ("settings.fiber_deduction", boolean(calculator.get("subtract_fiber"))),
        ("settings.fiber_factor", number(calculator.get("fiber_factor"))),
        ("settings.fiber_threshold", number(calculator.get("fiber_threshold_g"))),
        ("settings.warsaw_enabled", boolean(warsaw.get("enabled"))),
        ("settings.warsaw_factor_simple", number(warsaw.get("safety_factor"))),
        ("settings.warsaw_factor_dual", number(warsaw.get("safety_factor_dual"))),
        ("settings.warsaw_trigger", number(warsaw.get("trigger_threshold_kcal"))),
    ]
    missing = [key for key, value in header if value == NULL and key != "settings.config_hash"]
    if missing:
        raise SystemExit("The settings export lacks: " + ", ".join(missing))
    blocks = [header] + records
    print("\n\n".join("\n".join(f"{key}={value}" for key, value in block) for block in blocks))
    if not args.quiet:
        print(f"{len(records)} calculations written, {skipped} rows without a usable trace skipped", file=sys.stderr)


if __name__ == "__main__":
    main()
