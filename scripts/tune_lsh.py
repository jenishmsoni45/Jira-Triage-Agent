#!/usr/bin/env python3
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import statistics
import subprocess
import sys

from benchmark import ROOT, STRING_FIELDS

CONFIGS = [(128, 64), (128, 32), (128, 16), (192, 64), (256, 64), (384, 128), (64, 32), (96, 48)]
BASELINE = (128, 64)
POLICY = {
    "developmentQualitySeeds": [43001, 43002, 43003],
    "developmentQualityIssues": 500,
    "developmentScaleSeed": 43004,
    "developmentScaleIssues": 10000,
    "heldOutQualitySeeds": [45001, 45002],
    "heldOutQualityIssues": 1000,
    "heldOutScaleSeed": 45003,
    "heldOutScaleSizes": [1000, 10000, 50000],
    "minimumRecallEachCorpus": 0.95,
    "minimumBoundaryRecallEachCorpus": 0.90,
    "boundaryInterval": "0.30 <= exact Jaccard < 0.40",
    "maximumDevelopmentHeapRatio": 1.10,
    "repetitions": 3,
    "measuredQueriesPerRun": 200,
    "selection": "Among development-eligible configs, minimize total raw candidate pairs; tie break by signature size then bands",
    "promotion": "Frozen choice must pass both recall floors on each held-out corpus; otherwise retain baseline without retuning",
}


def config_id(config):
    return f"k{config[0]}-b{config[1]}"


def median(rows, key):
    return statistics.median(row[key] for row in rows)


def eligible(rows):
    return all(isinstance(row["lexicalOracleRecall"], (float, int))
               and row["lexicalOracleRecall"] >= POLICY["minimumRecallEachCorpus"]
               and isinstance(row["boundaryRecall"], (float, int))
               and row["boundaryRecall"] >= POLICY["minimumBoundaryRecallEachCorpus"] for row in rows)


