#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""资源出处 / 许可 / 指纹登记审计（槽位 C · plan §5 第 3 条「资源可获取」）。

钉的是这一句话：**公开分发的资源，出处与许可必须能独立读到**。

为什么需要它（不是形式主义）：
plan 第 3 条要求「来源、答案、解析、音频完整性、许可**分别登记**；
以前已发布不代表许可问题自动解决」。本轮实测发现 `EnglishBook` 只有**一个**
`source` 字段 —— 仓库名和许可证被拼成一坨字符串（`KyleBing/english-vocabulary（BSD-3-Clause）`），
于是：界面没法点回原文、没法按许可筛选、也没法**逐项校验某一项空了**。
同一个仓库里 `QuestionBank` 却有 `sourceNote / license / sourceUrl / licenseProof / scope`
四件套 —— **同一件事两套标准**，差的那套正是「已经公开分发了好几个月」的那一套。
「发布过」不等于「登记过」，这条审计就是用来把这句话变成可执行的判据。

跟项目里其它审计同一套规矩：
- **静态审计**（不连库、不发请求）：读 `schema.prisma` 的 model 字段 + 本地 `catalog.json`。
  `audit_brand_color.py` / `audit_api_contract.py` 也是这个路子 —— 能在本机 CI 里跑，
  不依赖「此刻服务器在不在」。
- **带 `--selftest`**：造一组样本（含**故意不合格**的对照组），必须红绿分明。
  判据自己写错比判据缺失更危险（BUGS S56），所以自验是硬要求。

用法：
    python scripts/audit_resource_provenance.py            # 审真实文件
    python scripts/audit_resource_provenance.py --selftest # 自验（含对照组）
