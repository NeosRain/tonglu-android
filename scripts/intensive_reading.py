"""Build verified, GPU-analyzed CET intensive-reading packs.

Original PDFs and model weights stay in work/. Use bundled PDF Python for
prepare, and the existing CUDA Python for analyze. No CPU inference fallback.
"""
from pathlib import Path
import argparse
import gzip
import hashlib
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "work/intensive-reading"
REPO = (ROOT / "work/intensive-input/CET46-Resources.git")
SOURCE = "https://github.com/0609x/CET46-Resources"
SOURCE_LICENSE = "真题整理件；原文权利归相应权利人，仓库未授予统一开源许可"
ROLES = {"nsubj": "主语", "csubj": "主语", "obj": "宾语", "iobj": "宾语",
         "amod": "定语", "acl": "从句", "nmod": "定语", "advmod": "状语",
         "advcl": "从句", "obl": "状语", "xcomp": "补语", "ccomp": "从句",
         "appos": "同位语", "root": "谓语", "aux": "谓语", "cop": "谓语",
         "mark": "连接词", "cc": "连接词", "conj": "并列", "det": "限定词",
         "case": "介词", "punct": "标点", "compound": "定语", "nummod": "定语"}
POS = {"NOUN": "名词", "PROPN": "专有名词", "VERB": "动词", "AUX": "助动词",
       "ADJ": "形容词", "ADV": "副词", "PRON": "代词", "DET": "限定词",
       "ADP": "介词", "CCONJ": "并列连词", "SCONJ": "从属连词", "NUM": "数词",
       "PART": "小品词", "PUNCT": "标点", "X": "其他", "INTJ": "感叹词", "SYM": "符号"}


def digest(value):
    return hashlib.sha256(value if isinstance(value, bytes) else value.encode("utf8")).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + ".part")
    temp.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")), encoding="utf8")
    temp.replace(path)


def normalized(value):
    return re.sub(r"\s+", " ", value).strip()


def clean_furniture(value):
    # Exact recurring publisher furniture only; keep legitimate Chinese glosses.
    return re.sub(r"202[1-6]\s*年\s*\d{1,2}\s*月\s*英语[四六]级真题第\s*\d\s*套\s*第\s*\d+\s*页[，,]?\s*共\s*\d+\s*页", "", value)


def layout_paragraphs(full):
    lines = full.replace('\x0c', '\n').splitlines()
    nonempty = [line for line in lines if line.strip()]
    left = min((len(line)-len(line.lstrip()) for line in nonempty), default=0)
    right = max((len(line.rstrip()) for line in nonempty), default=0)
    blocks, current, previous = [], [], ''
    for line in lines:
        value = line.strip()
        indent = len(line)-len(line.lstrip())
        paragraph = bool(current) and (not value or indent > left+2 or (
            previous.rstrip().endswith(('.', '?', '!', '”', '"')) and right-len(previous.rstrip()) > 25))
        if paragraph:
            blocks.append(normalized(' '.join(current))); current=[]
        if value: current.append(value)
        previous=line
    if current: blocks.append(normalized(' '.join(current)))
    return '\n\n'.join(blocks)


def utf16(value):
    return len(value.encode("utf-16-le")) // 2


def aligned_words(sentence):
    """Keep the exact surface span for syntactic MWT expansions (e.g. gonna).

    If component forms concatenate exactly, each gets its own source slice.
    Otherwise each expanded word explicitly shares its parent surface range;
    `form` retains the actual model word, never a fabricated character offset.
    """
    result = {}
    for token in sentence.tokens:
        cursor = token.start_char
        concatenated = ''.join(word.text for word in token.words) == token.text
        for word in token.words:
            start, end = word.start_char, word.end_char
            if start is None or end is None:
                if concatenated:
                    start, end = cursor, cursor + len(word.text)
                    cursor = end
                else:
                    start, end = token.start_char, token.end_char
            if start is None or end is None:
                raise ValueError('unaligned surface token')
            result[word.id] = start, end
    return result


