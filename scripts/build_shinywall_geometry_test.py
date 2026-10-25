import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_shinywall_geometry as geo  # noqa: E402


def _variant(count: int, columns: int = 4, rows: int = 3) -> dict:
    holds = [
        {"index": i, "name": None, "col": i % columns, "row": i // columns,
         "x": float(i % columns), "y": float(i // columns)}
        for i in range(count)
    ]
    return {"hold_count": count, "grid": {"columns": columns, "rows": rows}, "confidence": "high", "holds": holds}


def _doc(**variants) -> dict:
    return {"format": geo.FORMAT, "provenance": {}, "variants": variants}


class BuildShinyWallGeometryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.out = Path(self.tmp.name) / "shinywall.sqlite3"

    def tearDown(self):
        self.tmp.cleanup()

    def test_writes_app_ids_and_separate_regions(self):
        counts = geo.build(_doc(st1=_variant(12), st2=_variant(6, columns=3, rows=2)), self.out, "ab" * 32)
        self.assertEqual({"st1": 12, "st2": 6}, counts)
        con = sqlite3.connect(self.out)
        sizes = con.execute("SELECT id, product_id, name, edge_left, edge_right, edge_bottom, edge_top "
                            "FROM product_sizes ORDER BY id").fetchall()
        self.assertEqual((9402, 93, "Standard"), sizes[0][:3])
        self.assertEqual((9403, 93, "Standard Max"), sizes[1][:3])
        # st1 region: slot 2 * 500, columns 0..3 -> x 1000..1060 inside the edges;
        # the top edge follows the highest hold (row 2 -> y 40).
        self.assertEqual((990, 1070, -10, 50), sizes[0][3:])
        placements = con.execute("SELECT placement_id, hole_id, set_id, x, y FROM placements "
                                 "WHERE placement_id BETWEEN 2000000 AND 2999999 ORDER BY placement_id").fetchall()
        self.assertEqual((2000000, 2000000, 2, 1000, 0), placements[0])
        self.assertEqual((2000011, 2000011, 2, 1060, 40), placements[-1])
        xs_st2 = [x for (x,) in con.execute("SELECT x FROM placements WHERE set_id = 3")]
        self.assertTrue(all(1500 <= x < 2000 for x in xs_st2))
        self.assertEqual(4, con.execute("SELECT COUNT(*) FROM placement_roles WHERE board_brand='shinywall'").fetchone()[0])
        self.assertEqual(0, con.execute("SELECT COUNT(*) FROM board_images").fetchone()[0])
        self.assertEqual(0, con.execute("SELECT COUNT(*) FROM leds").fetchone()[0])
        self.assertEqual({"shinywall"}, {b for (b,) in con.execute("SELECT board_brand FROM placements")})
        con.close()

    def test_all_stored_coordinates_stay_inside_backup_limits(self):
        geo.build(_doc(st5=_variant(18 * 35, columns=18, rows=35)), self.out, "cd" * 32)
        con = sqlite3.connect(self.out)
        lo, hi = con.execute("SELECT MIN(x), MAX(x) FROM placements").fetchone()
        self.assertGreaterEqual(lo, 0)
        self.assertLess(hi, 10_000)
        con.close()

    def test_rejects_gaps_wrong_counts_and_unknown_variants(self):
        broken = _variant(4)
        broken["holds"][2]["index"] = 7
        for doc in (
            _doc(st1=broken),
            _doc(st1=dict(_variant(4), hold_count=5)),
            _doc(st9=_variant(4)),
            {"format": "other", "variants": {"st1": _variant(4)}},
            _doc(),
        ):
            with self.assertRaises(geo.GeometryError):
                geo.build(doc, self.out, "00" * 32)

    def test_rejects_holds_outside_their_grid(self):
        far = _variant(4)
        far["holds"][0]["x"] = 40.0
        with self.assertRaises(geo.GeometryError):
            geo.build(_doc(st1=far), self.out, "00" * 32)

    def test_placeholder_indices_get_no_placement(self):
        variant = _variant(6)
        variant["holds"][3] = {"index": 3, "name": None, "col": None, "row": None,
                               "x": None, "y": None, "kind": "placeholder", "present": False}
        counts = geo.build(_doc(st1=variant), self.out, "11" * 32)
        self.assertEqual({"st1": 5}, counts)
        con = sqlite3.connect(self.out)
        ids = [p for (p,) in con.execute("SELECT placement_id FROM placements ORDER BY placement_id")]
        self.assertNotIn(2000003, ids)
        self.assertEqual(5, len(ids))
        con.close()

    def test_quarter_cell_rows_stay_on_integers(self):
        variant = _variant(2)
        variant["holds"][1]["y"] = 0.25
        geo.build(_doc(st1=variant), self.out, "22" * 32)
        con = sqlite3.connect(self.out)
        self.assertEqual([0, 5], [y for (y,) in con.execute("SELECT y FROM placements ORDER BY placement_id")])
        con.close()

    def test_same_input_gives_the_same_bytes(self):
        doc = _doc(st1=_variant(12), st4=_variant(12))
        geo.build(doc, self.out, "ef" * 32)
        first = self.out.read_bytes()
        geo.build(doc, self.out, "ef" * 32)
        self.assertEqual(first, self.out.read_bytes())


if __name__ == "__main__":
    unittest.main()
