"""Reproduce every committed Legacy vector file from the pinned Legacy SHA (ADR 0024, ADR 0026).

Read-only. It fetches exactly one commit of the public `DanielGTdiabetes/bolus_ai` repository into a new temporary
directory, checks that the commit is the SHA pinned in the engine and in each generated file, runs each capture
script with `python -I` from outside the checkout and compares the output byte for byte with the committed file. It
never pushes, never runs Legacy services and deletes the temporary checkout afterwards.

Usage (from any directory):
    python3 -I scripts/legacy-vectors/verify_legacy_vectors.py
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

LEGACY_URL = "https://github.com/DanielGTdiabetes/bolus_ai.git"
REPOSITORY = pathlib.Path(__file__).resolve().parents[2]
ENGINE = REPOSITORY / "shared/bolus-engine/src"
CAPTURES = [
    (
        "capture_candidate_vectors.py",
        ENGINE / "commonTest/kotlin/org/bolusai/engine/candidate/CandidateLegacyVectors.kt",
        ENGINE / "commonMain/kotlin/org/bolusai/engine/candidate/CandidateEngine.kt",
    ),
    (
        "capture_iob_vectors.py",
        ENGINE / "commonTest/kotlin/org/bolusai/engine/iob/CandidateIobLegacyVectors.kt",
        ENGINE / "commonMain/kotlin/org/bolusai/engine/iob/CandidateIob.kt",
    ),
]
PINNED = re.compile(r'LEGACY_SHA: String = "([0-9a-f]{40})"')
SOURCE = re.compile(r"^// Source: DanielGTdiabetes/bolus_ai@([0-9a-f]{40}) ", re.MULTILINE)


def git(*args, cwd=None):
    subprocess.run(["git", *args], cwd=cwd, check=True, stdout=subprocess.DEVNULL)


def pinned_sha(rules, generated):
    rules_match = PINNED.search(rules.read_text(encoding="utf-8"))
    source_match = SOURCE.search(generated.read_text(encoding="utf-8"))
    if not rules_match or not source_match:
        raise SystemExit(f"Pinned Legacy SHA not found in {rules.name} or {generated.name}")
    if rules_match.group(1) != source_match.group(1):
        raise SystemExit(f"{generated.name} was captured at {source_match.group(1)}, engine pins {rules_match.group(1)}")
    return rules_match.group(1)


def main():
    failures = []
    shas = {pinned_sha(rules, generated) for _, generated, rules in CAPTURES}
    workspace = pathlib.Path(tempfile.mkdtemp(prefix="bolus-legacy-vectors-"))
    try:
        for sha in sorted(shas):
            checkout = workspace / sha
            checkout.mkdir()
            git("init", "-q", cwd=checkout)
            git("remote", "add", "origin", LEGACY_URL, cwd=checkout)
            git("remote", "set-url", "--push", "origin", "no-push", cwd=checkout)
            git("fetch", "-q", "--depth", "1", "origin", sha, cwd=checkout)
            git("checkout", "-q", "--detach", "FETCH_HEAD", cwd=checkout)
        for script, generated, rules in CAPTURES:
            sha = pinned_sha(rules, generated)
            result = subprocess.run(
                [sys.executable, "-I", str(REPOSITORY / "scripts/legacy-vectors" / script), str(workspace / sha), sha],
                cwd=workspace, capture_output=True, text=True, encoding="utf-8",
            )
            if result.returncode != 0:
                failures.append(f"{script} failed at {sha} (exit {result.returncode}):\n{result.stderr}")
                continue
            committed = generated.read_text(encoding="utf-8").replace("\r\n", "\n")
            if result.stdout.replace("\r\n", "\n") != committed:
                failures.append(f"{generated.relative_to(REPOSITORY)} differs from {script} at {sha}")
            else:
                print(f"Legacy vectors reproduced: {generated.name} from {script} at {sha}")
    finally:
        shutil.rmtree(workspace, ignore_errors=True)
    if failures:
        raise SystemExit("\n".join(failures))


if __name__ == "__main__":
    main()
