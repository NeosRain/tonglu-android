"""Build a metadata-only index for user-initiated CET source reading.

No exam prose, translations, PDF files or model weights are published by this
tool. Original material is retrieved from its source into private device cache.
"""
import hashlib
import argparse
import json
from pathlib import Path
import re
import unicodedata
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def normalized_characters(value):
    value = unicodedata.normalize("NFKC", value).lower()
    return "".join(c for c in value if "a" <= c <= "z" or "0" <= c <= "9")


def fingerprint(value):
    return hashlib.sha256(value.encode("utf8")).hexdigest()


def prefix_at_utf16(text, offset):
    return text.encode("utf-16-le")[:offset * 2].decode("utf-16-le")


def descriptor(article):
    if not article["complete"] or not article["grammarDevice"].startswith("cuda"):
        raise ValueError("Completed CUDA grammar result required")
    original = article["original"]
    filled = original
    for item in article["fills"]:
        number = str(item["number"])
        pattern = r"_+\s*" + number + r"\s*_+|\[\s*" + number + r"\s*\]"
        filled, count = re.subn(pattern, item["word"], filled, count=1)
        if count != 1:
            raise ValueError("Cannot identify blank " + number)
    body = "".join(normalized_characters(p["text"]) for p in article["paragraphs"])
    if normalized_characters(filled) != body:
        raise ValueError("Original/body normalization mismatch: " + article["id"])
    paragraphs = []
    for paragraph in article["paragraphs"]:
        sentences = []
        for sentence in paragraph["sentences"]:
            kept = [t for t in sentence["tokens"] if normalized_characters(t["text"])]
            ids = {t["id"]: i + 1 for i, t in enumerate(kept)}
            tokens = []
            for token in kept:
                head = token["head"]
                while head and head not in ids:
                    head = sentence["tokens"][head - 1]["head"]
                start = len(normalized_characters(prefix_at_utf16(sentence["text"], token["start"])))
                end = len(normalized_characters(prefix_at_utf16(sentence["text"], token["end"])))
                tokens.append([ids[token["id"]], start, end, ids.get(head, 0), token["role"], token["posLabel"], token["relation"]])
            sentences.append(dict(id=sentence["id"], length=len(normalized_characters(sentence["text"])), tokens=tokens))
        assert sum(s["length"] for s in sentences) == len(normalized_characters(paragraph["text"]))
        paragraphs.append(dict(length=len(normalized_characters(paragraph["text"])), sentences=sentences))
    result = {k: article[k] for k in ["id", "exam", "year", "month", "suite", "kind", "wordCount", "textSha256", "sourceUrl", "sourceSha256", "licenseUrl", "modelFingerprint", "grammarDevice"]}
    normalized = normalized_characters(original)
    result.update(originalLength=len(normalized), originalSha256=fingerprint(normalized),
        prefixSha256=fingerprint(normalized[:48]), bodySha256=fingerprint(body),
        sentences=sum(len(p["sentences"]) for p in paragraphs), paragraphs=paragraphs,
        sourceFormat="markdown" if urlsplit(article["sourceUrl"]).path.endswith(".md") else "pdf",
        fills=article["fills"], rightsNotice="原卷权利归相应权利人；应用只索引来源，点选后读取到本机，不随安装包分发正文")
    return result


def build(corpus, output):
    entries, excluded = [], []
    for file in sorted(corpus.glob("*.json")):
        article = json.loads(file.read_text("utf8"))
        if article.get("qualityNotice"):
            excluded.append(dict(id=article["id"], reason="扫描识别稿尚未逐字校对，暂不声明可直接精读"))
            continue
        entries.append(descriptor(article))
    result = dict(schemaVersion=1, revision="cet-remote-syntax-20261026-v1", metadataOnly=True,
        license="GPL-3.0-only (index and numeric grammar only; not exam source PDFs)", articles=entries, unavailable=excluded)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf8")
    proof = dict(sourceUrl="https://github.com/0609x/CET46-Resources", license=result["license"],
        fingerprint=hashlib.sha256(output.read_bytes()).hexdigest(), file=output.relative_to(ROOT).as_posix(), entries=len(entries),
        distribution="metadata only; no prose, translation, PDFs or model weights",
        sourceRights="Each entry separately records upstream source URL, PDF SHA and rights URL")
    (ROOT / "content/intensive/remote-provenance.json").write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding="utf8")
    print(json.dumps(dict(entries=len(entries), excluded=len(excluded), bytes=output.stat().st_size, sha256=proof["fingerprint"])))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / "work/intensive-reading/analyzed")
    parser.add_argument("--out", type=Path, default=ROOT / "apps/android/app/src/main/assets/intensive/remote-index.json")
    args = parser.parse_args()
    build(args.corpus, args.out)
