package cn.tonglu.app.data.english

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包精简词书（EF03）的合并规则。
 *
 * 钉住两条业务边界，任何一条被改坏都会直接影响线上：
 * 1. **只补不换** —— 云端/缓存清单里已有的书（含用户学过的）原样保留、顺序不变；
 *    随包书只补 id 不重复的，同 id 以云端为准。
 * 2. **不完整的不进列表** —— 缺指纹/缺词数/重复 id 的随包条目直接丢掉，
 *    换词书列表里不能出现「点开没反应」的一行。
 *
 * 为什么值得单测：这两件事都发生在 `EnglishBookStore.catalog()` 的 IO 分支里，
 * 真机/网络条件不好复现；而它们又是「随包会覆盖用户已下载书」这类事故的最后一道闸。
 */
class BundledBooksTest {

    private fun book(
        id: String,
        name: String = "书 $id",
        category: String = "国内考试",
        count: Int = 100,
        revision: String = "revision-$id",
        asset: String = "$id.book",
        bytes: Int = 1000,
        sha256: String = "a".repeat(64),
        source: String = "KyleBing/english-vocabulary",
        license: String = "BSD-3-Clause",
        sourceUrl: String = "https://github.com/KyleBing/english-vocabulary",
    ) = EnglishBook(id, name, category, count, revision, asset, bytes, sha256, source, license, sourceUrl)

    private fun ids(books: List<EnglishBook>) = books.map { it.id }

    /** 正常合并：精简书插在完整六级后面，原有书顺序与对象都原样保留。 */
    @Test
    fun `随包书插在锚点后面 原有书不动`() {
        val base = listOf(book("kylebing-cet4"), book("kylebing-cet6"), book("kylebing-ielts"))
        val extras = listOf(book("kylebing-cet4-compact"), book("kylebing-cet6-compact"))
        val merged = mergeBundledBooks(base, extras, "kylebing-cet6")
        assertEquals(
            listOf("kylebing-cet4", "kylebing-cet6", "kylebing-cet4-compact", "kylebing-cet6-compact", "kylebing-ielts"),
            ids(merged),
        )
        // 原有对象必须原样保留（不是重新构造、不是改字段）
        assertSame(base[0], merged[0])
        assertSame(base[1], merged[1])
        assertSame(base[2], merged[4])
    }

    /** 同 id 以云端/缓存清单为准：随包条目被丢弃，已有书不被替换。 */
    @Test
    fun `同 id 不覆盖已有书`() {
        val serverBook = book("kylebing-cet4-compact", name = "服务端版本", count = 3105)
        val bundledBook = book("kylebing-cet4-compact", name = "随包版本", count = 1)
        val merged = mergeBundledBooks(listOf(serverBook), listOf(bundledBook), "kylebing-cet4-compact")
        assertEquals(1, merged.size)
        assertSame(serverBook, merged[0])
        assertEquals("服务端版本", merged[0].name)
    }

    /** 锚点不存在（服务端清单变了/新装只有缓存）→ 追加到末尾，绝不丢书。 */
    @Test
    fun `锚点不存在时追加到末尾`() {
        val base = listOf(book("a"), book("b"))
        val extras = listOf(book("x"))
        assertEquals(listOf("a", "b", "x"), ids(mergeBundledBooks(base, extras, "missing")))
        assertEquals(listOf("a", "b", "x"), ids(mergeBundledBooks(base, extras, null)))
    }

    /** 没有随包清单时行为必须与改动前完全一致（返回同一个 base）。 */
    @Test
    fun `无随包书时原样返回`() {
        val base = listOf(book("a"), book("b"))
        assertSame(base, mergeBundledBooks(base, emptyList(), "a"))
    }

    /** 不完整条目全部丢掉：缺字段、词数/大小非正、sha 不是 64 位、id 重复。 */
    @Test
    fun `不完整随包条目被丢弃`() {
        val good = book("ok")
        val goodDuplicate = book("ok")
        val noName = book("no-name", name = "   ")
        val noRevision = book("no-revision", revision = "")
        val noAsset = book("no-asset", asset = "")
        val zeroCount = book("zero-count", count = 0)
        val zeroBytes = book("zero-bytes", bytes = 0)
        val shortSha = book("short-sha", sha256 = "abc")
        val valid = validBundledBooks(
            listOf(good, goodDuplicate, noName, noRevision, noAsset, zeroCount, zeroBytes, shortSha)
        )
        assertEquals(1, valid.size)
        assertEquals("ok", valid[0].id)
    }

    /** 归一化：sha 统一小写、字段去首尾空白；完整条目一个都不许少。 */
    @Test
    fun `字段归一化后可用`() {
        val upper = book("upper", sha256 = "AB".repeat(32), name = " 标题 ")
        val valid = validBundledBooks(listOf(upper))
        assertEquals(1, valid.size)
        assertEquals("AB".repeat(32).lowercase(), valid[0].sha256)
        assertEquals("标题", valid[0].name)
        assertTrue(valid[0].license.isNotBlank())
    }

    /** Gson 缺字段时随包条目会是 null：不能因此抛异常，整份清单按空处理。 */
    @Test
    fun `清单为 null 时返回空列表`() {
        assertTrue(validBundledBooks(null).isEmpty())
    }
}