def paper_identity(path):
    if "/真题PDF/" not in path or not path.lower().endswith(".pdf"):
        return None
    date = re.search(r"/(202[1-6])\.(06|12)/", path)
    if not date or (date[1] == "2026" and date[2] != "06"):
        return None
    exam = "CET4" if path.startswith("四级") else "CET6" if path.startswith("六级") else None
    found = re.search(r"第([123一二三])套|[_-]([123])$|[（(]([一二三123])[）)]", Path(path).stem)
    if not exam or not found:
        return None
    suite = next(value for value in found.groups() if value)
    suite = int({"一": 1, "二": 2, "三": 3}.get(suite, suite))
    return dict(id=f"{exam.lower()}-{date[1]}-{date[2]}-{suite}",
                exam=exam, year=int(date[1]), month=int(date[2]), suite=suite)


def pdf_blocks(path):
    """Prefer logical PDF paragraphs to guessed visual line breaks."""
    import pdfplumber
    from pypdf import PdfReader
    with pdfplumber.open(path) as pdf:
        blocks = []
        if pdf.structure_tree:
            chars = {i + 1: page.chars for i, page in enumerate(pdf.pages)}

            def references(node):
                result = [(node["page_number"], value) for value in node.get("mcids", [])
                          if node.get("page_number")]
                for child in node.get("children", []):
                    result.extend(references(child))
                return result

            def text(node):
                result, previous = "", None
                refs = references(node)
                selected = [char for page, mcid in refs for char in chars.get(page, []) if char.get("mcid") == mcid]
                right = max((char["x1"] for char in selected), default=0)
                for page, mcid in refs:
                    for char in chars.get(page, []):
                        if char.get("mcid") != mcid:
                            continue
                        value = char["text"]
                        if previous and result and not result[-1].isspace() and not value.isspace():
                            new_line = page != previous[0] or abs(char["top"] - previous[1]["top"]) > max(
                                char["height"], previous[1]["height"]) * 0.45
                            gap = char["x0"] - previous[1]["x1"]
                            if new_line:
                                # Some tagged PDFs put several visual paragraphs
                                # in one P node. A short final line is a boundary.
                                short = right - previous[1]["x1"] > char["height"] * 3
                                result += "\n\n" if short and result.rstrip().endswith(('.', '!', '?', '"', '”')) else " "
                            elif gap > char["height"] * 0.18:
                                result += " "
                        result += value
                        previous = page, char
                return "\n\n".join(normalized(part) for part in result.split("\n\n") if normalized(part))

            def walk(node):
                if node.get("type") in {"P", "H1", "H2", "H3", "H4", "LI", "Caption", "TR"}:
                    value = text(node)
                    if value:
                        blocks.append(value)
                else:
                    for child in node.get("children", []):
                        walk(child)
            for node in pdf.structure_tree:
                walk(node)
            full = "\n\n".join(blocks)
            if len(full) > 5000 and re.search(r"Passage\s+One", full, re.I):
                return full, "tagged+short-line-paragraphs"
    reader = PdfReader(path)
    full = "\n".join(page.extract_text(extraction_mode="layout") or "" for page in reader.pages)
    return layout_paragraphs(full), "pdf-layout-indent-paragraphs"


