"""Offline unit tests for train_indian.py (no network, no ultralytics). Needs numpy-free stdlib + Pillow.

  python3 -m unittest discover -s models/train -p 'test_*.py' -v
"""
import io
import json
import sys
import tarfile
import tempfile
import unittest
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import train_indian as ti  # noqa: E402


def _jpg(w=1920, h=994) -> bytes:
    from PIL import Image
    b = io.BytesIO()
    Image.new("RGB", (w, h), (90, 90, 90)).save(b, format="JPEG")
    return b.getvalue()


def _frame(name, labels):
    return {"name": name, "attributes": {"weather": "clear", "scene": "city street", "timeofday": "night"}, "labels": labels}


def _lab(cat, x1, y1, x2, y2, **extra):
    d = {"id": 1, "category": cat, "box2d": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
         "attributes": {"occluded": False, "truncated": False}, "track_id": 3}
    d.update(extra)
    return d


class ClassMappingTest(unittest.TestCase):
    def test_targets_are_the_app_labels(self):
        self.assertEqual(ti.TARGET_CLASSES, ["person", "rider", "bicycle", "motorcycle", "autorickshaw", "car", "truck", "bus", "animal"])

    def test_source_categories(self):
        m = ti.map_category
        self.assertEqual(m("person"), "person")
        self.assertEqual(m("rider"), "rider")
        self.assertEqual(m("Auto-Rickshaw"), "autorickshaw")
        self.assertEqual(m("autorickshaw"), "autorickshaw")
        self.assertEqual(m("motorcycle"), "motorcycle")
        self.assertEqual(m("animal"), "animal")
        self.assertIsNone(m("traffic light"))
        self.assertIsNone(m("traffic_sign"))
        self.assertIsNone(m("something else"))
        self.assertEqual(m("vehicle fallback", "car"), "car")
        self.assertEqual(m("vehicle_fallback", "car"), "car")
        self.assertIsNone(m("vehicle fallback", "drop"))
        with self.assertRaises(ValueError):
            m("vehicle fallback", "bogus")

    def test_prediction_names(self):
        p = ti.map_prediction_name
        self.assertEqual(p("autorickshaw"), ["autorickshaw"])  # fine-tuned model
        self.assertEqual(p("rider"), ["rider"])
        self.assertEqual(p("cow"), ["animal"])                  # stock COCO model
        self.assertEqual(p("dog"), ["animal"])
        self.assertEqual(p("person"), ["person"])
        self.assertEqual(p("traffic light"), [])
        self.assertEqual(p("airplane"), [])


class BddParsingTest(unittest.TestCase):
    def test_parse_frame_and_score_filter(self):
        f = _frame("clipA/0001.jpg", [
            _lab("car", 10, 20, 110, 120),
            _lab("traffic sign", 0, 0, 5, 5),
            _lab("vehicle fallback", 200, 200, 300, 300),
            _lab("rider", 400, 100, 450, 200, confidence=0.3),
            _lab("autorickshaw", 500, 100, 600, 200, score=0.9),
        ])
        boxes, low, st = ti.parse_bdd_frame(f, fallback="car", min_score=0.5)
        self.assertEqual([c for c, _ in boxes], ["car", "car", "autorickshaw"])
        self.assertEqual([c for c, _ in low], ["rider"])
        self.assertEqual(st["below_min_score"], 1)
        self.assertEqual(st["with_score"], 2)
        self.assertEqual(st["dropped:traffic sign"], 1)
        boxes, low, _ = ti.parse_bdd_frame(f, fallback="drop", min_score=0.0)
        self.assertEqual(low, [])
        self.assertEqual([c for c, _ in boxes], ["car", "rider", "autorickshaw"])

    def test_frame_json_variants(self):
        f = _frame("c/0001.jpg", [])
        self.assertEqual(ti.load_frame_json(json.dumps(f).encode())["name"], "c/0001.jpg")
        self.assertEqual(ti.load_frame_json(json.dumps([f]).encode())["name"], "c/0001.jpg")
        wrapped = {"name": "c/0001.jpg", "frames": [{"labels": []}]}
        self.assertEqual(ti.load_frame_json(json.dumps(wrapped).encode())["labels"], [])
        with self.assertRaises(ti.AnnotationFormatError) as e:
            ti.load_frame_json(json.dumps({"foo": 1, "bar": [1]}).encode())
        self.assertIn("foo", str(e.exception))

    def test_clip_ids_and_split(self):
        self.assertEqual(ti.clip_id_of("abc_def_0042"), "abc_def")
        self.assertEqual(ti.clip_id_of("x_0001", {"name": "clipZ/0001.jpg"}), "clipZ")
        self.assertEqual(ti.split_of("clipZ", 10), ti.split_of("clipZ", 10))
        splits = Counter(ti.split_of(f"clip{i}", 10) for i in range(2000))
        self.assertTrue(120 < splits["val"] < 280, splits)

    def test_yolo_line(self):
        self.assertEqual(ti.to_yolo_line(5, (0, 0, 100, 50), 200, 100), "5 0.250000 0.250000 0.500000 0.500000")
        self.assertEqual(ti.to_yolo_line(0, (-10, -10, 50, 50), 100, 100), "0 0.250000 0.250000 0.500000 0.500000")
        self.assertIsNone(ti.to_yolo_line(0, (10, 10, 10, 20), 100, 100))


