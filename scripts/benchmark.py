#!/usr/bin/env python3
"""Local benchmark orchestration using only Python/JDK standard libraries."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
STRING_FIELDS = {"corpusSha256", "mode", "missedPairIndices"}


def positive(value):
    result = int(value)
    if result < 1:
        raise argparse.ArgumentTypeError("must be positive")
    return result


def default_settings():
    values = dict(line.split("=", 1) for line in (ROOT / "src/main/resources/application.properties").read_text().splitlines()
                  if line.startswith("triage.duplicates."))
    return {"shingleSize": int(values["triage.duplicates.shingle-size"]),
            "signatureSize": int(values["triage.duplicates.signature-size"]),
            "bands": int(values["triage.duplicates.bands"]),
            "hashSeed": int(values["triage.duplicates.seed"]),
            "minimumSimilarity": float(values["triage.duplicates.minimum-similarity"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", nargs="+", type=positive, default=[1000, 10000, 50000])
    parser.add_argument("--repetitions", type=positive, default=3)
    parser.add_argument("--queries", type=positive, default=200)
    parser.add_argument("--seed", type=int, default=20261014)
    parser.add_argument("--heap", default="2g", help="maximum heap per sequential JVM (default 2g)")
    parser.add_argument("--output", type=Path, default=ROOT / "target/benchmarks/results.json")
    args = parser.parse_args()
    settings = default_settings()
    if (settings["shingleSize"], settings["hashSeed"], settings["minimumSimilarity"]) != (2, 20261007, 0.30):
        parser.error("Benchmark driver fixes shingle size, seed, and threshold; update it before benchmarking changed defaults.")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    javac = str(Path(java_home) / "bin/javac") if java_home else "javac"
    classes = ROOT / "target/classes"
    destination = ROOT / "target/benchmark-classes"
    if not (classes / "com/jenishsoni/jiratriage/duplicate/LshDuplicateIndex.class").is_file():
        parser.error("Compiled project classes are missing. Run mvn -o verify with JDK 21 first.")
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run([javac, "--release", "21", "-cp", str(classes), "-d", str(destination),
                    str(ROOT / "benchmarks/DuplicateBenchmark.java")], check=True, cwd=ROOT)
    report = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "scope": "Synthetic local index benchmark; not human duplicate accuracy, Jira, or MCP latency",
        "environment": {"platform": platform.platform(), "logicalCpus": os.cpu_count(),
                        "javaVersion": subprocess.run([java, "-version"], capture_output=True, text=True, check=True).stderr.strip(),
                        "jvmArguments": ["-Xms128m", "-Xmx" + args.heap], "pythonVersion": platform.python_version()},
        "settings": settings,
        "parameters": {"sizes": args.sizes, "repetitions": args.repetitions, "queries": args.queries, "corpusSeed": args.seed},
        "runs": [], "complete": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        args.output.write_text(json.dumps(report, indent=2) + "\n")

    def run(size, mode, seed, repetition):
        command = [java, "-Xms128m", "-Xmx" + args.heap, "-cp", os.pathsep.join([str(classes), str(destination)]),
                   "DuplicateBenchmark", str(size), mode, str(seed), str(args.queries), str(settings["signatureSize"]), str(settings["bands"])]
        print(f"Running {mode}: {size:,} issues, repetition {repetition}", flush=True)
        result = subprocess.run(command, capture_output=True, text=True, cwd=ROOT, timeout=600)
        if result.returncode:
            raise RuntimeError(f"Benchmark exited {result.returncode}: {result.stderr[-4000:]}")
        row = {"repetition": repetition, "command": command}
        for line in result.stdout.splitlines():
            key, value = line.split("\t", 1)
            if key in STRING_FIELDS or value == "undefined":
                row[key] = value
            else:
                row[key] = float(value) if "." in value or "e" in value.lower() else int(value)
        report["runs"].append(row)
        save()
        return row

    save()
    try:
        for size in args.sizes:
            rows = [run(size, "mixed", args.seed, repetition) for repetition in range(1, args.repetitions + 1)]
            if len({row["corpusSha256"] for row in rows}) != 1:
                raise AssertionError("Corpus changed across repetitions")
        for repetition in range(1, args.repetitions + 1):
            run(1000, "boilerplate", args.seed, repetition)

        quality = [run(500, "quality", args.seed + 1, repetition) for repetition in range(1, 3)]
        for field in ["corpusSha256", "oraclePairs", "retrievedOraclePairs", "rawCandidatePairs", "missedPairIndices"]:
            if quality[0][field] != quality[1][field]:
                raise AssertionError(f"Non-reproducible quality output: {field}")
        report["summary"] = []
        for mode, size in [("mixed", size) for size in args.sizes] + [("boilerplate", 1000)]:
            rows = [row for row in report["runs"] if row["mode"] == mode and row["issues"] == size]
            report["summary"].append({"mode": mode, "issues": size, "repetitions": len(rows), **{
                "median_" + key: statistics.median(row[key] for row in rows)
                for key in ["buildMillis", "queryP50Millis", "queryP95Millis", "meanRetrievedCandidates",
                            "approximateIndexHeapDeltaBytes", "largestVisitedBucket"]}})
        report["complete"] = True
        save()
        print(f"Complete: {args.output}")
    except (RuntimeError, subprocess.SubprocessError, AssertionError) as error:
        report["error"] = str(error)
        save()
        print(f"Incomplete run: {error}. Partial results: {args.output}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
