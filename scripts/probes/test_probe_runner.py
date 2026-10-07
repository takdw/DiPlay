"""Desktop tests for selecting the intended device and accepting complete diagnostics."""

import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "probe_byd_ambient", Path(__file__).resolve().parents[1] / "probe_byd_ambient.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ProbeRunnerTest(unittest.TestCase):
    def test_device_selection_requires_one_authorized_device(self):
        for output in (
            "List of devices attached\n",
            "List of devices attached\ncar\tunauthorized\n",
            "List of devices attached\ncar\toffline\n",
            "List of devices attached\ncar\tdevice\nphone\tdevice\n",
        ):
            with self.subTest(output=output), patch.object(runner, "run", return_value=output):
                with self.assertRaises(RuntimeError):
                    runner.select_device("adb", None, {})

    def test_explicit_target_must_be_authorized(self):
        output = "List of devices attached\ncar\tdevice\nphone\tdevice\n"
        with patch.object(runner, "run", return_value=output):
            self.assertEqual("car", runner.select_device("adb", "car", {}))
            with self.assertRaises(RuntimeError):
                runner.select_device("adb", "another-device", {})

    def test_report_excludes_unrelated_sdk_output(self):
        stdout = (
            "unrelated SDK startup message\n" + runner.HEADER + "\r\n"
            "read.SET_IAL_COLOR_CONFIG=-1 (0xffffffff)\r\n"
            "vehicle_compatibility=unverified\r\n"
            "write_authorization=untested\r\n"
            "probe.complete=true\r\n"
            "unrelated SDK shutdown message\n"
        )
        report = runner.diagnostic_report(stdout)
        self.assertNotIn("unrelated", report)
        self.assertIn("-1 (0xffffffff)", report)
        self.assertIn("vehicle_compatibility=unverified", report)

    def test_partial_or_timed_out_reports_are_rejected(self):
        for stdout in (
            "probe.complete=true\n",
            runner.HEADER + "\nread.SET_IAL_FRONT_COLOR=4\n",
            runner.HEADER + "\nprobe.complete=false\n",
            runner.HEADER + "\nprobe.complete=false\nprobe.complete=true\n",
        ):
            with self.subTest(stdout=stdout), self.assertRaises(RuntimeError):
                runner.diagnostic_report(stdout)


if __name__ == "__main__":
    unittest.main()