def extract_articles(full, paper, auxiliary=None):
    import cet_pdf_to_banks as banks
    full = clean_furniture(full.replace("\u00ad", "").replace("\u200b", ""))
    match = re.search(r"Reading\s+Comprehension", full, re.I)
    first_passage = re.search(r'Passage\s+One', full, re.I)
    if match and (first_passage is None or match.start() < first_passage.start()):
        reading = full[match.end():]
    elif first_passage and re.search(r'Passage\s+Two', full, re.I):
        # Some original PDF slices start after the Part III header. Both
        # article markers AND exact 46/51 question boundaries remain required.
        reading = full
    else:
        raise ValueError("missing reading section")
    raw = clean_furniture(auxiliary or full)
    raw_start = re.search(r"Reading\s+Comprehension", raw, re.I)
    raw_passage = re.search(r'Passage\s+One', raw, re.I)
    raw_reading = raw[raw_start.end():] if raw_start and (raw_passage is None or raw_start.start() < raw_passage.start()) else raw
    results = []
    try:
        section = re.search(r"Section\s*A", reading, re.I)
        end = re.search(r"Section\s*B", reading, re.I)
        if not section or not end:
            raise ValueError("missing Section A/B boundaries")
        cloze = reading[section.end():end.start()]
        cloze = re.sub(r"^\s*(?:Banked Cloze|Vocabulary Understanding)\s*", "", cloze, flags=re.I)
        cloze = re.sub(r"^\s*Directions:[\s\S]*?(?:more than once\.|Answer Sheet 2\.|on the Answer Sheet\.)\s*", "", cloze, flags=re.I)
        blank = re.search(r"(?:\[\s*26\s*\]|_+\s*26\s*_+|\b26\b)", cloze)
        if not blank:
            raise ValueError("missing cloze blank 26")
        body = cloze.strip()
        bank_start = re.search(r"(?:Word\s*Bank\s*:?)?\s*\bA[)）]\s*[A-Za-z]", body)
        original = body[:bank_start.start()].strip() if bank_start else body
        # Tagged paragraphs preserve prose, while physical text retains tables,
        # labels and answer cells which some PDFs mark as decorative spans.
        raw = clean_furniture(auxiliary or full)
        raw_start = re.search(r"Reading\s+Comprehension", raw, re.I)
        raw_passage = re.search(r'Passage\s+One', raw, re.I)
        raw_reading = raw[raw_start.end():] if raw_start and (raw_passage is None or raw_start.start() < raw_passage.start()) else raw
        raw_section = re.search(r"Section\s*A", raw_reading, re.I)
        raw_end = re.search(r"Section\s*B", raw_reading, re.I)
        raw_cloze = raw_reading[raw_section.end():raw_end.start()] if raw_section and raw_end else body
        choices = dict(re.findall(r"\b([A-O])[)）]\s*([A-Za-z][A-Za-z’'-]*)", raw_cloze))
        if len(choices) != 15:
            raise ValueError("word bank does not contain all 15 labeled choices")
        _, answer_text = banks.split_answer_sheet(banks.clean(raw))
        if not answer_text:
            answer_text = raw[raw.lower().rfind("answer key"):] if "answer key" in raw.lower() else ""
        answer = banks.parse_answer_key(answer_text)
        if not all(number in answer for number in range(26, 36)):
            _, tagged_answers = banks.split_answer_sheet(banks.clean(full))
            answer.update(banks.parse_answer_key(tagged_answers))
        completed, fills = original, []
        for number in range(26, 36):
            letter = answer.get(number)
            word = choices.get(letter)
            if not word:
                raise ValueError("cloze answer not verified: " + str(number))
            completed, count = re.subn(rf"(?:\[\s*{number}\s*\]|_+\s*{number}\s*_+)", word, completed)
            if count != 1:
                raise ValueError("ambiguous/missing cloze location: " + str(number))
            fills.append(dict(number=number, letter=letter, word=word))
        results.append(("cloze", completed, original, fills))
    except ValueError as error:
        print("CLOZE", paper["id"], str(error), flush=True)
    for kind, marker, number in [("passage1", "One", 46), ("passage2", "Two", 51)]:
        match = re.search(r"Passage\s+" + marker, reading, re.I)
        if not match:
            raise ValueError("missing Passage " + marker)
        chunk = reading[match.end():]
        stop = re.search(r"(?:^|\n|\s)" + str(number) + r"[.．、]\s", chunk)
        if not stop:
            physical = re.search(r"Passage\s+" + marker, raw_reading, re.I)
            physical_chunk = raw_reading[physical.end():] if physical else ""
            boundary = re.search(r"(?:^|\n|\s)" + str(number) + r"[.．、]\s", physical_chunk)
            if not boundary:
                raise ValueError("missing question boundary " + str(number))
            body = layout_paragraphs(physical_chunk[:boundary.start()])
        else:
            body = chunk[:stop.start()].strip()
        body = re.sub(r"^Questions\s+\d+\s+to\s+\d+.*?following passage[.:]\s*", "", body,
                      flags=re.I | re.S)
        results.append((kind, body, body, []))
    articles = []
    for kind, body, original, fills in results:
        paragraphs = [normalized(value) for value in body.split("\n\n") if normalized(value)]
        text = "\n\n".join(paragraphs)
        words = re.findall(r"[A-Za-z]+(?:['’][A-Za-z]+)*", text)
        if not 110 <= len(words) <= 1000 or re.search(
                r"Directions:|Questions \d+ to|Part [IVX]+|答案速查", text, re.I):
            raise ValueError("contaminated or incomplete article " + kind)
        if "\ufffd" in text or re.search(r"\[\s*(?:2[6-9]|3[0-5])\s*\]", text):
            raise ValueError("unresolved text " + kind)
        articles.append({**paper, "id": paper["id"] + "-" + kind, "kind": kind,
                         "paragraphs": paragraphs, "original": original, "fills": fills,
                         "wordCount": len(words), "textSha256": digest(text)})
    if len({article["textSha256"] for article in articles}) != len(articles):
        raise ValueError("duplicate passages")
    return articles


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    commit = subprocess.check_output(["git", "--git-dir=" + str(REPO), "rev-parse", "HEAD"]).decode().strip()
    paths = subprocess.check_output(["git", "-c", "core.quotePath=false", "--git-dir=" + str(REPO),
                                     "ls-tree", "-r", "--name-only", commit]).decode("utf8").splitlines()
    selected = {}
    for path in paths:
        meta = paper_identity(path)
        if meta:
            if meta["id"] in selected:
                # The repository retains both scanned originals and searchable
                # cetN_YYYY_MM_N editions. Prefer that explicit text edition.
                if not Path(path).name.startswith("cet"):
                    continue
            selected[meta["id"]] = meta, path
    articles, errors, papers = [], [], []
    for identifier, (meta, path) in sorted(selected.items(), reverse=True):
        url = "https://raw.githubusercontent.com/0609x/CET46-Resources/" + commit + "/" + urllib.parse.quote(path)
        pdf = OUT / "pdfs" / (identifier + ".pdf")
        pdf.parent.mkdir(exist_ok=True)
        try:
            if not pdf.exists():
                existing = (ROOT / "work/intensive-input/pdfs") / Path(path).name
                if existing.exists():
                    pdf.write_bytes(existing.read_bytes())
                else:
                    for attempt in range(5):
                        try:
                            request = urllib.request.Request(url, headers={"User-Agent": "Tonglu-Reading-Source/1.0"})
                            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=45) as response:
                                data = response.read(32 * 1024 * 1024 + 1)
                            if data.startswith(b"version https://git-lfs.github.com"):
                                media = url.replace("raw.githubusercontent.com/", "media.githubusercontent.com/media/")
                                with urllib.request.urlopen(media, timeout=60) as response:
                                    data = response.read(32 * 1024 * 1024 + 1)
                            if not data.startswith(b"%PDF-") or len(data) > 32 * 1024 * 1024:
                                raise ValueError("invalid PDF")
                            pdf.write_bytes(data)
                            break
                        except Exception:
                            if attempt == 4:
                                raise
                            time.sleep(1 + attempt)
            full, method = pdf_blocks(pdf)
            from pypdf import PdfReader
            auxiliary = "\n".join(page.extract_text(extraction_mode="layout") or "" for page in PdfReader(pdf).pages)
            (OUT / "texts").mkdir(exist_ok=True)
            (OUT / "texts" / (identifier + ".txt")).write_text(full, encoding="utf8")
            source = dict(**meta, source=f"0609x/CET46-Resources@{commit}", sourceUrl=url,
                          sourceSha256=digest(pdf.read_bytes()), license=SOURCE_LICENSE,
                          licenseUrl=SOURCE + "/blob/" + commit + "/RIGHTS.md", paragraphMethod=method)
            rows = extract_articles(full, source, auxiliary)
            articles.extend(rows)
            papers.append(source)
            print(identifier, len(rows), "passages", method, flush=True)
        except Exception as error:
            errors.append(dict(paper=identifier, sourceUrl=url, error=str(error)))
            print("ERROR", identifier, str(error), flush=True)
    write(OUT / "corpus.json", dict(schemaVersion=1, revision="cet-intensive-20261024-v1",
                                   papers=papers, articles=articles, errors=errors,
                                   requestedYears=list(range(2021, 2027))))
    print("Prepared", len(papers), "papers", len(articles), "articles;", len(errors), "failed", flush=True)
    return 0 if articles else 1