class ShardPrepareTest(unittest.TestCase):
    def _shard(self, path: Path, samples):
        with tarfile.open(path, "w") as tf:
            for key, frame in samples:
                for ext, data in (("jpg", _jpg()), ("png", b"\x89PNG"), ("json", json.dumps(frame).encode())):
                    ti_ = tarfile.TarInfo(f"{key}.{ext}")
                    ti_.size = len(data)
                    tf.addfile(ti_, io.BytesIO(data))

    def test_prepare_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            shard_dir = d / "hf" / "indian-road-dataset" / "data"
            shard_dir.mkdir(parents=True)
            samples = []
            for c in range(12):
                for fr in range(2):
                    samples.append((f"clip{c:02d}_{fr:04d}", _frame(f"clip{c:02d}/{fr:04d}.jpg", [
                        _lab("car", 960, 497, 1920, 994), _lab("autorickshaw", 0, 0, 3, 3), _lab("traffic light", 1, 1, 50, 50),
                        _lab("bus", 0, 600, 300, 900, confidence=0.2),            # unsure, clear area: masked
                        _lab("rider", 1000, 500, 1200, 900, confidence=0.3)])))   # unsure, on the car: dropped
            self._shard(shard_dir / "train-00000-of-00646.tar", samples)
            self.assertEqual(sum(1 for _ in ti.iter_shard_samples(shard_dir / "train-00000-of-00646.tar")), 24)
            rc = ti.main(["--work", str(d), "prepare", "--val-pct", "30"])
            self.assertEqual(rc, 0)
            out = d / "yolo"
            imgs = list((out / "images").rglob("*.jpg"))
            self.assertEqual(len(imgs), 24)
            from PIL import Image
            self.assertEqual(max(Image.open(imgs[0]).size), 640)
            # Frames of one clip never straddle train/val.
            for c in range(12):
                where = {p.parent.name for p in imgs if p.name.startswith(f"clip{c:02d}_")}
                self.assertEqual(len(where), 1, where)
            lab = (out / "labels" / imgs[0].parent.name / (imgs[0].stem + ".txt")).read_text().split()
            self.assertEqual(lab, ["5", "0.750000", "0.750000", "0.500000", "0.500000"])  # tiny auto + light dropped
            y = (out / "data.yaml").read_text()
            self.assertIn("4: autorickshaw", y)
            self.assertIn("nc: 9", y)
            stats = json.loads((out / "prepare_stats.json").read_text())
            self.assertEqual(stats["tiny_box_dropped"], 24)
            self.assertEqual(stats["low_conf_masked"], 24)
            self.assertEqual(stats["low_conf_dropped_overlap"], 24)
            im = Image.open(imgs[0]).convert("RGB")
            s = 640 / 1920
            self.assertGreater(im.getpixel((int(150 * s), int(750 * s)))[0], 105)   # masked grey (114)
            self.assertLess(im.getpixel((int(1500 * s), int(200 * s)))[0], 100)     # untouched (90)
            # Resumable: a second run converts nothing new.
            ti.main(["--work", str(d), "prepare", "--val-pct", "30"])
            self.assertEqual(json.loads((out / "prepare_stats.json").read_text())["already_done"], 24)
            # Resuming with a different split is refused instead of leaking clips across splits.
            with self.assertRaises(SystemExit):
                ti.main(["--work", str(d), "prepare", "--val-pct", "10"])