def main():
    output = ROOT / "target/tuning/results.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    home = os.environ.get("JAVA_HOME")
    java = str(Path(home) / "bin/java") if home else "java"
    javac = str(Path(home) / "bin/javac") if home else "javac"
    classes = ROOT / "target/classes"
    destination = ROOT / "target/benchmark-classes"
    destination.mkdir(parents=True, exist_ok=True)
    report = {
        "startedAtUtc": datetime.now(timezone.utc).isoformat(), "complete": False,
        "policy": POLICY, "configurations": [config_id(c) for c in CONFIGS],
        "fixedSettings": {"shingleSize": 2, "hashSeed": 20261007, "minimumSimilarity": 0.30},
        "environment": {"platform": platform.platform(), "logicalCpus": os.cpu_count(),
                        "javaVersion": subprocess.run([java, "-version"], check=True, capture_output=True, text=True).stderr.strip(),
                        "jvmArguments": ["-Xms128m", "-Xmx2g"], "pythonVersion": platform.python_version()},
        "sourceSha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in [ROOT / "benchmarks/DuplicateBenchmark.java", Path(__file__),
                                   *sorted((ROOT / "src/main/java/com/jenishsoni/jiratriage/duplicate").glob("*.java"))]},
        "runs": [],
    }

    def save():
        temporary = output.with_suffix(".tmp")
        temporary.write_text(json.dumps(report, indent=2) + "\n")
        temporary.replace(output)

    def run(config, phase, mode, size, seed, repetition=1):
        print(f"{phase}: {config_id(config)}, {mode}, n={size}, seed={seed}, repetition={repetition}", flush=True)
        command = [java, "-Xms128m", "-Xmx2g", "-cp", os.pathsep.join([str(classes), str(destination)]),
                   "DuplicateBenchmark", str(size), mode, str(seed), "200", str(config[0]), str(config[1])]
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=600, cwd=ROOT)
        row = {"phase": phase, "configuration": config_id(config), "repetition": repetition, "command": command}
        for line in result.stdout.splitlines():
            key, value = line.split("\t", 1)
            row[key] = value if key in STRING_FIELDS or value == "undefined" else (float(value) if "." in value or "e" in value.lower() else int(value))
        if mode == "quality-v2" and (row["oraclePairs"] == 0 or row["boundaryOraclePairs"] == 0):
            raise AssertionError("Quality corpus must contain both oracle positives and near-threshold positives")
        if mode == "boilerplate" and (row["meanRetrievedCandidates"] != size - 1 or row["meanReturnedMatches"] != size - 1):
            raise AssertionError("Identical-text stress case must retrieve and return all other issues")
        peers = [r for r in report["runs"] if r["mode"] == mode and r["issues"] == size and r["seed"] == seed]
        if any(r["corpusSha256"] != row["corpusSha256"] for r in peers):
            raise AssertionError("Compared configurations did not use the same corpus")
        for peer in peers:
            if peer["configuration"] == row["configuration"] and mode != "quality-v2":
                for key in ["meanRetrievedCandidates", "meanReturnedMatches", "largestVisitedBucket"]:
                    if peer[key] != row[key]:
                        raise AssertionError(f"Non-reproducible retrieval: {key}")
        report["runs"].append(row)
        save()
        return row

    def rows(phase, config):
        return [r for r in report["runs"] if r["phase"] == phase and r["configuration"] == config_id(config)]

    save()
    try:
        subprocess.run([javac, "--release", "21", "-cp", str(classes), "-d", str(destination),
                        str(ROOT / "benchmarks/DuplicateBenchmark.java")], check=True, cwd=ROOT)
        for seed in POLICY["developmentQualitySeeds"]:
            for config in CONFIGS:
                run(config, "development-quality", "quality-v2", 500, seed)
        schedule = [(config, repetition) for repetition in range(1, 4) for config in CONFIGS]
        random.Random(43005).shuffle(schedule)
        for config, repetition in schedule:
            run(config, "development-cost", "mixed", 10000, 43004, repetition)
        base_heap = median(rows("development-cost", BASELINE), "approximateIndexHeapDeltaBytes")
        choices = []
        report["developmentSummary"] = []
        for config in CONFIGS:
            quality = rows("development-quality", config)
            cost = rows("development-cost", config)
            ratio = median(cost, "approximateIndexHeapDeltaBytes") / base_heap
            qualifies = eligible(quality) and ratio <= POLICY["maximumDevelopmentHeapRatio"]
            pairs = sum(r["rawCandidatePairs"] for r in quality)
            report["developmentSummary"].append({"configuration": config_id(config), "eligible": qualifies,
                "minimumRecall": min(r["lexicalOracleRecall"] for r in quality),
                "minimumBoundaryRecall": min(r["boundaryRecall"] for r in quality),
                "totalRawCandidatePairs": pairs, "heapRatioToBaseline": ratio,
                "medianQueryP95Millis": median(cost, "queryP95Millis"),
                "medianMeanCandidates": median(cost, "meanRetrievedCandidates")})
            if qualifies:
                choices.append((pairs, config))
        selected = min(choices)[1] if choices else BASELINE
        report["selection"] = {"configuration": config_id(selected), "signatureSize": selected[0], "bands": selected[1],
                               "frozenAtUtc": datetime.now(timezone.utc).isoformat(),
                               "reason": "Development eligibility and candidate objective" if choices else "No eligible choice; baseline fallback"}
        save()
        compared = list(dict.fromkeys([BASELINE, selected]))
        for seed in POLICY["heldOutQualitySeeds"]:
            for config in compared:
                run(config, "held-out-quality", "quality-v2", 1000, seed)
        report["promotionPassed"] = eligible(rows("held-out-quality", selected)) and bool(choices)
        report["recommendedDefault"] = config_id(selected if report["promotionPassed"] else BASELINE)
        save()
        schedule = [(config, size, repetition) for size in POLICY["heldOutScaleSizes"]
                    for repetition in range(1, 4) for config in compared]
        random.Random(45004).shuffle(schedule)
        for config, size, repetition in schedule:
            run(config, "held-out-cost", "mixed", size, 45003, repetition)
        for repetition in range(1, 4):
            for config in compared:
                run(config, "stress", "boilerplate", 1000, 45003, repetition)
        report["costSummary"] = []
        for phase in ["held-out-cost", "stress"]:
            for config in compared:
                for size in sorted({r["issues"] for r in rows(phase, config)}):
                    values = [r for r in rows(phase, config) if r["issues"] == size]
                    report["costSummary"].append({"phase": phase, "configuration": config_id(config), "issues": size,
                        **{"median_" + key: median(values, key) for key in ["buildMillis", "queryP50Millis", "queryP95Millis",
                            "meanRetrievedCandidates", "approximateIndexHeapDeltaBytes", "largestVisitedBucket"]}})
        report["complete"] = True
        report["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        save()
        print(f"Completed. Recommended default: {report['recommendedDefault']}. Report: {output}")
    except (subprocess.SubprocessError, AssertionError, OSError, ValueError) as error:
        report["error"] = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            report["stderr"] = error.stderr
        save()
        print(f"Incomplete tuning run; see {output}: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