def analyze():
    import ctypes
    sys.path.insert(0, str(OUT / "python"))
    kernel = ctypes.WinDLL("kernel32")
    kernel.GetCurrentProcess.restype = ctypes.c_void_p
    kernel.SetProcessAffinityMask.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    kernel.SetPriorityClass.argtypes = [ctypes.c_void_p, ctypes.c_uint32]
    kernel.SetProcessAffinityMask(kernel.GetCurrentProcess(), 0xC000)
    kernel.SetPriorityClass(kernel.GetCurrentProcess(), 0x4000)
    import torch
    import stanza
    from transformers import MarianMTModel, MarianTokenizer
    assert torch.cuda.is_available(), "CUDA required; CPU fallback refused"
    torch.set_num_threads(2)
    processors = dict(tokenize="ewt", mwt="ewt", pos="ewt_nocharlm",
                      lemma="ewt_nocharlm", depparse="ewt_nocharlm")
    nlp = stanza.Pipeline("en", dir=str(OUT / "models/stanza"), package=None,
                          processors=processors, use_gpu=True, device="cuda",
                          download_method=None, verbose=False)
    grammar_device = str(next(nlp.processors["depparse"].trainer.model.parameters()).device)
    assert grammar_device.startswith("cuda")
    folder = OUT / "models/opus-mt-en-zh"
    tokenizer = MarianTokenizer.from_pretrained(str(folder), local_files_only=True)
    model = MarianMTModel.from_pretrained(str(folder), local_files_only=True).half().to("cuda").eval()
    corpus = json.loads((OUT / "corpus.json").read_text("utf8"))
    success, errors, started = [], [], time.monotonic()
    model_files = []
    for base in [OUT / "models/stanza", folder]:
        for path in sorted(base.rglob("*")):
            if path.is_file() and ".cache" not in path.parts:
                model_files.append(dict(path=str(path.relative_to(OUT / "models")),
                                        sha256=digest(path.read_bytes()), bytes=path.stat().st_size))
    fingerprint = digest(json.dumps(model_files, sort_keys=True))
    for article in corpus["articles"]:
        target = OUT / "analyzed" / (article["id"] + ".json")
        try:
            if target.exists():
                old = json.loads(target.read_text("utf8"))
                if (old.get("textSha256") == article["textSha256"] and
                    old.get("sourceSha256") == article["sourceSha256"] and
                    old.get("modelFingerprint") == fingerprint and old.get("complete") is True):
                    success.append(old)
                    print(article["id"], "reused", flush=True)
                    continue
            paragraphs, all_sentences = [], []
            for paragraph_number, paragraph in enumerate(article["paragraphs"], 1):
                parsed, sentences = nlp(paragraph), []
                for sentence_number, sentence in enumerate(parsed.sentences, 1):
                    first, last = sentence.tokens[0].start_char, sentence.tokens[-1].end_char
                    text, tokens = paragraph[first:last], []
                    ranges = aligned_words(sentence)
                    for word in sentence.words:
                        start, end = ranges[word.id]
                        start, end = start - first, end - first
                        if not 0 <= start < end <= len(text):
                            raise ValueError("token outside sentence")
                        relation = word.deprel.split(":")[0]
                        role = ROLES.get(relation, "其他")
                        if relation == "root" and word.upos not in ["VERB", "AUX"]:
                            role = "表语" if any(w.deprel == "cop" for w in sentence.words) else "中心词"
                        tokens.append(dict(id=word.id, start=utf16(text[:start]), end=utf16(text[:end]),
                                           text=text[start:end], form=word.text, lemma=word.lemma or word.text,
                                           pos=word.upos, posLabel=POS.get(word.upos, "其他"),
                                           head=word.head, relation=word.deprel, role=role))
                    row = dict(id=f"p{paragraph_number}-s{sentence_number}", text=text,
                               sha256=digest(text), tokens=tokens, translation="")
                    sentences.append(row)
                    all_sentences.append(row)
                paragraphs.append(dict(id=paragraph_number, text=paragraph, sentences=sentences, translation=""))
            for offset in range(0, len(all_sentences), 8):
                rows = all_sentences[offset:offset + 8]
                encoded = tokenizer([">>cmn_Hans<< " + row["text"] for row in rows],
                                    padding=True, return_tensors="pt", truncation=False)
                if encoded["input_ids"].shape[1] > 512:
                    raise ValueError("sentence exceeds translation context; no silent truncation")
                with torch.inference_mode():
                    generated = model.generate(**encoded.to("cuda"), num_beams=4, max_new_tokens=384)
                translated = tokenizer.batch_decode(generated, skip_special_tokens=True)
                for row, translation in zip(rows, translated):
                    if not re.search(r"[\u3400-\u9fff]", translation):
                        raise ValueError("translation missing Chinese: " + repr(row['text'][:120]) + ' -> ' + repr(translation[:120]))
                    row["translation"] = translation
            for paragraph in paragraphs:
                paragraph["translation"] = "".join(row["translation"] for row in paragraph["sentences"])
            result = {**article, "paragraphs": paragraphs,
                      "translation": "\n\n".join(p["translation"] for p in paragraphs),
                      "complete": True, "modelFingerprint": fingerprint,
                      "grammarSource": "https://stanfordnlp.github.io/stanza/",
                      "grammarModel": "Stanza 1.8.2 en-ewt_nocharlm",
                      "grammarLicense": "Apache-2.0; EWT model/data terms apply",
                      "translationSource": "https://huggingface.co/Helsinki-NLP/opus-mt-en-zh",
                      "translationLicense": "Apache-2.0", "grammarDevice": grammar_device,
                      "translationDevice": str(next(model.parameters()).device),
                      "analysisNotice": "机器辅助翻译与句法分析，非官方解析；结合原文核对。" + article.get('qualityNotice', '')}
            write(target, result)
            success.append(result)
            print(article["id"], "analyzed", len(all_sentences), "sentences; seconds",
                  round(time.monotonic() - started, 1), flush=True)
        except Exception as error:
            errors.append(dict(id=article["id"], error=str(error)))
            print("ERROR", article["id"], str(error), flush=True)
    write(OUT / "analysis-result.json", dict(articles=len(success), failed=errors,
          device=torch.cuda.get_device_name(0), modelFingerprint=fingerprint, modelFiles=model_files,
          cudaPeakBytes=torch.cuda.max_memory_allocated(), seconds=time.monotonic() - started))
    return 0 if not errors else 1