"""
import hashlib
import io
import json
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCHEMA = os.path.join(ROOT, "backend", "prisma", "schema.prisma")
CATALOG = os.path.join(ROOT, "apps", "android", "app", "src", "main",
                       "assets", "english", "catalog.json")
# 随包分发的词书（EF03）：清单与文件一起进 APK，必须和云端词库同样登记出处/许可/指纹。
BUNDLED_MANIFEST = os.path.join(ROOT, "apps", "android", "app", "src", "main",
                                "assets", "english", "bundled", "catalog.json")
BUNDLED_DIR = os.path.dirname(BUNDLED_MANIFEST)
LEARNING_CATALOG = os.path.join(ROOT, "content", "learning", "catalog.json")

# 每个「公开分发的资源类型」必须登记哪些位。
# 说明写在 model 里而不是这里 —— 这里只列**必须存在**的字段名。
REQUIRED = {
    "EnglishBook": {
        "指纹（下载后核对）": ["sha256", "bytes"],
        "来源与许可（分别登记）": ["source", "license", "sourceUrl"],
    },
    "QuestionBank": {
        "指纹（下载后核对）": ["sha256", "bytes"],
        "来源与许可（分别登记）": ["sourceNote", "license", "sourceUrl", "licenseProof"],
    },
    "LearningPack": {
        "版本（不可变）": ["packageId", "version"],
    },
}


def strip_comments(text):
    """去掉 `///` 行注释与 `//` 行尾注释。

    为什么必须剥：schema 的注释里**大量出现字段名**（「`@@map` 只改表名」这类），
    不剥会把「注释里提到了 license」当成「真的有 license 这一列」。
    `audit_brand_color.py` 踩过同一件事（封杀旧值前先剥注释）。
    """
    out = []
    for line in text.splitlines():
        # 行尾注释：不在引号里的 //
        in_str = False
        i = 0
        while i < len(line):
            c = line[i]
            if c == '"':
                in_str = not in_str
            elif c == "/" and not in_str and i + 1 < len(line) and line[i + 1] == "/":
                line = line[:i]
                break
            i += 1
        out.append(line)
    return "\n".join(out)


def model_fields(schema_text, model):
    """取某个 model 块里的字段名（只取 `name Type`，跳过 `@`/`@@` 属性行）。"""
    body = strip_comments(schema_text)
    m = re.search(r"^\s*model\s+%s\s*\{(.*?)^\s*\}" % re.escape(model),
                  body, re.M | re.S)
    if not m:
        return None
    fields = set()
    for line in m.group(1).splitlines():
        line = line.strip()
        if not line or line.startswith("@") or line.startswith("//"):
            continue
        mm = re.match(r"^([A-Za-z_]\w*)\s+[A-Za-z_][\w\[\]<>?,\s]*", line)
        if mm:
            fields.add(mm.group(1))
    return fields


def check_schema(schema_text):
    """审 schema：每个资源类型的必登记位是否真的在表里。"""
    bad = []
    for model, groups in REQUIRED.items():
        fields = model_fields(schema_text, model)
        if fields is None:
            bad.append("%s：schema 里找不到这个 model（改名了？）" % model)
            continue
        for label, names in groups.items():
            missing = [n for n in names if n not in fields]
            if missing:
                bad.append("%s 缺「%s」：%s" % (model, label, "、".join(missing)))
    return bad


def check_catalog(catalog_text):
    """审本地词库清单：每一本都要有出处、许可、指纹（不能有一本漏登记）。"""
    bad = []
    try:
        books = json.loads(catalog_text)
    except (ValueError, TypeError) as e:
        return ["catalog.json 解析失败：%s" % e]
    if not isinstance(books, list) or not books:
        return ["catalog.json 不是非空数组"]
    for b in books:
        bid = b.get("id", "?")
        for key in ("source", "license", "sourceUrl", "sha256", "bytes"):
            if not str(b.get(key) or "").strip():
                bad.append("词库 %s 缺 %s" % (bid, key))
        sha = str(b.get("sha256") or "")
        if sha and not re.match(r"^[0-9a-fA-F]{64}$", sha):
            bad.append("词库 %s 的 sha256 不是 64 位十六进制：%r" % (bid, sha[:20]))
    return bad


def check_bundled_books(manifest_text, bundled_dir):
    """审随包词书清单（`assets/english/bundled/catalog.json`）。

    随包 = 直接进 APK 的公开分发资源，判据比照云端词库、再加两条随包特有的：
    - 清单登记了 `asset`，**文件就必须真的在 bundled 目录里**，且 bytes/sha256 与实算一致
      （登记了假指纹比没有指纹更坏 —— 与考试材料 `contentSha256` 同一条纪律）；
    - `asset` 只能是文件名，不许带路径（不然清单能指向目录外任意文件）。
    """
    bad = []
    try:
        manifest = json.loads(manifest_text)
    except (ValueError, TypeError) as e:
        return ["随包词书清单解析失败：%s" % e]
    if not isinstance(manifest, dict):
        return ["随包词书清单不是 JSON 对象"]
    version = manifest.get("bundleVersion")
    if not isinstance(version, int) or version < 1:
        bad.append("随包词书清单缺 bundleVersion（应为 ≥1 的整数，用于版本化替换）")
    books = manifest.get("books")
    if not isinstance(books, list) or not books:
        return bad + ["随包词书清单 books 必须是非空数组"]
    ids = set()
    for b in books:
        if not isinstance(b, dict):
            bad.append("随包词书条目不是对象")
            continue
        bid = str(b.get("id") or "?")
        for key in ("id", "name", "category", "revision", "asset", "source", "license", "sourceUrl"):
            if not str(b.get(key) or "").strip():
                bad.append("随包词书 %s 缺 %s（出处/许可/指纹必须分别登记）" % (bid, key))
        for key in ("count", "bytes"):
            value = b.get(key)
            if not isinstance(value, int) or value <= 0:
                bad.append("随包词书 %s 的 %s 必须是正整数" % (bid, key))
        sha = str(b.get("sha256") or "")
        if not re.match(r"^[0-9a-f]{64}$", sha):
            bad.append("随包词书 %s 的 sha256 不是 64 位小写十六进制：%r" % (bid, sha[:20]))
        if bid in ids:
            bad.append("随包词书 id 重复：%s" % bid)
        ids.add(bid)
        asset = str(b.get("asset") or "")
        if asset and ("/" in asset or "\\" in asset or asset in (".", "..")):
            bad.append("随包词书 %s 的 asset 必须是文件名（不许带路径）：%r" % (bid, asset))
            continue
        if not asset:
            continue
        path = os.path.join(bundled_dir, asset)
        if not os.path.exists(path):
            bad.append("随包词书 %s 登记了 asset=%s，但文件不存在" % (bid, asset))
            continue
        with open(path, "rb") as stream:
            data = stream.read()
        if isinstance(b.get("bytes"), int) and len(data) != b["bytes"]:
            bad.append("随包词书 %s 的 bytes 登记 %s，文件实际 %s" % (bid, b["bytes"], len(data)))
        if sha and hashlib.sha256(data).hexdigest() != sha:
            bad.append("随包词书 %s 的 sha256 与 bundled 文件实算不符" % bid)
        if data[:2] != b"\x1f\x8b":
            bad.append("随包词书 %s 不是 gzip 包（.book 应为 gzip 的 jsonl）" % bid)
    return bad


def check_learning_exam(catalog_text):
    """审学习目录里的**考试材料条目**（带 exam 元数据的文章；B 槽精读考试入口的数据面）。

    四六级历年真题没有授权 —— 现阶段的红线就写在判据里：
    - FULLTEXT（全文收录）：来源 / 许可 / 指纹必须分别登记，且 sha256 要与正文**实算一致**
      （登记了假指纹比没有指纹更坏）；发布权利必须已确认。
    - SOURCE_LINK（仅原站导航）：**绝不能携带正文**（把链接冒充离线全文），
      原文地址必须是真的 https。
    普通学习资料（不带 exam）不在这里审 —— 那走管理后台与 packages/learning-catalog 的校验。
    """
    bad = []
    try:
        data = json.loads(catalog_text)
    except (ValueError, TypeError) as e:
        return ["learning catalog.json 解析失败：%s" % e]
    articles = data.get("articles") if isinstance(data, dict) else None
    if not isinstance(articles, list):
        return ["learning catalog.json 没有 articles 数组"]
    for a in articles:
        if not isinstance(a, dict) or "exam" not in a:
            continue
        aid = a.get("id", "?")
        exam = a.get("exam")
        body = str(a.get("body") or "")
        sha = str(a.get("contentSha256") or "")
        if not isinstance(exam, dict):
            bad.append("考试材料 %s 的 exam 必须是对象" % aid)
            continue
        availability = exam.get("availability")
        exam_type = exam.get("examType")
        if exam_type is not None and exam_type not in ("CET4", "CET6"):
            bad.append("考试材料 %s 的 examType 不在 CET4/CET6：%r" % (aid, exam_type))
        year = exam.get("year")
        if year is not None and (not isinstance(year, int) or not 1990 <= year <= 2100):
            bad.append("考试材料 %s 的 year 不合法：%r" % (aid, year))
        if availability == "FULLTEXT":
            if not body.strip():
                bad.append("考试材料 %s 标记 FULLTEXT 但正文为空" % aid)
            for key in ("sourceUrl", "licenseId", "attribution"):
                if not str(a.get(key) or "").strip():
                    bad.append("考试材料 %s（FULLTEXT）缺 %s（来源与许可必须分别登记）" % (aid, key))
            if a.get("rightsConfirmed") is not True:
                bad.append("考试材料 %s（FULLTEXT）的发布权利未确认（rightsConfirmed）" % aid)
            if not re.match(r"^[0-9a-fA-F]{64}$", sha):
                bad.append("考试材料 %s（FULLTEXT）缺 64 位十六进制 contentSha256" % aid)
            elif sha != hashlib.sha256(body.encode("utf-8")).hexdigest():
                bad.append("考试材料 %s 的 contentSha256 与正文实算不符（登记了假指纹）" % aid)
        elif availability == "SOURCE_LINK":
            if body.strip():
                bad.append("考试材料 %s 标记 SOURCE_LINK 却携带正文（不许把链接冒充离线全文）" % aid)
            if not str(a.get("sourceUrl") or "").startswith("https://"):
                bad.append("考试材料 %s（SOURCE_LINK）缺 https 原文地址" % aid)
        else:
            bad.append("考试材料 %s 缺 availability（FULLTEXT / SOURCE_LINK）" % aid)
    return bad


def run(root=ROOT):
    bad = []
    p = os.path.join(root, "backend", "prisma", "schema.prisma")
    c = os.path.join(root, "apps", "android", "app", "src", "main", "assets",
                     "english", "catalog.json")
    if not os.path.exists(p):
        bad.append("找不到 schema：%s" % p)
    else:
        bad += check_schema(io.open(p, encoding="utf-8").read())
    catalog_text = None
    if not os.path.exists(c):
        bad.append("找不到 catalog：%s" % c)
    else:
        catalog_text = io.open(c, encoding="utf-8").read()
        bad += check_catalog(catalog_text)
    bm = os.path.join(root, "apps", "android", "app", "src", "main", "assets",
                      "english", "bundled", "catalog.json")
    if not os.path.exists(bm):
        bad.append("找不到随包词书清单：%s（随包词书也必须登记出处/许可/指纹）" % bm)
    else:
        bundle_text = io.open(bm, encoding="utf-8").read()
        bad += check_bundled_books(bundle_text, os.path.dirname(bm))
        # 随包书不许和出厂清单同 id：同 id 会遮蔽旧书 —— 「不能覆盖旧资源」的硬判据。
        if catalog_text:
            try:
                factory_ids = {str(x.get("id")) for x in json.loads(catalog_text) if isinstance(x, dict)}
                bundle_books = json.loads(bundle_text).get("books") or []
                bundled_ids = {str(x.get("id")) for x in bundle_books if isinstance(x, dict)}
                overlap = sorted(factory_ids & bundled_ids)
                if overlap:
                    bad.append("随包词书与出厂清单同 id（会遮蔽旧书）：%s" % "、".join(overlap))
            except (ValueError, TypeError):
                pass  # 清单解析失败已由上面的检查点名，不重复报
    lc = os.path.join(root, "content", "learning", "catalog.json")
    if not os.path.exists(lc):
        bad.append("找不到 learning catalog：%s" % lc)
    else:
        bad += check_learning_exam(io.open(lc, encoding="utf-8").read())
    return bad


SELFTEST_SCHEMA = """
model EnglishBook {
  id        String   @id
  name      String
  bytes     Int
  sha256    String
  /// 注释里提到 license 不算数 —— 用来证明「剥注释」这一步真的生效
  source    String?
  license   String?
  sourceUrl String?
  @@map("english_books")
}

