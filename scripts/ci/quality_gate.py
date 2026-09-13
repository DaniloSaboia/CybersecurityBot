"""Fail-closed checks for retrieval quality and mandatory JUnit execution.

These gates validate report structure and declared metrics, not scientific truth.
Retrieval thresholds are configurable starting budgets, not expert-calibrated claims.
"""
import argparse
import json
import math
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def require(condition, message):
    if not condition:
        raise ValueError(message)


def retrieval(path, recall, mrr, ndcg):
    report = json.loads(path.read_text())
    require(isinstance(report, dict), "report must be an object")
    count = report.get("cases")
    require(type(count) is int and count > 0, "cases must be a positive integer")
    results = report.get("results")
    require(isinstance(results, list) and len(results) == count, "results count differs from cases")
    ids = []
    for result in results:
        require(isinstance(result, dict), "each result must be an object")
        case_id = result.get("id")
        require(isinstance(case_id, str) and case_id.strip(), "each result requires a case id")
        require(type(result.get("pass")) is bool, "each result requires boolean pass")
        ids.append(case_id)
    require(len(set(ids)) == count, "duplicate case IDs")
    passed = report.get("passed")
    require(type(passed) is int and passed == sum(r["pass"] for r in results), "passed total differs from results")
    require(passed == count, "not all cases passed")
    for field, threshold in [("meanRecallAtK", recall), ("meanMrr", mrr), ("meanNdcgAtK", ndcg)]:
        value = report.get(field)
        require(type(value) in (float, int) and math.isfinite(value) and 0 <= value <= 1,
                f"{field} must be finite and in [0,1]")
        require(value >= threshold, f"{field}={value} below threshold {threshold}")
    return f"Retrieval gate passed: {count} cases"


def junit(paths, required_suites=()):
    total = 0
    seen = set()
    for path in paths:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall(".//testsuite")
        require(bool(suites), f"{path}: no testsuites")
        for suite in suites:
            seen.add(suite.get("name"))
            cases = suite.findall("testcase")
            require(bool(cases), f"{path}: no tests executed")
            require(int(suite.get("tests", "-1")) == len(cases), f"{path}: tests count mismatch")
            for status in ("failures", "errors", "skipped"):
                require(int(suite.get(status, "0")) == 0, f"{path}: {status} must be zero")
            for case in cases:
                for status in ("failure", "error", "skipped"):
                    require(case.find(status) is None, f"{path}: testcase has {status}")
            total += len(cases)
    require(total > 0, "no tests executed")
    missing = set(required_suites) - seen
    require(not missing, "missing required suites: " + ", ".join(sorted(missing)))
    return f"JUnit gate passed: {total} tests, no skips or failures"


def probability(value):
    number = float(value)
    if not math.isfinite(number) or not 0 <= number <= 1:
        raise argparse.ArgumentTypeError("threshold must be finite and in [0,1]")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    retrieval_parser = modes.add_parser("retrieval")
    retrieval_parser.add_argument("report", type=Path)
    retrieval_parser.add_argument("--recall", type=probability, default=0.8)
    retrieval_parser.add_argument("--mrr", type=probability, default=0.6)
    retrieval_parser.add_argument("--ndcg", type=probability, default=0.6)
    junit_parser = modes.add_parser("junit")
    junit_parser.add_argument("reports", nargs="+", type=Path)
    junit_parser.add_argument("--require-suite", action="append", default=[])
    args = parser.parse_args()
    try:
        message = (retrieval(args.report, args.recall, args.mrr, args.ndcg)
                   if args.mode == "retrieval" else junit(args.reports, args.require_suite))
    except (ValueError, OSError, ET.ParseError) as error:
        print(f"Quality gate failed: {error}", file=sys.stderr)
        return 1
    print(message)
    return 0


if __name__ == "__main__":
    sys.exit(main())
