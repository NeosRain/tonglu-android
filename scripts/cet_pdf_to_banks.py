# -*- coding: utf-8 -*-
"""0609x 版式的四六级真题 PDF → **按题型拆题库**（每种题型一个题库）。

跟 `pdf_to_question_banks.py` 的分工
----------------------------------
那份是按「题号行 + 缩进选项」的**通用**版式写的，靠题号区间判题型。
但 0609x 仓库里的真题是另一种排法，通用版式在这里会切错（实测：听力切成
40 题（应 25）、选词填空只认出 2 题（应 10））：

| 题型 | 这份卷子的实际写法 |
|---|---|
| 听力 1–25 | 题号**独占一行** `1.`，选项缩进 `A) …` |
| 选词填空 26–35 | 空是**行内** `[26]`，词库是 `A) anywhere  B) asserts …` 的块 |
| 段落匹配 36–45 | 题号行 `36.`，段落字母在**正文行首** `A) For those …` |
| 仔细阅读 46–55 | 题号与题干**同一行** `46. What does…` |

所以本脚本按这套版式单独解析，**判分与产出格式完全对齐** `docs/plan/57` 与 plan/64：
- `answer = -1` 表示「不可判分」，**绝不编答案**；
- 选项顺序保持卷面顺序（A/B/C/D…），答案按**字母**映射到下标；
- 答案字母不在识别出的选项里 → **并入选项并明确告警**（不静默丢判分）。

用法
----
    python scripts/cet_pdf_to_banks.py --pdf <卷.pdf> --answers <解析.pdf> \\
        --exam cet6 --id-prefix cet6-2026-06-1 --label "2026年6月六级真题（第1套）" \\
        --source "0609x/CET46-Resources@<sha>" --out work/tmp/cet-banks
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

# 题号区间（CET 四/六级一致；见 plan/64 那张表）
LISTENING = (1, 25)
CLOZE = (26, 35)
MATCHING = (36, 45)
READING = (46, 55)

TYPE_SLUG = {
    "listening": "listening", "cloze": "cloze", "matching": "matching",
    "reading": "reading", "writing": "writing", "translation": "translation",
}
TYPE_CN = {
    "listening": "听力", "cloze": "选词填空", "matching": "段落匹配",
    "reading": "仔细阅读", "writing": "写作", "translation": "翻译",
}
ORDER = ["listening", "cloze", "matching", "reading", "writing", "translation"]

# 段落匹配的段落字母行：`A) For those "citizens of the world", ...`
PARA_LINE_RE = re.compile(r"^([A-O])\)\s+(\S.*)$")
# 题号行：`1.` 或 `46. What does the author believe ...`
QNUM_RE = re.compile(r"^\s{0,6}(\d{1,2})\.\s*(.*)$")
# 段落匹配题干是**列表序号 + 题号**：` 1. 36. Pieter Levels created ...`
QNUM_LIST_RE = re.compile(r"^\s{0,6}\d{1,2}\.\s+(\d{1,2})\.\s*(.*)$")
# 卷末自带的答案速查表（会把答案当成题干，必须整段截掉）。
# 大小写都要认：四级卷子写的是**大写** `KEYS`。
ANSWER_SHEET_RE = re.compile(r"^\s*(答案速查|参考答案|答案速查表|Key|Keys)\b", re.I)
# 选项行：`    A) How to run one's business successfully.`
OPT_RE = re.compile(r"^\s{0,10}([A-D])\)\s*(.*)$")
# 行内空：`preventing landlords ... [27] ...`
BLANK_RE = re.compile(r"\[(\d{1,2})\]")


# ---------------------------------------------------------------- 纯函数：抽文

def run_pdftotext(pdf: str, layout: bool = True) -> str:
    exe = shutil.which("pdftotext")
    if not exe:
        raise SystemExit("找不到 pdftotext（poppler / MiKTeX 自带）")
    cmd = [exe, "-layout", pdf, "-"] if layout else [exe, pdf, "-"]
    r = subprocess.run(cmd, capture_output=True, timeout=300)
    if r.returncode != 0:
        raise SystemExit("pdftotext 失败：" + r.stderr.decode("utf-8", "replace")[:300])
    return r.stdout.decode("utf-8", "replace")


def clean(text: str) -> str:
    """页眉页脚/空行归一。保留行结构（本脚本靠行首判题号/选项）。"""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    out = []
    for line in text.split("\n"):
        # \x0c 是 pdftotext 的分页符：它贴在行首会让 `J) But these …` 认不出段落字母
        line = line.replace("\x0c", "").replace("\u00a0", " ").rstrip()
        line = re.sub(r"[ \t]+", " ", line)
        out.append(line)
    return "\n".join(out)


# ---------------------------------------------------------------- 纯函数：答案

def split_answer_sheet(text: str) -> tuple[str, str]:
    """切开卷末自带的「答案速查 / KEYS」段，返回 (正文, 答案段)。

    实测：0609x 的真题 PDF **末尾自带答案速查表**（` 38. F 39. K 40. B …`
    或四级那种「一行题号 + 一行字母」）。不切开的话这些答案会被当成题干，
    听力会凭空多出 15 题；而四级仓库里的「答案解析」PDF 是**扫描件抽不出字**，
    答案只能从这段来。
    """
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if ANSWER_SHEET_RE.match(line):
            return "\n".join(lines[:i]), "\n".join(lines[i:])
    return text, ""


def parse_answer_key(text: str) -> dict[int, str]:
    """解析答案。实测这个仓库的解析 PDF 里答案是**多列表格**（一行好几组）：

        1      B    2     C       3    A        4        D   5    A …

    也有逐条写法 `36. D — Pieter Levels …`。两种都要认，且**先出现的为准**。
    只认 1–55 的题号 + 单个 A–O 字母，避免把正文里的 "9% rise" 之类当答案。
    """
    pair_re = re.compile(r"(?<![\d.])(\d{1,2})[.、]\s*([A-O])(?![a-zA-Z])")
    table_re = re.compile(r"(?<![\d.])(\d{1,2})\s+([A-O])(?![a-zA-Z])")
    key: dict[int, str] = {}
    lines = text.split("\n")
    for i, line in enumerate(lines):
        # ③ 卷尾 KEYS 表：**一行题号 + 一行字母**（四级卷子就是这种）
        #    1  2  3  4 …   ← 数字行
        #    A  B  A  D …   ← 字母行
        if _looks_like_number_row(line):
            nums = [int(x) for x in line.split()]
            for nxt in lines[i + 1:i + 3]:
                if _looks_like_letter_row(nxt) and len(nxt.split()) == len(nums):
                    for n, ch in zip(nums, nxt.split()):
                        if 1 <= n <= 55:
                            key.setdefault(n, ch)
                    break
        hits = pair_re.findall(line) or table_re.findall(line)
        for num, letter in hits:
            n = int(num)
            if 1 <= n <= 55:
                key.setdefault(n, letter)
    return key


def parse_explanations(text: str) -> dict[int, str]:
    """抽逐条解析。解析 PDF 里的写法是 `26. impose (J) — 句意：“提案对租金上涨施加限制”…`。

    后端**要求每题必须有解析**（没有就 400），而扫描件抽不出字 → 抽不到时调用方
    会退回写「答案：X（原卷答案）」这种**如实**的说明，绝不编解析。
    """
    out: dict[int, str] = {}
    cur: int | None = None
    buf: list[str] = []
    for line in text.split("\n"):
        m = re.match(r"^\s*(\d{1,2})[.、]\s+(\S.*)$", line)
        if m:
            if cur is not None and buf:
                out.setdefault(cur, " ".join(buf).strip())
            cur, buf = int(m.group(1)), [m.group(2).strip()]
            continue
        if cur is not None:
            if line.strip() and not re.match(r"^\s*(Part|Section)\b", line):
                buf.append(line.strip())
            elif buf:
                out.setdefault(cur, " ".join(buf).strip())
                cur, buf = None, []
    if cur is not None and buf:
        out.setdefault(cur, " ".join(buf).strip())
    return {n: t for n, t in out.items() if 1 <= n <= 55 and len(t) >= 8}


def _looks_like_number_row(line: str) -> bool:
    toks = line.split()
    return len(toks) >= 4 and all(t.isdigit() and 1 <= int(t) <= 55 for t in toks)


def _looks_like_letter_row(line: str) -> bool:
    toks = line.split()
    return len(toks) >= 4 and all(len(t) == 1 and "A" <= t <= "O" for t in toks)


# ---------------------------------------------------------------- 纯函数：题型

OPTION_SPLIT_RE = re.compile(r"(?:^|\s{2,})([A-D])\)\s*")


def split_inline_options(line: str) -> list[tuple[str, str]]:
    """一行里挤着多个选项时按字母切开。

    四级卷子把听力选项排成**两列**：
        `7. A) Recognizing the impact on consumers.      C) Abandoning the …`
        `   B) Identifying the source of the problem.    D) Establishing …`
    六级是一行一个。两种都要认，所以按 `A)`/`B)` 出现的位置切段。
    """
    marks: list[tuple[str, int, int]] = []  # (字母, 正文起点, 本段起点)
    for m in re.finditer(r"([A-D])\)(?=\s|$)", line):
        prev_end = marks[-1][1] if marks else 0
        gap = line[prev_end:m.start()]
        core = gap.strip()
        # 行首 / 题号前缀（`7. `）后面那个选项要认；行中出现的必须隔了两个以上空格
        # （正文里偶尔有 "…in Section A)" 这种，不能当成选项）
        if prev_end == 0:
            ok = core == "" or re.fullmatch(r"\d{1,2}\.", core) or len(gap) >= 2
        else:
            ok = len(gap) >= 2
        if ok:
            marks.append((m.group(1), m.end(), m.start()))
    out = []
    for i, (letter, body_start, _) in enumerate(marks):
        end = marks[i + 1][2] if i + 1 < len(marks) else len(line)
        out.append((letter, line[body_start:end].strip()))
    return [(l, b) for l, b in out if b]


def parse_numbered_questions(text: str, lo: int, hi: int) -> list[dict]:
    """抽题号在 [lo,hi] 的题：题干 + A/B/C/D 选项。

    题号可能独占一行（`1.`），也可能与题干同行（`46. What does ...`），
    还可能题干与选项同行、且一行挤着两个选项（四级卷子）。
    遇到下一个同区间题号或新 Section/Part 就停（否则题干会吃到下一节的说明）。
    """
    lines = text.split("\n")
    items: list[dict] = []
    cur: dict | None = None
    last_opt = -1
    for line in lines:
        # 段落匹配题干是「列表序号 + 题号」：` 1. 36. Pieter Levels created ...`
        m_list = QNUM_LIST_RE.match(line)
        m_num = QNUM_RE.match(line) if not m_list else None
        if m_list:
            num = int(m_list.group(1))
            stem = m_list.group(2).strip()
            if lo <= num <= hi:
                cur = {"num": num, "stem": stem, "options": []}
                items.append(cur)
                last_opt = -1
                continue
        elif m_num:
            num = int(m_num.group(1))
            if lo <= num <= hi:
                rest = m_num.group(2).strip()
                # 🔴 题干与选项**同行**的情况（四级卷子：`1. A) …   C) …`）：
                # 不拆的话 A)/C) 会被整段吞进题干，只剩 B)/D) 两个选项。
                inline = split_inline_options(rest)
                if inline:
                    first = re.search(r"(?:^|\s)([A-D])\)", rest)
                    stem = rest[:first.start()].strip() if first else ""
                    cur = {"num": num, "stem": stem, "options": []}
                    items.append(cur)
                    for letter, body in inline:
                        idx = ord(letter) - ord("A")
                        while len(cur["options"]) <= idx:
                            cur["options"].append("")
                        cur["options"][idx] = body
                    last_opt = max((ord(l) - ord("A")) for l, _ in inline)
                else:
                    cur = {"num": num, "stem": rest, "options": []}
                    items.append(cur)
                    last_opt = -1
                continue
            if cur is not None and num > hi:
                cur = None  # 进入下一区间，收手
                continue
        if re.match(r"^\s*(Part\s+[IVX]+|Section\s+[A-C])\b", line):
            if cur is not None and cur["options"]:
                cur = None
            continue
        if cur is None:
            continue
        inline = split_inline_options(line)
        if inline:
            for letter, body in inline:
                idx = ord(letter) - ord("A")
                while len(cur["options"]) <= idx:
                    cur["options"].append("")
                cur["options"][idx] = (cur["options"][idx] + " " + body).strip()
                last_opt = idx
            continue
        m_opt = OPT_RE.match(line)
        if m_opt:
            letter = m_opt.group(1)
            idx = ord(letter) - ord("A")
            body = m_opt.group(2).strip()
            while len(cur["options"]) <= idx:
                cur["options"].append("")
            cur["options"][idx] = (cur["options"][idx] + " " + body).strip()
            last_opt = idx
            continue
        # 续行：归到最近开始的那个字段（选项优先，否则题干）
        if not line.strip():
            continue
        if last_opt >= 0 and cur["options"]:
            cur["options"][last_opt] = (cur["options"][last_opt] + " " + line.strip()).strip()
        elif cur["stem"]:
            cur["stem"] = (cur["stem"] + " " + line.strip()).strip()
    out = []
    for it in items:
        opts = [o for o in it["options"] if o]
        if not opts and not it["stem"]:
            continue
        out.append({"num": it["num"], "stem": it["stem"], "options": opts})
    return out


def parse_word_bank(text: str) -> list[str]:
    """词库块。这个仓库里有三种排法：

    - `A) anywhere   B) asserts   C) constituent   D) correlated`（一行四个）
    - ` A) accepted          I) samples`（一行两个，分两列）
    - `Word Bank: A) adolescents B) arbitrarily … F)` + 下一行 `disregard G) drop …`
      （**带 Word Bank: 头且跨行** → 按行切会丢掉 F 并让后面全部串位）

    判据是「字母后面跟的是**一个单词**」（段落那边 `A) For those …` 跟的是整句）。
    """
    # ① 有 Word Bank: 头 → 先把这一段拼成一行再切（跨行才不出错）
    m = re.search(r"Word Bank:?\s*", text)
    if m:
        chunk = text[m.end():m.end() + 800]
        chunk = re.split(r"\n\s*\n|\n\s*(?:Section|Part|Directions)\b", chunk)[0]
        flat = re.sub(r"\s+", " ", chunk)
        pairs = re.findall(r"(?:^|\s)([A-O])\)\s*([a-z][\w'’\-]*)", flat)
        words = {}
        for letter, word in pairs:
            words.setdefault(letter, word)
        if len(words) >= 10:
            return [words[chr(ord("A") + i)] for i in range(15) if chr(ord("A") + i) in words]

    # ② 没有头 → 按行解析（一行多个，每个单词后面紧跟下一个字母或行尾）
    words: dict[str, str] = {}
    for line in text.split("\n"):
        # ⚠️ 不能拿「多个空格」当判据：clean() 已把连续空格折叠成一个了（实测踩过）。
        for mm in re.finditer(r"(?:^|\s)([A-O])\)\s+([a-z][\w'’\-]*)(?=\s+[A-O]\)|$)", line):
            words.setdefault(mm.group(1), mm.group(2))
    if len(words) < 10:
        return []
    return [words[chr(ord("A") + i)] for i in range(15) if chr(ord("A") + i) in words]


def parse_cloze_blanks(text: str) -> dict[int, str]:
    """行内空。三种写法：`[26]`（六级）、`___26___`（四级）、**裸数字** `early 27,`。

    裸数字最危险（正文里到处是数字）→ 只在 **Section A 这一段的区间里**找，
    并且**必须 26–35 十个数字全出现**才认（凑不齐就当没找到，不猜）。
    """
    stems: dict[int, str] = {}
    patterns = [BLANK_RE, re.compile(r"_{2,}\s*(\d{1,2})\s*_{2,}")]

    def collect(line: str) -> None:
        nums: list[str] = []
        for p in patterns:
            nums += p.findall(line)
        for n in nums:
            num = int(n)
            if CLOZE[0] <= num <= CLOZE[1]:
                stem = line
                for p in patterns:
                    stem = p.sub("______", stem)
                stems.setdefault(num, stem.strip())

    for line in text.split("\n"):
        collect(line)

    # 裸数字：先切出「选词填空」那一段。
    # ⚠️ 锚点必须是 `Banked Cloze`，**不能只写 `Section A`** —— 听力那边也有
    # Section A（Long Conversations），只写 Section A 会切到听力段里（实测踩过）。
    m = (re.search(r"(?:Banked\s+Cloze|选词填空)(.*?)(?=\n\s*Section\s*B\b)", text, re.S | re.I)
         or re.search(r"Section\s*A\b(.*?)(?=\n\s*Section\s*B\b)", text, re.S))
    if m and len(stems) < 10:
        region = m.group(1)
        found: dict[int, str] = {}
        for line in region.split("\n"):
            # 边界只排除**字母/数字/百分号**，以及「点后跟数字」（那是小数，如 26.5）。
            # ⚠️ 不能把 `.` 一并排掉：卷面上有 `not 31. These` 这种「句号紧跟空号」的写法
            # （实测踩过，会整题找不到）。
            # 也不能把 `_` 当词字符：`___26___` 那种写法前面就是下划线。
            for tok in re.finditer(r"(?<![A-Za-z0-9.])(2[6-9]|3[0-5])(?![A-Za-z0-9%])(?!\.\d)", line):
                num = int(tok.group(1))
                if num not in found:
                    found[num] = re.sub(r"(?<![A-Za-z0-9.])%d(?![A-Za-z0-9%%])(?!\.\d)" % num,
                                        "______", line).strip()
        # 十个全齐最理想；8–9 个也认（**降级不改口径**：缺的那几个不造题，
        # 并明确告警是哪些 —— 不静默按 10 题报）。
        if len(found) >= 8:
            if len(found) < 10:
                miss = [n for n in range(CLOZE[0], CLOZE[1] + 1) if n not in found]
                print("  [警告] 选词填空：只在卷面认出 %d/10 个空，缺 %s（缺的题不生成）"
                      % (len(found), "、".join(map(str, miss))))
            for num, line in found.items():
                stems.setdefault(num, line)
    return stems


def parse_paragraph_options(text: str) -> list[str]:
    """段落匹配：正文行首 `A) For those ...` → 选项 = 该段**完整文字**。

    🔴 2026-10-15 修（BUGS 见 docs/logs/2026-10-15.md）：旧实现把每段砍到 60 字符
    （`body[:60] + "…"`），生产 4 份 matching 题库 **500/500 个选项全部恰好 64 字符**，
    段落匹配因此退化成"一串省略号"——用户原话「段落匹配更是搞笑，题都没有，只要选项」。
    现在保留整段（1200 字符上限，防异常长行把题库撑爆），并把紧跟其后的**续行**接上：
    PDF 文本里段落常被折行，只取首行同样会截断。
    停止条件用"下一个段落标记 / 题号行 / 空行"，不靠猜长度。
    """
    opts: dict[str, list[str]] = {}
    current: str | None = None
    for line in text.split("\n"):
        m = PARA_LINE_RE.match(line)
        if m:
            current = m.group(1)
            opts[current] = [m.group(2).strip()]
            continue
        if current is not None:
            stripped = line.strip()
            if not stripped or QNUM_RE.match(line) or QNUM_LIST_RE.match(line):
                current = None
            else:
                opts[current].append(stripped)
    if len(opts) < 4:
        return []
    return ["%s) %s" % (l, " ".join(opts[l])[:1200]) for l in sorted(opts)]


def parse_subjective(text: str) -> dict[str, str]:
    """写作 / 翻译：这两个题型不编号，按 Part 标题切。

    ⚠️ 标题里的分隔符有版本差异：`Part I Writing` 与 `Part I · Writing` 都存在，
    所以中间的分隔符必须是**可选**的。
    """
    res: dict[str, str] = {}
    m = re.search(r"Part\s+I\s*[·•\-–—]?\s*Writing(.*?)(?=Part\s+II\b)", text, re.S | re.I)
    if m:
        body = m.group(1).strip()
        given = re.search(r'"([^"]{40,})"', body)
        if given:
            body = "以给定句子开篇：\n" + given.group(1)
        res["writing"] = re.sub(r"\n{3,}", "\n\n", body)[:1200]
    m = re.search(r"Part\s+IV\s*[·•\-–—]?\s*Translation(.*?)(?=Part\s+[IVX]+\s*[·•\-–—]?\s*(?!Translation)|$)",
                  text, re.S | re.I)
    if m:
        res["translation"] = re.sub(r"\n{3,}", "\n\n", m.group(1).strip())[:1200]
    return res


# ---------------------------------------------------------------- 纯函数：组库

def _letter_index(options: list[str], letter: str) -> int:
    """按选项前缀字母定位下标；选项没有字母前缀（词库）时按 A+i 顺序。"""
    for i, o in enumerate(options):
        if re.match(r"^%s\)" % letter, o):
            return i
    i = ord(letter) - ord("A")
    return i if 0 <= i < len(options) else -1


def build_banks(paper: str, key: dict[int, str], prefix: str, label: str,
                source_note: str, license_: str,
                explanations: dict[int, str] | None = None) -> tuple[list[dict], dict]:
    explanations = explanations or {}
    warns: list[str] = []
    banks: list[dict] = []
    stats: dict[str, dict] = {}

    def emit(tname: str, questions: list[dict]) -> None:
        if not questions:
            return
        gradable = sum(1 for q in questions if q["answer"] >= 0)
        banks.append({
            "id": "%s-%s" % (prefix, TYPE_SLUG[tname]),
            "title": "%s · %s" % (label, TYPE_CN[tname]),
            "subject": "english", "level": "",
            "sourceNote": source_note, "license": license_, "version": 1,
            "questions": questions,
        })
        stats[tname] = {"count": len(questions), "gradable": gradable,
                        "missing_answer": len(questions) - gradable}

    def make(tname: str, num: int, stem: str, options: list[str], ans_letter: str | None) -> dict:
        ans = -1
        if ans_letter:
            if ans_letter not in [chr(ord("A") + i) for i in range(len(options))]:
                # 🔴 不静默丢判分：把答案用到的字母并进选项，并告警
                options = options + ["%s) （原卷选项，抽取时未识别到）" % ans_letter]
                warns.append("%s 第 %d 题答案 %s 不在识别出的 %d 个选项里 → 已并入，请人工核对"
                             % (TYPE_CN[tname], num, ans_letter, len(options) - 1))
            ans = _letter_index(options, ans_letter)
        # 后端要求每题**必须有解析**；抽不到解析时写清「答案来自原卷答案速查」，
        # 绝不编一段看起来像解析的话。
        if ans >= 0:
            expl = explanations.get(num) or ("答案：%s（原卷答案速查）" % ans_letter)
        else:
            expl = "答案待补（原卷未给出/未识别）"
        # 听力题的**题干在录音里**，卷面只印选项 —— 不能显示成「题干未识别」
        # （那看着像我们解析失败，其实是卷子本来就没有）。
        prompt = stem or ("(听力第 %d 题：题干在录音中，卷面只印选项)" % num
                          if tname == "listening" else "(题干未识别)")
        return {
            "id": "q%02d" % num,
            "type": "single-choice" if len(options) >= 2 else "prompt",
            "prompt": prompt,
            "options": options,
            "answer": ans,
            "explanation": expl,
            "difficulty": "basic",
            "segmentId": "",
        }

    # 听力 / 段落匹配 / 仔细阅读：题号行
    for tname, (lo, hi) in (("listening", LISTENING), ("matching", MATCHING), ("reading", READING)):
        qs = []
        for it in parse_numbered_questions(paper, lo, hi):
            opts = it["options"]
            if tname == "matching" and len(opts) < 4:
                opts = parse_paragraph_options(paper)
            qs.append(make(tname, it["num"], it["stem"], opts, key.get(it["num"])))
        emit(tname, qs)

    # 选词填空：行内空 + 共享词库
    stems = parse_cloze_blanks(paper)
    bank_words = parse_word_bank(paper)
    if stems:
        qs = []
        for num in sorted(stems):
            qs.append(make("cloze", num, stems[num], list(bank_words), key.get(num)))
        if not bank_words:
            warns.append("选词填空：词库没认出来 → %d 题全部不可判分" % len(qs))
        emit("cloze", qs)

    # 写作 / 翻译
    for tname, body in parse_subjective(paper).items():
        emit(tname, [{
            "id": "q01", "type": "prompt", "prompt": body, "options": [], "answer": -1,
            "explanation": "主观题，按题库契约不可自动判分", "difficulty": "basic",
            "segmentId": "",
        }])

    total = sum(s["count"] for s in stats.values())
    gradable = sum(s["gradable"] for s in stats.values())
    return banks, {"types": stats, "total": total, "gradable": gradable, "warnings": warns}


def main() -> int:
    ap = argparse.ArgumentParser(description="0609x 版式真题 PDF → 按题型拆题库")
    ap.add_argument("--pdf", required=True)
    ap.add_argument("--answers", help="解析 PDF（同仓库的 _ans.pdf）；"
                                      "不给或抽不出字时，退回用卷末自带的答案速查段")
    ap.add_argument("--exam", default="cet4", choices=["cet4", "cet6"])
    ap.add_argument("--id-prefix", dest="prefix", required=True,
                    help="题库 id 前缀，规则 ^[a-z][a-z0-9-]{2,63}$")
    ap.add_argument("--label", required=True)
    ap.add_argument("--source", dest="source", default="", help="来源标注（仓库@SHA）")
    ap.add_argument("--out", default="work/tmp/cet-banks")
    args = ap.parse_args()

    if not re.match(r"^[a-z][a-z0-9-]{2,63}$", args.prefix):
        raise SystemExit("--id-prefix 必须匹配 ^[a-z][a-z0-9-]{2,63}$")

    raw = clean(run_pdftotext(args.pdf, layout=True))
    paper, sheet = split_answer_sheet(raw)
    if sheet:
        print("  [提示] 卷末自带答案速查表 → 已截掉（不截的话答案会被当成题干）")

    key: dict[int, str] = {}
    if args.answers:
        key = parse_answer_key(clean(run_pdftotext(args.answers, layout=True)))
        if not key:
            print("  [警告] 解析 PDF 一个答案都没抽出来（多半是扫描件）→ 改用卷末答案速查")
    sheet_key = parse_answer_key(sheet) if sheet else {}
    if sheet_key and key:
        # 两处都有答案 → 对一遍。**答案对不上就是这套卷子不能交付的硬信号**。
        diff = sorted(n for n in set(key) & set(sheet_key) if key[n] != sheet_key[n])
        if diff:
            print("  🔴 [警告] 解析 PDF 与卷末答案速查有 %d 题不一致：%s → 以解析 PDF 为准，请人工核对"
                  % (len(diff), ", ".join("%d:%s/%s" % (n, key[n], sheet_key[n]) for n in diff[:10])))
    if not key:
        key = sheet_key
        if key:
            print("  [提示] 答案来自卷末答案速查（%d 题）" % len(key))
    elif sheet_key:
        # 解析 PDF 只覆盖一部分时（实测：六级 2025.12 第1套 解析缺听力 16–25），
        # 用卷末速查**补齐缺的那几题** —— 补的是原卷答案，不是编的。
        filled = [n for n in sheet_key if n not in key]
        for n in filled:
            key[n] = sheet_key[n]
        if filled:
            print("  [提示] 解析 PDF 缺 %d 题，已用卷末答案速查补上：%s"
                  % (len(filled), "、".join(map(str, sorted(filled)[:12]))))
    if not key:
        print("答案一个都没解析出来 —— 宁可空手，也不生成一堆不可判分的题库。")
        return 2

    source_note = "真题整理件（来源：%s）。原卷著作权归命题机构，本平台仅作学习练习用；权利人如有异议立即下线。" \
        % (args.source or "未标注")
    expl_text = (clean(run_pdftotext(args.answers, layout=True)) if args.answers else "") + "\n" + sheet
    explanations = parse_explanations(expl_text)
    banks, stats = build_banks(paper, key, args.prefix, args.label, source_note,
                               license_="真题（第三方整理，非平台原创）",
                               explanations=explanations)

    os.makedirs(args.out, exist_ok=True)
    manifest = []
    for b in banks:
        path = os.path.join(args.out, b["id"] + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(b, f, ensure_ascii=False, indent=2)
        manifest.append({"id": b["id"], "file": path, "title": b["title"],
                         "count": len(b["questions"])})
    manifest_path = os.path.join(args.out, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump({"exam": args.exam, "label": args.label, "source": args.source,
                   "banks": manifest, "stats": stats}, f, ensure_ascii=False, indent=2)

    print("=" * 66)
    print("试卷：%s    答案：%d 题" % (args.label, len(key)))
    for w in stats["warnings"]:
        print("  [警告] " + w)
    for tname in ORDER:
        s = stats["types"].get(tname)
        if s:
            print("  %-34s %2d 题   可判分 %2d   缺答案 %2d"
                  % (b["id"] if False else TYPE_CN[tname], s["count"], s["gradable"], s["missing_answer"]))
    print("-" * 66)
    print("共 %d 个题库 / %d 题（可判分 %d）" % (len(banks), stats["total"], stats["gradable"]))
    print("manifest: " + manifest_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