model QuestionBank {
  id          String   @id
  sourceNote  String
  license     String?
  sourceUrl   String?
  licenseProof String?
  bytes       Int      @default(0)
  sha256      String   @default("")
  @@map("question_banks")
}

model LearningPack {
  packageId String
  version   Int
}
"""

SELFTEST_CATALOG = [
    {"id": "ok-book", "source": "某人/某库", "license": "MIT",
     "sourceUrl": "https://example.org", "sha256": "a" * 64, "bytes": 1024},
    # ↓ 对照组：故意漏 license，必须被抓出来
    {"id": "no-license", "source": "某人/某库", "license": "",
     "sourceUrl": "https://example.org", "sha256": "b" * 64, "bytes": 2048},
]


def selftest():
    """自验：好样本必须绿；**故意写坏的对照组必须红**（对照无效 = 判据无效）。"""
    ok = True

    # ① schema 合格样本 → 必须绿
    good = check_schema(SELFTEST_SCHEMA)
    print("  ① 合格 schema → 期望 0 条，实到 %d 条" % len(good))
    if good:
        ok = False
        for x in good:
            print("     !! " + x)

    # ② 对照组：把 license / sourceUrl 两列**真删掉**、只在注释里留个名字 → 必须红。
    #    两条都验：剥注释生效（注释里的名字不算数）+ 缺少的列真的被点到名。
    fake = SELFTEST_SCHEMA.replace("  license   String?\n", "")
    fake = fake.replace("  sourceUrl String?\n", "")
    fake = fake.replace("  /// 注释里提到 license 不算数 —— 用来证明「剥注释」这一步真的生效\n",
                        "  /// license 与 sourceUrl 只写在注释里，列其实没有\n")
    bad = check_schema(fake)
    hit = [x for x in bad if "EnglishBook" in x and "license" in x]
    print("  ② 对照组（license 只在注释里）→ 期望抓到，实到 %d 条" % len(hit))
    if not hit:
        ok = False
        print("     !! 没抓到 —— 剥注释或字段检查有一个是假的")

    # ③ catalog 对照组：一本漏 license → 必须红且点名
    bad = check_catalog(json.dumps(SELFTEST_CATALOG))
    hit = [x for x in bad if "no-license" in x]
    print("  ③ catalog 对照组（漏 license）→ 期望抓到，实到 %d 条" % len(hit))
    if not hit:
        ok = False
        print("     !! 没抓到 —— catalog 检查是假的")
    for x in bad:
        print("     " + x)

    # ④ 全合格 catalog → 必须绿
    good = check_catalog(json.dumps([SELFTEST_CATALOG[0]]))
    print("  ④ 合格 catalog → 期望 0 条，实到 %d 条" % len(good))
    if good:
        ok = False
        for x in good:
            print("     !! " + x)

    # ⑤ 考试材料合格样本（FULLTEXT 带真指纹 + SOURCE_LINK 无正文）→ 必须绿
    import hashlib as _h
    exam_body = "It is a truth universally acknowledged."
    good_exam = check_learning_exam(json.dumps({
        "articles": [
            {"id": "ok-full", "kind": "MARKDOWN", "body": exam_body,
             "sourceUrl": "https://example.org", "licenseId": "CUSTOM",
             "attribution": "原作者", "rightsConfirmed": True,
             "contentSha256": _h.sha256(exam_body.encode("utf-8")).hexdigest(),
             "exam": {"examType": None, "availability": "FULLTEXT"}},
            {"id": "ok-link", "kind": "LINK", "body": "",
             "sourceUrl": "https://cet.neea.edu.cn/",
             "exam": {"examType": "CET4", "availability": "SOURCE_LINK"}},
        ],
    }))
    print("  ⑤ 合格考试材料 → 期望 0 条，实到 %d 条" % len(good_exam))
    if good_exam:
        ok = False
        for x in good_exam:
            print("     !! " + x)

    # ⑥ 考试材料对照组：假指纹 / SOURCE_LINK 带正文 / 未确认权利 → 必须红且点名
    bad_exam = check_learning_exam(json.dumps({
        "articles": [
            {"id": "fake-sha", "kind": "MARKDOWN", "body": "hello",
             "sourceUrl": "https://example.org", "licenseId": "CUSTOM",
             "attribution": "原作者", "rightsConfirmed": True,
             "contentSha256": "a" * 64,
             "exam": {"availability": "FULLTEXT"}},
            {"id": "link-with-body", "kind": "LINK", "body": "冒充离线全文",
             "sourceUrl": "https://example.org",
             "exam": {"examType": "CET6", "availability": "SOURCE_LINK"}},
            {"id": "no-rights", "kind": "MARKDOWN", "body": "b",
             "sourceUrl": "https://example.org", "licenseId": "CUSTOM",
             "attribution": "原作者", "rightsConfirmed": False,
             "contentSha256": _h.sha256(b"b").hexdigest(),
             "exam": {"availability": "FULLTEXT"}},
        ],
    }))
    hit_names = [x for x in bad_exam
                 if any(n in x for n in ("fake-sha", "link-with-body", "no-rights"))]
    print("  ⑥ 考试材料对照组（假指纹/链接带正文/未确认权利）→ 期望 3 条点名，实到 %d 条" % len(hit_names))
    if len(hit_names) < 3:
        ok = False
        print("     !! 没抓全 —— 考试材料检查有假")
    for x in bad_exam:
        print("     " + x)

    # ⑦ 随包词书合格样本（真文件 + 真指纹）→ 必须绿
    import hashlib as _h
    with tempfile.TemporaryDirectory() as tmp:
        bundled = os.path.join(tmp, "bundled")
        os.makedirs(bundled)
        payload = b"\x1f\x8b" + b"demo-payload"
        with open(os.path.join(bundled, "demo.book"), "wb") as stream:
            stream.write(payload)
        good_bundle = json.dumps({
            "bundleVersion": 1,
            "books": [{"id": "demo", "name": "演示", "category": "国内考试", "count": 3,
                       "revision": "r", "asset": "demo.book", "bytes": len(payload),
                       "sha256": _h.sha256(payload).hexdigest(),
                       "source": "src", "license": "MIT", "sourceUrl": "https://example.org"}],
        })
        problems = check_bundled_books(good_bundle, bundled)
        print("  ⑦ 合格随包词书 → 期望 0 条，实到 %d 条" % len(problems))
        if problems:
            ok = False
            for x in problems:
                print("     !! " + x)

        # ⑧ 随包词书对照组：假指纹 / 错 bytes / 缺文件 / 路径穿越 / 缺 bundleVersion / 重复 id → 必须红
        bad_bundle = json.dumps({
            "books": [
                {"id": "fake", "name": "假指纹", "category": "国内考试", "count": 3,
                 "revision": "r", "asset": "demo.book", "bytes": 1,
                 "sha256": "0" * 64, "source": "src", "license": "MIT",
                 "sourceUrl": "https://example.org"},
                {"id": "missing", "name": "缺文件", "category": "国内考试", "count": 3,
                 "revision": "r", "asset": "nope.book", "bytes": 1,
                 "sha256": "1" * 64, "source": "src", "license": "MIT",
                 "sourceUrl": "https://example.org"},
                {"id": "escape", "name": "路径穿越", "category": "国内考试", "count": 3,
                 "revision": "r", "asset": "../escape.book", "bytes": 1,
                 "sha256": "2" * 64, "source": "src", "license": "MIT",
                 "sourceUrl": "https://example.org"},
                {"id": "fake", "name": "重复 id", "category": "国内考试", "count": 3,
                 "revision": "r", "asset": "demo.book", "bytes": len(payload),
                 "sha256": _h.sha256(payload).hexdigest(), "source": "src",
                 "license": "MIT", "sourceUrl": "https://example.org"},
            ],
        })
        bad_problems = check_bundled_books(bad_bundle, bundled)
        names = ("bundleVersion", "bytes", "sha256", "文件不存在", "asset", "id 重复")
        hit_names = [x for x in bad_problems if any(n in x for n in names)]
        print("  ⑧ 随包词书对照组（假指纹/错 bytes/缺文件/路径穿越/缺版本/重复 id）→ 期望 ≥5 条点名，实到 %d 条"
              % len(hit_names))
        if len(hit_names) < 5:
            ok = False
            print("     !! 没抓全 —— 随包词书检查有假")
        for x in bad_problems:
            print("     " + x)

    print("  自验：%s" % ("通过（对照组有效）" if ok else "失败"))
    return 0 if ok else 1


def check_cloud_audio(folder):
    """Explicit staged public audio audit, without network or credential access."""
    from pathlib import Path
    directory = Path(folder)
    bad = []
    try:
        index = json.loads((directory / 'public/index.json').read_text('utf8'))
        proof = json.loads((directory / 'provenance.json').read_text('utf8'))
        import gzip
        bundled = Path(__file__).resolve().parents[1] / 'apps/android/app/src/main/assets/speech/english-cet-20261024-v1.json.gz'
        if gzip.decompress(bundled.read_bytes()) != (directory / 'public/index.json').read_bytes():
            bad.append('随包英语索引与公开版本不一致')
        for field in ('source', 'sourceUrl', 'license', 'modelSource', 'modelLicense', 'model'):
            if not index.get(field): bad.append('云英语音频缺 ' + field)
        if index.get('frontendPatch'):
            patch = index['frontendPatch']
            for field in ('source', 'sourceUrl', 'license'):
                if not patch.get(field): bad.append('云英语补音缺 ' + field)
            for voice in patch.get('voices', {}).values():
                body = ''.join(word + ' ' + ' '.join(phones) + '\n' for word, phones in voice['entries'].items()).encode()
                if hashlib.sha256(body).hexdigest() != voice['sha256']:
                    bad.append('云英语补音条目与指纹不一致')
        if index.get('complete') is not True: bad.append('云英语音频不完整')
        files = {row['path']: row for row in proof['files']}
        for row in index['items']:
            path = 'audio/' + row[2] + '.m4a'
            expected = files.get(path)
            if not expected or expected['sha256'] != row[3] or expected['bytes'] != row[4]:
                bad.append('云英语逐条指纹登记不一致: ' + path)
        for name, row in files.items():
            valid = re.fullmatch(r'audio/[a-f0-9]{64}\.m4a', name) or name in (
                'index.json', 'source.json', 'MODEL-LICENSE.txt', 'TEXT-LICENSE.txt', 'NOTICE.txt')
            if not valid:
                bad.append('云英语音频路径无效'); continue
            path = directory / ('pack' if name.startswith('audio/') else 'public') / name
            if not path.is_file() or path.stat().st_size != row['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest() != row['sha256']:
                bad.append('云英语音频文件指纹不匹配: ' + name)
        for notice in ('source.json', 'MODEL-LICENSE.txt', 'TEXT-LICENSE.txt', 'NOTICE.txt'):
            if notice not in files: bad.append('云英语音频缺许可/来源文件: ' + notice)
        pairs = {(row[0], row[1]) for row in index['items']}
        if len(pairs) != len(index['items']) or len(index['items']) != proof['clips']:
            bad.append('云英语音频计数/重复不一致')
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        bad.append('云英语资源审计失败: ' + str(error))
    return bad


def check_intensive():
    """Audit derived packs separately from original paper rights and model rights."""
    from pathlib import Path
    import gzip
    root = Path(ROOT)
    directory = root / 'apps/android/app/src/main/assets/intensive'
    if not directory.exists():
        return []
    bad = []
    try:
        catalog = json.loads((directory / 'catalog.json').read_text('utf8'))
        proof = json.loads((root / 'content/intensive/provenance.json').read_text('utf8'))
        if proof['articles'] != catalog['articles']:
            bad.append('精读索引与逐篇出处登记不一致')
        for field in ('grammarSource', 'grammarLicense', 'translationSource', 'translationLicense', 'modelFingerprint', 'modelFiles'):
            if not proof.get(field): bad.append('精读模型缺 ' + field)
        seen = set()
        for row in catalog['articles']:
            for field in ('source', 'sourceUrl', 'license', 'licenseUrl', 'sourceSha256', 'textSha256', 'modelFingerprint', 'sha256', 'bytes'):
                if not row.get(field): bad.append('精读文章缺 ' + field)
            identifier = row['id']
            if not re.fullmatch(r'cet[46]-202[1-6]-(06|12)-[123]-(cloze|passage[12])', identifier):
                bad.append('精读文章路径无效'); continue
            if identifier in seen: bad.append('精读重复文章')
            seen.add(identifier)
            data = gzip.decompress((directory / 'articles' / (identifier + '.json.gz')).read_bytes())
            if len(data) != row['bytes'] or hashlib.sha256(data).hexdigest() != row['sha256']:
                bad.append('精读文章指纹不匹配 ' + identifier)
            article = json.loads(data)
            if article.get('complete') is not True or article.get('modelFingerprint') != proof['modelFingerprint']:
                bad.append('精读分析不完整/模型不一致 ' + identifier)
            if not all(article.get(key, '').startswith('cuda') for key in ('grammarDevice', 'translationDevice')):
                bad.append('精读缺 GPU 语法/翻译证据 ' + identifier)
        actual = {p.name[:-8] for p in (directory / 'articles').glob('*.json.gz')}
        if actual != seen: bad.append('精读资源存在未登记/遗留文章')
    except (OSError, ValueError, KeyError, TypeError) as error:
        bad.append('精读资源审计失败: ' + str(error))
    return bad


def main():
    if "--selftest" in sys.argv:
        print("自验：")
        return selftest()

    bad = run() + check_intensive()
    if '--cloud-audio' in sys.argv:
        position = sys.argv.index('--cloud-audio') + 1
        if position >= len(sys.argv): bad.append('--cloud-audio 缺目录')
        else: bad += check_cloud_audio(sys.argv[position])
    print("资源出处 / 许可 / 指纹登记审计")
    print("  schema   %s" % SCHEMA)
    print("  catalog  %s" % CATALOG)
    print("  bundled  %s" % BUNDLED_MANIFEST)
    print("  learning %s" % LEARNING_CATALOG)
    intensive_checked = int(os.path.isfile(os.path.join(ROOT, 'apps/android/app/src/main/assets/intensive/catalog.json')))
    count = len(REQUIRED) + 2 + int('--cloud-audio' in sys.argv) + intensive_checked
    if not bad:
        print("\n%d/%d 通过 —— 每个公开分发的资源都登记了出处、许可与指纹"
              % (count, count))
        return 0
    print("\n%d 项不合格：" % len(bad))
    for x in bad:
        print("  - " + x)
    return 1


if __name__ == "__main__":
    sys.exit(main())
