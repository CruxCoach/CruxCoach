import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_shinywall_geometry as geo  # noqa: E402
import build_shinywall_images as img  # noqa: E402

try:
    import PIL  # noqa: F401
    HAVE_PIL = True
except ImportError:  # CI runs the scripts' tests without Pillow
    HAVE_PIL = False


def _variant(count: int, columns: int = 4, rows: int = 3) -> dict:
    holds = [
        {"index": i, "name": f"h{i}", "col": i % columns, "row": i // columns,
         "x": float(i % columns), "y": float(i // columns) * 0.75}
        for i in range(count)
    ]
    holds[-1]["present"] = False  # a placeholder index gets no photo
    return {"hold_count": count, "grid": {"columns": columns, "rows": rows}, "holds": holds}


def _doc(**variants) -> dict:
    return {"format": geo.FORMAT, "provenance": {}, "variants": variants}


class BuildShinyWallImagesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_image_covers_the_geometry_bundle_board_area_and_holds(self):
        doc = _doc(st1=_variant(12), st5=_variant(10, columns=5, rows=2))
        geo.build(doc, self.dir / "geo.sqlite3", "ab" * 32)
        con = sqlite3.connect(self.dir / "geo.sqlite3")
        for code, variant in doc["variants"].items():
            image = img.layout(code, variant)
            left, right, bottom, top = con.execute(
                "SELECT edge_left, edge_right, edge_bottom, edge_top FROM product_sizes WHERE id = ?",
                (image.size_id,),
            ).fetchone()
            self.assertEqual((right - left) * img.PX_PER_UNIT, image.width)
            self.assertEqual((top - bottom) * img.PX_PER_UNIT, image.height)
            placements = con.execute(
                "SELECT x, y FROM placements WHERE set_id = ? ORDER BY placement_id",
                (geo.VARIANT_SLOTS[code],),
            ).fetchall()
            # Same holds in the same order, each centred where the app draws it.
            self.assertEqual(len(placements), len(image.holds))
            for (x, y), hold in zip(placements, image.holds):
                self.assertEqual((x - left) * img.PX_PER_UNIT, hold.center_x)
                self.assertEqual((top - y) * img.PX_PER_UNIT, hold.center_y)
        con.close()

    def test_a_hold_without_photo_name_is_an_error(self):
        variant = _variant(4)
        variant["holds"][0]["name"] = None
        with self.assertRaises(img.ImageBuildError):
            img.layout("st1", variant)

    @unittest.skipUnless(HAVE_PIL, "Pillow not installed")
    def test_render_places_each_photo_on_the_wall(self):
        from PIL import Image

        holds_dir = self.dir / "holds"
        holds_dir.mkdir()
        for i in range(12):
            # Like the app's photos: a hold in the middle of a transparent tile.
            tile = Image.new("RGBA", (75, 75), (0, 0, 0, 0))
            tile.paste((250, 250, 250, 255), (25, 25, 50, 50))
            tile.save(holds_dir / f"h{i}.png")
        image = img.layout("st1", _variant(12))
        wall = img.render(image, holds_dir)
        self.assertEqual((image.width, image.height), wall.size)
        self.assertEqual(img.BACKGROUND, wall.getpixel((0, 0)))
        for hold in image.holds:
            self.assertEqual((250, 250, 250), wall.getpixel((int(hold.center_x), int(hold.center_y))))
        missing = img.layout("st1", _variant(14))
        with self.assertRaises(img.ImageBuildError):
            img.render(missing, holds_dir)


if __name__ == "__main__":
    unittest.main()
