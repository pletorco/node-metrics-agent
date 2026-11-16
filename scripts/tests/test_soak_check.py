import copy
import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import soak_check as soak


def measurement():
    return {"callerP99Nanos": 1000, "callerCpuNanos": 500, "callerAllocatedBytes": 272,
            "processCpuCores": .02, "medianThreads": 20, "http": {"p99Millis": 3},
            "retention": {"growthBytes": 100_000, "slopeBytesPerMinute": 10_000}}


class SoakEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.budgets = json.loads((soak.ROOT / "scripts/soak/budgets.json").read_text())

    def test_stable_control_and_enabled_comparison_passes(self):
        self.assertEqual([], soak.evaluate(measurement(), measurement(), self.budgets)[0])

    def test_resource_growth_and_host_overhead_regressions_fail(self):
        cases = [("callerP99Nanos", 100_000), ("callerCpuNanos", 100_000),
                 ("callerAllocatedBytes", 2048), ("processCpuCores", .5), ("medianThreads", 100)]
        for key, value in cases:
            enabled = measurement()
            enabled[key] = value
            with self.subTest(key=key):
                self.assertTrue(soak.evaluate(measurement(), enabled, self.budgets)[0])
        for key in ("growthBytes", "slopeBytesPerMinute"):
            enabled = measurement()
            enabled["retention"][key] = 100 * 1024 * 1024
            self.assertTrue(soak.evaluate(measurement(), enabled, self.budgets)[0])
        enabled = measurement()
        enabled["http"]["p99Millis"] = 1000
        self.assertTrue(soak.evaluate(measurement(), enabled, self.budgets)[0])

    def test_noisy_control_cannot_relax_the_enabled_budget(self):
        control = measurement()
        control["callerP99Nanos"] = 2_000_000
        self.assertIn("Control is unstable", soak.evaluate(control, measurement(), self.budgets)[0][0])

    def test_nonfinite_or_missing_budget_is_incomplete_not_clean(self):
        for value in (float("nan"), -1, True):
            budgets = dict(self.budgets, processCpuExtraCores=value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                soak.evaluate(measurement(), measurement(), budgets)
        with self.assertRaises(ValueError):
            soak.evaluate(measurement(), measurement(), {})

    def test_retention_uses_post_gc_trend_and_resists_one_outlier(self):
        stable = [(i * 10, 1_000_000) for i in range(12)]
        stable[5] = (50, 50_000_000)
        self.assertEqual(0, soak.retention(stable)["slopeBytesPerMinute"])
        growing = [(i * 10, 1_000_000 + i * 1_000_000) for i in range(12)]
        self.assertEqual(6_000_000, soak.retention(growing)["slopeBytesPerMinute"])
        with self.assertRaises(ValueError):
            soak.retention(growing[:3])

    def test_missing_windows_accounting_scenarios_or_queue_bounds_fail(self):
        fields = "seconds,measured,phase,p99Nanos,callerCpuNanos,callerAllocatedBytes,postGcHeapBytes,threads,refreshThreads,fixtureQueue,agentQueue,processed,errors,rejected,applied,recovered,actualReloads,processCpuNanos".split(",")
        rows = []
        for i in range(61):
            row = dict.fromkeys(fields, 1)
            row.update(seconds=i, measured="true", processCpuNanos=i * 1_000_000,
                       postGcHeapBytes=1_000_000, refreshThreads=14)
            row.update({key: i for key in ("processed", "errors", "rejected", "applied", "recovered", "actualReloads")})
            rows.append(row)
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "samples.csv"
            for kind in ("valid", "truncated", "queue", "nan", "noGC", "noRecovery", "missingAccounting"):
                chosen = copy.deepcopy(rows)
                if kind == "truncated":
                    chosen = chosen[:20]
                elif kind == "queue":
                    chosen[30]["agentQueue"] = 3000
                elif kind == "nan":
                    chosen[30]["callerCpuNanos"] = "NaN"
                elif kind == "noGC":
                    for row in chosen:
                        row["postGcHeapBytes"] = -1
                elif kind == "noRecovery":
                    for row in chosen:
                        row["recovered"] = 0
                elif kind == "missingAccounting":
                    chosen[30]["callerAllocatedBytes"] = -1
                with path.open("w") as file:
                    writer = csv.DictWriter(file, fields)
                    writer.writeheader()
                    writer.writerows(chosen)
                with self.subTest(kind=kind):
                    if kind == "valid":
                        self.assertEqual(61, soak.summarize_samples(path, 60, True)["windows"])
                    else:
                        with self.assertRaises(ValueError):
                            soak.summarize_samples(path, 60, True)


if __name__ == "__main__":
    unittest.main()