def supplement():
    """Use pinned structured 2023 transcriptions; never infer missing answers."""
    repo = (ROOT / 'work/intensive-input/english-exem-md.git')
    commit = subprocess.check_output(['git', '--git-dir=' + str(repo), 'rev-parse', 'HEAD']).decode().strip()
    paths = subprocess.check_output(['git', '--git-dir=' + str(repo), 'ls-tree', '-r', '--name-only', commit]).decode().splitlines()
    corpus = json.loads((OUT / 'corpus.json').read_text('utf8'))
    existing = {row['id']: row for row in corpus['articles']}
    for path in paths:
        match = re.search(r'(cet[46])-(2023)-(06|12)-([123])\.md$', path)
        if not match:
            continue
        data = subprocess.check_output(['git', '--git-dir=' + str(repo), 'show', commit + ':' + path])
        text = data.decode('utf8')
        text = re.sub(r'<u>&emsp;(\d+)&emsp;</u>', r'[\1]', text)
        text = re.sub(r'</?[^>]+>', '', text)
        text = re.sub(r'(?m)^#+\s*', '', text).replace('**', '').replace('_', '')
        paper = dict(id=match[0][:-3], exam=match[1].upper(), year=2023, month=int(match[3]), suite=int(match[4]),
                     source='wamich/english-exem-md@' + commit,
                     sourceUrl='https://raw.githubusercontent.com/wamich/english-exem-md/' + commit + '/' + path,
                     sourceSha256=digest(data), license=SOURCE_LICENSE,
                     licenseUrl='https://github.com/wamich/english-exem-md/blob/' + commit + '/LICENSE',
                     paragraphMethod='source-markdown-paragraphs')
        try:
            for row in extract_articles(text, paper):
                existing[row['id']] = row
        except ValueError as error:
            print('SUPPLEMENT ERROR', paper['id'], str(error), flush=True)
    corpus['articles'] = sorted(existing.values(), key=lambda row: row['id'])
    write(OUT / 'corpus.json', corpus)
    print('Supplemented total', len(existing), flush=True)


