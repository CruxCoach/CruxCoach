from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from render_junit_html import render as render_junit
from render_root_html import render as render_root
from render_run_html import render as render_run


class HtmlReportTest(unittest.TestCase):
    def test_junit_html_escapes_untrusted_xml_text(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "junit.xml"
            output = root / "junit-only.html"
            report.write_text(
                """<testsuite name="&lt;script&gt;bad&lt;/script&gt;">
                <testcase classname="A&amp;B" name="x&lt;img src=x onerror=1&gt;" time="0.1">
                  <failure message="&lt;b&gt;no&lt;/b&gt;">trace &amp; detail</failure>
                </testcase></testsuite>""",
                encoding="utf-8",
            )
            render_junit(report, output)
            text = output.read_text(encoding="utf-8")
            self.assertNotIn("<script>bad</script>", text)
            self.assertNotIn("<img src=x onerror=1>", text)
            self.assertIn("&lt;script&gt;bad&lt;/script&gt;", text)
            self.assertIn("&lt;img src=x onerror=1&gt;", text)
            self.assertIn("JUnit-only", text)

    def test_junit_html_rejects_empty_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "junit.xml"
            report.write_text("<testsuite />", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "no testcase"):
                render_junit(report, root / "junit-only.html")

    def test_root_html_uses_all_component_verdicts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            statuses = {
                "maestro": "PASS",
                "evidence_capture": "PASS",
                "junit": "PASS",
                "junit_html": "PASS",
                "process_health": "PASS",
                "expectations": "FAIL",
                "identity_proof": "SKIP",
                "post_hook": "PASS",
            }
            render_root(
                root / "report.html",
                flow="x<script>",
                result="FAIL",
                maestro_exit=0,
                infrastructure_retries=1,
                statuses=statuses,
            )
            text = (root / "report.html").read_text(encoding="utf-8")
            self.assertIn("ROOT FAIL", text)
            self.assertIn("Scoped Logcat expectations", text)
            self.assertIn("x&lt;script&gt;", text)
            self.assertNotIn("x<script>", text)

    def test_root_html_rejects_false_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            statuses = {
                "maestro": "PASS", "evidence_capture": "FAIL",
                "junit": "PASS", "junit_html": "PASS",
                "process_health": "PASS", "expectations": "PASS",
                "identity_proof": "SKIP", "post_hook": "PASS",
            }
            with self.assertRaisesRegex(ValueError, "disagrees"):
                render_root(
                    Path(directory) / "report.html", flow="x", result="PASS",
                    maestro_exit=0, infrastructure_retries=0, statuses=statuses,
                )

    def test_run_html_links_only_inside_run_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            evidence = run_dir / "repeat-01" / "001-a&b"
            evidence.mkdir(parents=True)
            results = run_dir / "results.tsv"
            results.write_text(
                "repeat\tsequence\tflow\tresult\tmaestro_exit\tinfrastructure_retries\tevidence\n"
                f"1\t1\ta<script>\tPASS\t0\t0\t{evidence}\n",
                encoding="utf-8",
            )
            output = run_dir / "run-report.html"
            render_run(results, run_dir, output, "PASS")
            text = output.read_text(encoding="utf-8")
            self.assertIn("a&lt;script&gt;", text)
            self.assertNotIn("a<script>", text)
            self.assertIn("repeat-01/001-a%26b/report.html", text)

    def test_run_html_rejects_external_evidence_path(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            results = run_dir / "results.tsv"
            results.write_text(
                "repeat\tsequence\tflow\tresult\tmaestro_exit\tinfrastructure_retries\tevidence\n"
                "1\t1\ta\tPASS\t0\t0\t/tmp/outside\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "escapes"):
                render_run(results, run_dir, run_dir / "run-report.html", "PASS")

    def test_run_html_fails_when_cleanup_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            evidence = run_dir / "repeat-01" / "001-root"
            evidence.mkdir(parents=True)
            results = run_dir / "results.tsv"
            results.write_text(
                "repeat\tsequence\tflow\tresult\tmaestro_exit\tinfrastructure_retries\tevidence\n"
                f"1\t1\troot\tPASS\t0\t0\t{evidence}\n",
                encoding="utf-8",
            )
            output = run_dir / "run-report.html"
            render_run(results, run_dir, output, "FAIL")
            text = output.read_text(encoding="utf-8")
            self.assertIn("<strong>FAIL</strong>", text)
            self.assertIn("cleanup/restore <span class=\"failed\">FAIL</span>", text)


if __name__ == "__main__":
    unittest.main()