def _coco():
    return {
        "images": [{"id": 1, "file_name": "a.jpg", "width": 1920, "height": 994, "time_of_day": "night", "clip_id": "c1"},
                   {"id": 2, "file_name": "b.jpg", "width": 1920, "height": 994, "time_of_day": "day", "clip_id": "c2"}],
        "categories": [{"id": 1, "name": "car"}, {"id": 2, "name": "rider"}, {"id": 3, "name": "vehicle fallback"},
                       {"id": 4, "name": "traffic sign"}, {"id": 5, "name": "autorickshaw"}],
        "annotations": [
            {"id": 1, "image_id": 1, "category_id": 1, "bbox": [100, 100, 200, 100], "iscrowd": 0},
            {"id": 2, "image_id": 1, "category_id": 2, "bbox": [500, 100, 50, 120], "iscrowd": 0},
            {"id": 3, "image_id": 1, "category_id": 3, "bbox": [800, 100, 100, 100], "iscrowd": 0},
            {"id": 4, "image_id": 1, "category_id": 1, "bbox": [1200, 100, 100, 100], "iscrowd": 1},
            {"id": 5, "image_id": 2, "category_id": 4, "bbox": [10, 10, 20, 20], "iscrowd": 0},
            {"id": 6, "image_id": 2, "category_id": 5, "bbox": [300, 300, 150, 150], "iscrowd": 0},
        ],
    }


class CocoEvalTest(unittest.TestCase):
    def test_bad_structure_shows_keys(self):
        with self.assertRaises(ti.AnnotationFormatError) as e:
            ti.load_coco({"frames": [], "meta": {"v": 1}})
        self.assertIn("frames", str(e.exception))
        self.assertIn("meta", str(e.exception))

    def test_ground_truth_mapping(self):
        gt = ti.coco_ground_truth(ti.load_coco(_coco()))
        self.assertEqual(sorted(c for c, _ in gt[1]["gt"]), ["car", "rider"])
        self.assertEqual(sorted(str(c) for c, _ in gt[1]["ignore"]), ["None", "car"])  # fallback + crowd
        self.assertEqual([c for c, _ in gt[2]["gt"]], ["autorickshaw"])                 # sign dropped
        gt_car = ti.coco_ground_truth(ti.load_coco(_coco()), "car")
        self.assertEqual(sorted(c for c, _ in gt_car[1]["gt"]), ["car", "car", "rider"])

    def test_ap_perfect_and_ignore(self):
        gt = ti.coco_ground_truth(ti.load_coco(_coco()))
        perfect = {1: [("car", 0.9, (100, 100, 300, 200)), ("rider", 0.8, (500, 100, 550, 220)),
                       ("car", 0.7, (1200, 100, 1300, 200)),    # on an ignore region: no penalty
                       ("car", 0.6, (805, 105, 900, 200))],     # on the vehicle-fallback ignore: no penalty
                   2: [("autorickshaw", 0.9, (300, 300, 450, 450))]}
        r = ti.evaluate_predictions(gt, perfect)
        self.assertAlmostEqual(r["car"]["ap"], 1.0)
        self.assertAlmostEqual(r["rider"]["ap50"], 1.0)
        self.assertAlmostEqual(r["autorickshaw"]["ap"], 1.0)
        self.assertIsNone(r["bus"]["ap"])  # no ground truth

    def test_ap_false_positive_and_miss(self):
        gt = ti.coco_ground_truth(ti.load_coco(_coco()))
        # Higher-scored false positive ahead of the true positive -> AP 0.5 at every threshold.
        preds = {1: [("car", 0.95, (1500, 500, 1600, 600)), ("car", 0.9, (100, 100, 300, 200))]}
        self.assertAlmostEqual(ti.evaluate_predictions(gt, preds)["car"]["ap50"], 0.5, places=2)
        # Loose box: counts at IoU 0.5 but not at 0.95.
        loose = {1: [("car", 0.9, (100, 100, 330, 210))]}
        r = ti.evaluate_predictions(gt, loose)["car"]
        self.assertAlmostEqual(r["ap50"], 1.0)
        self.assertLess(r["ap"], 0.9)
        self.assertEqual(ti.evaluate_predictions(gt, {})["autorickshaw"]["ap"], 0.0)

    def test_pick_shards_spreads(self):
        self.assertEqual(ti.pick_shards(4, 646), [0, 161, 323, 484])
        self.assertEqual(len(ti.pick_shards(1000, 646)), 646)


if __name__ == "__main__":
    unittest.main()