def clean_corpus():
    corpus = json.loads((OUT / 'corpus.json').read_text('utf8'))
    changed = []
    for row in corpus['articles']:
        paragraphs = [normalized(clean_furniture(p)) for p in row['paragraphs']]
        paragraphs = [p for p in paragraphs if p]
        text = '\n\n'.join(paragraphs)
        if digest(text) != row['textSha256']:
            changed.append(row['id'])
            row.update(paragraphs=paragraphs, textSha256=digest(text), original=clean_furniture(row['original']),
                       wordCount=len(re.findall(r"[A-Za-z]+(?:['’][A-Za-z]+)*", text)))
    write(OUT / 'corpus.json', corpus)
    print('Cleaned running furniture', changed, flush=True)


def reparagraph(paper_filter=None):
    from pypdf import PdfReader
    corpus = json.loads((OUT / 'corpus.json').read_text('utf8'))
    entries = {row['id']:row for row in corpus['articles']}
    papers = {}
    for row in entries.values():
        if row['source'].startswith('0609x/'):
            papers[row['id'].rsplit('-',1)[0]] = row
    for identifier,row in papers.items():
        if paper_filter and not identifier.startswith(paper_filter): continue
        path = OUT/'pdfs'/(identifier+'.pdf')
        source = {key:row[key] for key in ('exam','year','month','suite','source','sourceUrl','sourceSha256','license','licenseUrl')}
        try:
            full,method = pdf_blocks(path)
            raw = '\n'.join(page.extract_text(extraction_mode='layout') or '' for page in PdfReader(path).pages)
            source.update(id=identifier,paragraphMethod=method)
            rows = extract_articles(full,source,raw)
            for value in rows: entries[value['id']]=value
            print('Reflow',identifier,[(r['kind'],len(r['paragraphs'])) for r in rows],flush=True)
        except ValueError as error:
            print('REFLOW ERROR',identifier,str(error),flush=True)
    corpus['articles']=sorted(entries.values(),key=lambda row:row['id'])
    write(OUT/'corpus.json',corpus)


