"""Capture candidate IOB vectors from Legacy `compute_iob_from_sources` (ADR 0026).

Read-only audit tool. It loads `backend/app/services/math/curves.py` and `backend/app/services/iob.py` from a local,
detached checkout of `DanielGTdiabetes/bolus_ai` pinned to an exact SHA. Only the imports that the IOB loop does not
use for arithmetic are replaced by inert stubs (SQLAlchemy, database, settings model, data store and IOB response
models). `_load_iob_sources` is replaced so that the synthetic doses arrive as the single successful local database
source; Nightscout is absent and the cache is neither read nor written (`persist_cache=False`, missing data dir).
Nothing touches Legacy settings, databases, networks or files, and nothing is written to Legacy.

Usage (run with `python3 -I` from outside the checkout):
    python3 -I capture_iob_vectors.py <legacy-checkout> <expected-sha> > CandidateIobLegacyVectors.kt
"""
import asyncio
import pathlib
import struct
import subprocess
import sys
import types
from datetime import datetime, timedelta, timezone

NOW = datetime(2026, 10, 12, 10, 0, 0, tzinfo=timezone.utc)

# name, curve, dia_hours, peak_minutes, doses: (id, seconds before NOW, units, extended duration minutes)
CASES = [
    ("walsh_single_mid", "walsh", 4.0, 75, [("d1", 90 * 60, 4.0, 0)]),
    ("walsh_peak_55_two_doses", "walsh", 5.0, 55, [("d1", 37 * 60 + 30, 2.5, 0), ("d2", 200 * 60, 6.0, 0)]),
    ("walsh_dose_at_now", "walsh", 4.0, 75, [("d1", 0, 3.0, 0)]),
    ("walsh_dose_beyond_dia", "walsh", 3.0, 60, [("d1", 181 * 60, 5.0, 0), ("d2", 15 * 60, 1.0, 0)]),
    ("bilinear_before_peak", "bilinear", 4.0, 75, [("d1", 40 * 60, 5.0, 0)]),
    ("bilinear_after_peak", "bilinear", 4.0, 75, [("d1", 150 * 60, 5.0, 0)]),
    ("bilinear_at_peak", "bilinear", 3.5, 90, [("d1", 90 * 60, 2.0, 0)]),
    ("fiasp_native_duration", "fiasp", 5.0, 75, [("d1", 50 * 60, 3.0, 0), ("d2", 125 * 60, 4.0, 0)]),
    ("fiasp_scaled_duration", "fiasp", 4.0, 75, [("d1", 50 * 60, 3.0, 0)]),
    ("fiasp_within_one_minute_not_scaled", "fiasp", 5.01, 75, [("d1", 50 * 60, 3.0, 0)]),
    ("novorapid_native_duration", "novorapid", 5.0, 75, [("d1", 20 * 60, 2.0, 0), ("d2", 140 * 60, 3.0, 0)]),
    ("novorapid_scaled_duration", "novorapid", 6.0, 75, [("d1", 200 * 60 + 15, 4.0, 0)]),
    ("linear_curve", "linear", 4.0, 75, [("d1", 60 * 60, 4.0, 0), ("d2", 239 * 60, 1.0, 0)]),
    ("extended_partly_delivered", "walsh", 4.0, 75, [("d1", 20 * 60, 6.0, 60)]),
    ("extended_fully_delivered", "bilinear", 4.0, 75, [("d1", 100 * 60, 3.0, 30)]),
    ("extended_not_chunk_aligned", "fiasp", 5.0, 75, [("d1", 12 * 60, 2.0, 23)]),
    ("duration_ten_is_immediate", "walsh", 4.0, 75, [("d1", 45 * 60, 2.0, 10)]),
    ("small_contribution_still_counted", "walsh", 4.0, 75, [("d1", 236 * 60, 3.0, 0), ("d2", 30 * 60, 0.5, 0)]),
    ("no_doses_in_window", "walsh", 4.0, 75, []),
]


def install_stubs():
    for name in ("app", "app.core", "app.models", "app.services", "app.services.math"):
        sys.modules.setdefault(name, types.ModuleType(name))
    sqlalchemy = types.ModuleType("sqlalchemy")
    sqlalchemy.text = lambda sql: sql
    sys.modules["sqlalchemy"] = sqlalchemy
    db = types.ModuleType("app.core.db")
    db.get_engine = lambda: None
    db.AsyncSession = type("AsyncSession", (), {})
    sys.modules["app.core.db"] = db
    settings = types.ModuleType("app.models.settings")
    settings.UserSettings = type("UserSettings", (), {})
    sys.modules["app.models.settings"] = settings
    store = types.ModuleType("app.services.store")
    store.DataStore = type("DataStore", (), {})
    sys.modules["app.services.store"] = store

    class Record:
        def __init__(self, **kwargs):
            self.__dict__.update(kwargs)

    iob_models = types.ModuleType("app.models.iob")
    iob_models.IOBInfo = Record
    iob_models.SourceStatus = Record
    iob_models.COBInfo = Record
    iob_models.IOBStatus = str
    iob_models.COBStatus = str
    sys.modules["app.models.iob"] = iob_models


