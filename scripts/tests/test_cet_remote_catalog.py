import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from cet_remote_catalog import descriptor, normalized_characters, prefix_at_utf16


class RemoteCatalogTests(unittest.TestCase):
    def fixture(self):
        text = "We read books."
        token_data = [(1, "We", 0, 2, 2, "主语"), (2, "read", 3, 7, 0, "谓语"), (3, "books", 8, 13, 2, "宾语"), (4, ".", 13, 14, 2, "标点")]
        tokens = [dict(id=i, text=t, start=a, end=b, head=h, role=r, posLabel="名词", relation="root" if h == 0 else "dep") for i, t, a, b, h, r in token_data]
        return dict(id="cet4-2026-06-1-passage1", exam="CET4", year=2026, month=6, suite=1, kind="passage1",
            wordCount=3, textSha256=hashlib.sha256(text.encode()).hexdigest(), sourceUrl="https://example.org/paper.pdf",
            sourceSha256="a" * 64, licenseUrl="https://example.org/rights", modelFingerprint="fixture-model", grammarDevice="cuda:0",
            complete=True, original=text, fills=[], translation="Test translation must not be exported",
            paragraphs=[dict(text=text, sentences=[dict(id="p1-s1", text=text, translation="Private test translation", tokens=tokens)])])

    def test_gpu_descriptors_do_not_export_prose_or_translation(self):
        result = descriptor(self.fixture())
        value = json.dumps(result)
        self.assertNotIn("We read books.", value)
        self.assertNotIn("Private test translation", value)
        self.assertNotIn("translation", result)
        self.assertEqual(3, len(result["paragraphs"][0]["sentences"][0]["tokens"]))

    def test_cpu_or_incomplete_inputs_cannot_claim_cuda(self):
        for changes in [dict(grammarDevice="cpu"), dict(complete=False)]:
            with self.assertRaises(ValueError): descriptor(dict(self.fixture(), **changes))

    def test_original_mismatch_does_not_export_stale_offsets(self):
        with self.assertRaises(ValueError): descriptor(dict(self.fixture(), original="We write notes."))

    def test_utf16_prefix_counts_surrogate_pairs(self):
        self.assertEqual("🙂 ", prefix_at_utf16("🙂 We", 3))
        self.assertEqual("afistudents", normalized_characters("Ａ ﬁ Students"))

    def test_metadata_and_separate_provenance_fingerprint_match(self):
        file = ROOT / "apps/android/app/src/main/assets/intensive/remote-index.json"
        proof = json.loads((ROOT / "content/intensive/remote-provenance.json").read_text("utf8"))
        self.assertEqual(hashlib.sha256(file.read_bytes()).hexdigest(), proof["fingerprint"])
        self.assertTrue(proof["sourceUrl"] and proof["license"])

    def test_all_published_entries_are_numeric_syntax_and_separate_source_links(self):
        index = json.loads((ROOT / "apps/android/app/src/main/assets/intensive/remote-index.json").read_text("utf8"))
        self.assertTrue(index["metadataOnly"])
        self.assertTrue(index["articles"])
        self.assertEqual(len(index["articles"]), len({r["id"] for r in index["articles"]}))
        for entry in index["articles"]:
            self.assertTrue(entry["sourceUrl"] and entry["sourceSha256"] and entry["licenseUrl"])
            self.assertNotIn("original", entry)
            self.assertNotIn("translation", entry)
            for paragraph in entry["paragraphs"]:
                self.assertEqual({"length", "sentences"}, set(paragraph))
                for sentence in paragraph["sentences"]:
                    self.assertEqual({"id", "length", "tokens"}, set(sentence))
                    self.assertTrue(all(all(isinstance(x, int) for x in t[:4]) for t in sentence["tokens"]))


if __name__ == "__main__": unittest.main()