def bundle():
    corpus = json.loads((OUT / "corpus.json").read_text("utf8"))
    result = json.loads((OUT / "analysis-result.json").read_text("utf8"))
    assets = ROOT / "apps/android/app/src/main/assets/intensive"
    catalog = []
    for row in corpus["articles"]:
        path = OUT / "analyzed" / (row["id"] + ".json")
        if not path.exists():
            continue
        value = json.loads(path.read_text("utf8"))
        if not value.get("complete") or value.get("modelFingerprint") != result["modelFingerprint"]:
            raise ValueError("incomplete/stale analysis")
        if value.get('textSha256') != row['textSha256'] or value.get('sourceSha256') != row['sourceSha256']:
            raise ValueError('source/text changed after analysis: ' + row['id'])
        if not value["grammarDevice"].startswith("cuda"):
            raise ValueError("GPU grammar evidence missing")
        data = path.read_bytes()
        target = assets / "articles" / (row["id"] + ".json.gz")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(gzip.compress(data, mtime=0))
        catalog.append({**{key: value[key] for key in [
            "id", "exam", "year", "month", "suite", "kind", "wordCount", "source", "sourceUrl",
            "sourceSha256", "license", "licenseUrl", "textSha256", "modelFingerprint"]},
            "sha256": digest(data), "bytes": len(data), "qualityNotice": value.get('qualityNotice', ''),
            "sentences": sum(len(p["sentences"]) for p in value["paragraphs"])})
    if not catalog:
        raise ValueError("no complete articles")
    write(assets / "catalog.json", dict(schemaVersion=1, revision=corpus["revision"],
          articles=catalog, analysisNotice="机器辅助分析；非官方解析",
          modelFingerprint=result["modelFingerprint"], requestedYears=corpus["requestedYears"],
          coverageNotice="只收录通过正文、答案与分析校验的材料，未收录套卷明确展示缺失。"))
    write(ROOT / "content/intensive/provenance.json", dict(source=SOURCE, license=SOURCE_LICENSE,
          sourceUrl=SOURCE, articles=catalog, grammarSource="https://huggingface.co/stanfordnlp/stanza-en",
          grammarLicense="Apache-2.0",
          grammarTrainingDataSource="https://github.com/UniversalDependencies/UD_English-EWT",
          grammarTrainingDataLicense="CC-BY-SA-4.0 annotations; underlying texts retain original rights",
          translationSource="https://huggingface.co/Helsinki-NLP/opus-mt-en-zh",
          translationLicense="Apache-2.0", modelFingerprint=result["modelFingerprint"],
          modelFiles=result["modelFiles"]))
    print("Bundled", len(catalog), "complete articles", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "supplement", "clean", "reparagraph", "analyze", "bundle"])
    parser.add_argument('--paper', help='Optional paper id prefix for reparagraph')
    arguments = parser.parse_args()
    if arguments.command == 'reparagraph':
        raise SystemExit(reparagraph(arguments.paper))
    raise SystemExit({"prepare": prepare, "supplement": supplement, "clean": clean_corpus, "reparagraph": reparagraph, "analyze": analyze, "bundle": bundle}[arguments.command]())