def load(checkout, relative, module_name):
    module = types.ModuleType(module_name)
    module.__dict__["__name__"] = module_name
    sys.modules[module_name] = module
    path = f"{checkout}/{relative}"
    exec(compile(open(path, encoding="utf-8").read(), relative, "exec"), module.__dict__)
    return module


class InertStore:
    """No cache file exists and local events are empty, so the loop sees one successful source."""
    data_dir = pathlib.Path("/nonexistent-bolus-ai-next-capture")

    def load_events(self):
        return []

    def read_json(self, *_):
        raise AssertionError("cache must not be read")

    def write_json(self, *_):
        raise AssertionError("cache must not be written")


def capture(iob, curve, dia_hours, peak, doses):
    boluses = [{
        "ts": (NOW - timedelta(seconds=seconds)).isoformat(),
        "units": units,
        "duration": float(duration),
        "id": dose_id,
        "identity_aliases": [dose_id],
        "source": "local_db",
    } for dose_id, seconds, units, duration in doses]

    async def sources(**_):
        return boluses, [], [], None, None, None

    iob._load_iob_sources = sources
    settings = types.SimpleNamespace(iob=types.SimpleNamespace(dia_hours=dia_hours, curve=curve, peak_minutes=peak))
    total, breakdown, info, warning = asyncio.run(iob.compute_iob_from_sources(
        NOW, settings, None, InertStore(), user_id="synthetic", persist_cache=False))
    assert info.status == "ok" and warning is None, (info.status, warning)
    return total, {item["id"]: item["iob"] for item in breakdown}


def kotlin_double(value):
    bits = int.from_bytes(struct.pack(">d", value), "big")
    return f"Double.fromBits(0x{bits:016x}uL.toLong()) /* {value!r} */"


def text(value):
    return repr(value)[:-2] if repr(value).endswith(".0") else repr(value)


def main():
    # Legacy sources are UTF-8 and the generated file is UTF-8 with LF, whatever the platform locale (Windows CI).
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    checkout, expected_sha = sys.argv[1], sys.argv[2]
    sha = subprocess.check_output(["git", "-C", checkout, "rev-parse", "HEAD"], text=True).strip()
    if sha != expected_sha:
        raise SystemExit(f"Legacy checkout is at {sha}, expected {expected_sha}")
    install_stubs()
    load(checkout, "backend/app/services/math/curves.py", "app.services.math.curves")
    iob = load(checkout, "backend/app/services/iob.py", "app.services.iob")
    now_ms = int(NOW.timestamp() * 1000)
    print("package org.bolusai.engine.iob")
    print()
    print("// GENERATED by scripts/legacy-vectors/capture_iob_vectors.py. Do not edit by hand.")
    print(f"// Source: DanielGTdiabetes/bolus_ai@{sha} backend/app/services/iob.py::compute_iob_from_sources")
    print("// and backend/app/services/math/curves.py. Synthetic doses as the only successful source, no cache.")
    print("// Observed behaviour, not clinical approval.")
    print()
    print("internal class LegacyIobDose(")
    print("    val id: String, val secondsBefore: Long, val units: String, val extendedMinutes: Int,")
    print("    /** Legacy omits contributions <= 0.01 U from its breakdown; null means omitted there. */")
    print("    val legacyContributionU: Double?,")
    print(")")
    print()
    print("internal class LegacyIobVector(")
    print("    val name: String, val curve: String, val diaHours: String, val peakMinutes: Int,")
    print("    val doses: List<LegacyIobDose>, val totalU: Double,")
    print(")")
    print()
    print(f"internal const val LEGACY_IOB_VECTORS_SHA: String = \"{sha}\"")
    print(f"internal const val LEGACY_IOB_NOW_MS: Long = {now_ms}L")
    print()
    print("internal val LEGACY_IOB_VECTORS: List<LegacyIobVector> = listOf(")
    for name, curve, dia, peak, doses in CASES:
        total, contributions = capture(iob, curve, dia, peak, doses)
        print(f"    LegacyIobVector(\"{name}\", \"{curve}\", \"{text(dia)}\", {peak}, listOf(")
        for dose_id, seconds, units, duration in doses:
            contribution = contributions.get(dose_id)
            rendered = "null" if contribution is None else kotlin_double(contribution)
            print(f"        LegacyIobDose(\"{dose_id}\", {seconds}L, \"{text(units)}\", {duration}, {rendered}),")
        print(f"    ), {kotlin_double(total)}),")
    print(")")


if __name__ == "__main__":
    main()
