"""Contract tests for fail-closed CI result validation (no services needed)."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

GATE = Path(__file__).with_name("quality_gate.py")


class QualityGateTest(unittest.TestCase):
    def run_gate(self, mode, content, *args):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ("report.json" if mode == "retrieval" else "TEST-integration.xml")
            path.write_text(content)
            return subprocess.run([sys.executable, str(GATE), mode, str(path), *args],
                                  capture_output=True, text=True)

    def report(self, **changes):
        report = {"cases": 1, "passed": 1, "meanRecallAtK": 0.9,
                  "meanMrr": 0.8, "meanNdcgAtK": 0.8,
                  "results": [{"id": "case-1", "pass": True}]}
        report.update(changes)
        return json.dumps(report)

    def test_accepts_healthy_retrieval(self):
        result = self.run_gate("retrieval", self.report())
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_regression(self):
        result = self.run_gate("retrieval", self.report(meanRecallAtK=0.2))
        self.assertEqual(result.returncode, 1)
        self.assertIn("meanRecallAtK", result.stderr)

    def test_rejects_empty_report(self):
        result = self.run_gate("retrieval", self.report(cases=0, passed=0, results=[]))
        self.assertEqual(result.returncode, 1)
        self.assertIn("cases", result.stderr)

    def test_rejects_nan(self):
        result = self.run_gate("retrieval", self.report(meanMrr=float("nan")))
        self.assertEqual(result.returncode, 1)
        self.assertIn("meanMrr", result.stderr)

    def test_rejects_inconsistent_totals(self):
        result = self.run_gate("retrieval", self.report(cases=3))
        self.assertEqual(result.returncode, 1)
        self.assertIn("results", result.stderr)

    def test_rejects_false_case_even_with_claimed_pass_total(self):
        result = self.run_gate("retrieval", self.report(results=[{"id": "case-1", "pass": False}]))
        self.assertEqual(result.returncode, 1)
        self.assertIn("passed", result.stderr)

    def test_rejects_duplicate_case_ids(self):
        result = self.run_gate("retrieval", self.report(cases=2, passed=2, results=[
            {"id": "same", "pass": True}, {"id": "same", "pass": True}]))
        self.assertEqual(result.returncode, 1)
        self.assertIn("duplicate", result.stderr)

    def test_accepts_executed_integration_report(self):
        xml = '<testsuite name="DatabaseIntegrationTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="query"/></testsuite>'
        result = self.run_gate("junit", xml)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_skipped_integration_report(self):
        xml = '<testsuite name="DatabaseIntegrationTest" tests="1" failures="0" errors="0" skipped="1"><testcase name="query"><skipped/></testcase></testsuite>'
        result = self.run_gate("junit", xml)
        self.assertEqual(result.returncode, 1)
        self.assertIn("skipped", result.stderr)

    def test_requires_every_named_integration_suite(self):
        xml = '<testsuite name="DatabaseIntegrationTest" tests="1"><testcase name="query"/></testsuite>'
        present = self.run_gate("junit", xml, "--require-suite", "DatabaseIntegrationTest")
        self.assertEqual(present.returncode, 0, present.stderr)
        missing = self.run_gate("junit", xml, "--require-suite", "DatabaseIntegrationTest",
                                "--require-suite", "SecurityIntegrationTest")
        self.assertEqual(missing.returncode, 1)
        self.assertIn("missing required suites: SecurityIntegrationTest", missing.stderr)

    def test_rejects_empty_junit_report(self):
        result = self.run_gate("junit", '<testsuite tests="0"/>')
        self.assertEqual(result.returncode, 1)
        self.assertIn("tests", result.stderr)

    def test_rejects_junit_failure_even_if_summary_is_wrong(self):
        xml = '<testsuite tests="1" failures="0"><testcase name="query"><failure>broken</failure></testcase></testsuite>'
        result = self.run_gate("junit", xml)
        self.assertEqual(result.returncode, 1)
        self.assertIn("failure", result.stderr)


if __name__ == "__main__":
    unittest.main()
