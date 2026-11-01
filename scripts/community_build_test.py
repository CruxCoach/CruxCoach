import tempfile
import unittest

import community_build as cb

GRANT = {
    "project": "cruxcoach",
    "track": "c-led-pulse-1a2b3c4d",
    "package_name": "com.cruxcoach.android.dev.community.c_1a2b3c4d5e6f",
    "label": "alice: led-pulse",
    "branch": "alice/CruxCoach/feat/led-pulse",
    "commit": "a" * 40,
    "version_code": 3,
    "publisher": {"github_login": "alice", "display_name": "Alice"},
}


class CommunityBuildTest(unittest.TestCase):
    def test_outputs_carry_only_validated_values(self) -> None:
        outputs = cb.outputs_for(GRANT, "feat/led-pulse", "alice")
        self.assertEqual(outputs["authorized"], "true")
        self.assertEqual(outputs["feature_branch"], "feat/community/alice/led-pulse")
        self.assertEqual(outputs["publisher_name"], "Alice")
        with tempfile.NamedTemporaryFile("r+", encoding="utf-8") as stream:
            cb.write_outputs(stream.name, outputs)
            self.assertIn("package=com.cruxcoach.android.dev.community.c_1a2b3c4d5e6f\n", stream.read())

    def test_rejects_values_outside_the_community_namespace(self) -> None:
        for override in (
            {"track": "feat-led-1a2b3c4d"},
            {"package_name": "com.cruxcoach.android.dev.f_1a2b3c4d5e6f"},
            {"package_name": "com.cruxcoach.android"},
            {"label": 'x"; rm -rf /'},
            {"version_code": 0},
            {"publisher": {"github_login": "-bad"}},
        ):
            with self.subTest(override=override), self.assertRaises(SystemExit):
                cb.outputs_for({**GRANT, **override}, "feat/led-pulse", "alice")

    def test_outputs_refuse_multiline_values(self) -> None:
        with tempfile.NamedTemporaryFile("r+", encoding="utf-8") as stream, self.assertRaises(ValueError):
            cb.write_outputs(stream.name, {"x": "a\nb=c"})

    def test_badging_parser(self) -> None:
        text = "package: name='com.cruxcoach.android.dev.community.c_1a2b3c4d5e6f' versionCode='3' versionName='0.2.5'\n"
        self.assertEqual(cb.parse_badging(text), ("com.cruxcoach.android.dev.community.c_1a2b3c4d5e6f", 3))
        with self.assertRaises(SystemExit):
            cb.parse_badging("nothing here")

    def test_comments_explain_the_next_step(self) -> None:
        missing = cb.comment_body(reason="not_linked", publisher_login="alice")
        self.assertIn(cb.MARKER, missing)
        self.assertIn("send `/link`", missing)
        ready = cb.comment_body(
            reason=None, publisher_login="alice", track=GRANT["track"], version_code=3, name="Alice"
        )
        self.assertIn("Test build 3 by @alice is ready", ready)
        self.assertIn("track=c-led-pulse-1a2b3c4d&build=3#install", ready)
        self.assertNotIn("download=1", ready)


if __name__ == "__main__":
    unittest.main()

