package cn.tonglu.app.data.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「某个口音还没有离线模型时，界面不能是一片空白」这条规则的回归测试。
 *
 * ## 为什么有它
 *
 * 2026-10-08 用户报「英式英语的声音压根无法下载模型，也没有模型可下载」。
 * 真因不是模型缺失（那本来就是内容缺口），而是 `SpeechCatalog.voices()` 用
 * `filter { it.accent == accent }` 把不匹配的模型**整个滤掉**，于是选「英式」时
 * 列表里只剩「系统朗读」一条 —— 用户既不知道英式离线声音存不存在，也找不到下载入口。
 *
 * ## 判据（按 `docs/BUGS.md` 的规矩：故意改回 bug 的样子，测试必须变红）
 *
 * 把 [SpeechCatalog.merge] 里的 `ifEmpty { listOf(pending(accent)) }` 删掉，
 * [某口音没有内置模型时必须给出待提供占位] 必须失败。不红就说明这条规则没被锁住。
 */
class SpeechCatalogTest {

    private fun fakeVoice(accent: Accent, id: String = "builtin-x") = SpeechVoice(
        id = id, name = "假的离线声线", source = SoundSource.BuiltinVoice,
        accent = accent, gender = VoiceGender.Unspecified, available = true,
    )

    private fun systemVoice() = SpeechCatalog.systemVoice(ready = true, accent = Accent.American)

    @Test
    fun `某口音没有内置模型时必须给出待提供占位`() {
        // 只有「美式」有内置模型 —— 英式/中文都该拿到占位。
        val builtin = listOf(fakeVoice(Accent.American))
        for (accent in Accent.entries) {
            val merged = SpeechCatalog.merge(accent, systemVoice(), builtin)
            if (accent == Accent.American) continue
            assertEquals(
                "口音 $accent 没有内置模型时，清单里必须有一条占位（否则界面只剩「系统朗读」，用户以为坏了）",
                2, merged.size,
            )
            val placeholder = merged[1]
            assertFalse("占位必须是「不可用」", placeholder.available)
            assertTrue(
                "占位必须写明原因，否则界面只会显示一个空的「待提供」",
                placeholder.unavailableReason.isNotBlank(),
            )
        }
    }

    @Test
    fun `有内置模型时不能混进占位`() {
        val builtin = listOf(fakeVoice(Accent.American, "builtin-en"))
        val merged = SpeechCatalog.merge(Accent.American, systemVoice(), builtin)
        assertEquals(2, merged.size)
        assertEquals("builtin-en", merged[1].id)
        assertNotEquals(
            "有真模型时不该出现占位（否则会多出一条重复的声线）",
            SpeechCatalog.pending(Accent.American).id, merged[1].id,
        )
    }

    @Test
    fun `占位的 modelId 必须为空`() {
        // `VoiceRow` 靠 modelId 区分「没下载模型」（给下载按钮）
        // 和「还没提供」（显示「待提供」、无按钮）。占位若带上 modelId，
        // 界面会渲染出一个**点不动的下载按钮** —— 那比不显示还糟。
        for (accent in Accent.entries) {
            assertEquals(
                "占位的 modelId 必须留空（VoiceRow 靠它区分「未下载模型」和「待提供」）",
                "", SpeechCatalog.pending(accent).modelId,
            )
            assertFalse(SpeechCatalog.pending(accent).available)
            assertEquals(accent, SpeechCatalog.pending(accent).accent)
        }
    }

    @Test
    fun `系统朗读那一条永远排在最前`() {
        val merged = SpeechCatalog.merge(Accent.British, systemVoice(), emptyList())
        assertEquals(SoundSource.SystemTts, merged.first().source)
    }

    // ————————————————————————————————————————————————————————————————
    //  一个模型展开成多条声线（2026-10-08 用户报「只有一个女声，没有男声」）
    //  判据：把 [SpeechCatalog.expand] 里的 `model.voices.ifEmpty {...}` 去掉，
    //  或者把 `speakerId = v.sid` 写成 `speakerId = 0`，下面这几条必须变红。
    // ————————————————————————————————————————————————————————————————

    private fun model(
        id: String = "zh-demo",
        voices: List<TtsVoice> = emptyList(),
    ) = TtsModel(
        id = id, name = "中文·多说话人（离线）", lang = "zh", accent = Accent.Chinese,
        url = "https://example.invalid/x.zip", bytes = 1_000L, sha256 = "",
        license = "L", licenseUrl = "", attribution = "", note = "",
        modelFile = "m.onnx", lexicon = "lexicon.txt", tokens = "tokens.txt",
        dataDir = "", dictDir = "", ruleFsts = emptyList(), ruleFars = emptyList(),
        speakers = 187, voices = voices,
    )

    @Test
    fun `没声明 voices 的模型退化成一条 sid 为 0 的声线`() {
        // 老清单没有 voices 字段。行为必须与以前**逐字一致**，
        // 否则升级之后老用户的声音列表会凭空变空 —— 那是最坏的一类回归。
        val one = SpeechCatalog.expand(model(), installed = true)
        assertEquals("没声明 voices 时必须只给一条，不能凭空多出来", 1, one.size)
        assertEquals(0, one[0].speakerId)
        assertEquals(
            "单条声线的 id 必须保持 builtin-<模型id> 不变（用户偏好里存的就是这个字符串）",
            "builtin-zh-demo", one[0].id,
        )
    }

    @Test
    fun `声明了 voices 时每条都带上自己的说话人序号`() {
        val m = model(
            voices = listOf(
                TtsVoice(sid = 3, gender = "female", name = "中文·女声（离线）"),
                TtsVoice(sid = 61, gender = "male", name = "中文·男声（离线）"),
            ),
        )
        val vs = SpeechCatalog.expand(m, installed = true)
        assertEquals(2, vs.size)
        // 序号必须原样传下去：写成 0 的话，用户选男声也会出女声，
        // 而且界面上看不出任何异常（引擎把越界序号静默夹回 0）。
        assertEquals(listOf(3, 61), vs.map { it.speakerId })
        assertEquals(listOf(VoiceGender.Female, VoiceGender.Male), vs.map { it.gender })
        assertEquals(listOf("中文·女声（离线）", "中文·男声（离线）"), vs.map { it.name })
        // 多条时 id 必须互不相同：LazyColumn 用 id 做 key，重了会崩/串行。
        assertEquals(2, vs.map { it.id }.toSet().size)
        assertEquals(listOf("builtin-zh-demo#3", "builtin-zh-demo#61"), vs.map { it.id })
        // 两条指向的是同一个模型 —— 区分靠 speakerId，不是靠 modelId。
        // 这条也是「只下载一次」的依据：用户装了模型，两条声线同时变可用。
        assertTrue("两条声线必须指向同一个模型", vs.all { it.modelId == "zh-demo" })
    }

    @Test
    fun `性别认不出来必须是未标不能默认成女声`() {
        // 猜错 = 骗人。用户点「男声」出来女声，比老老实实写「未标」糟糕得多。
        assertEquals(VoiceGender.Unspecified, SpeechCatalog.genderOf(""))
        assertEquals(VoiceGender.Unspecified, SpeechCatalog.genderOf("unknown"))
        assertEquals(VoiceGender.Unspecified, SpeechCatalog.genderOf("Female-ish"))
        assertEquals(VoiceGender.Female, SpeechCatalog.genderOf("Female"))
        assertEquals(VoiceGender.Female, SpeechCatalog.genderOf(" 女 "))
        assertEquals(VoiceGender.Male, SpeechCatalog.genderOf("MALE"))
        assertEquals(VoiceGender.Male, SpeechCatalog.genderOf("男声"))
    }

    @Test
    fun `模型没下载时每一说话人都不可用`() {
        val m = model(
            voices = listOf(
                TtsVoice(sid = 3, gender = "female", name = "女"),
                TtsVoice(sid = 61, gender = "male", name = "男"),
            ),
        )
        val vs = SpeechCatalog.expand(m, installed = false)
        assertTrue("没下载时不能有任一条显示成可用", vs.none { it.available })
        assertTrue("每一条都要写明为什么不可用", vs.all { it.unavailableReason.isNotBlank() })
    }

    @Test
    fun `孤儿目录只清清单外的普通目录`() {
        // 这条规则会**误删正在下载的临时目录**：`.staging-x` / `.dl-x.zip` / `.old-x`
        // 全是 download() 自己的中间状态，清早了会让下载半路失败。
        val dead = TtsModelStore.orphanDirs(
            existing = listOf("zh-aishell3", "en-ljs", ".staging-zh-fanchen-c", ".dl-x.zip"),
            alive = setOf("zh-fanchen-c", "en-ljs"),
        )
        assertEquals(listOf("zh-aishell3"), dead)
    }

    @Test
    fun `清孤儿真的删掉旧目录且绝不动下载中的临时目录`() {
        // 光测 orphanDirs（返回名字）不够：真正会闯祸的是 **deleteRecursively 那一下**。
        // 这里用真临时目录走完整条路，确认删的是谁、留下的是谁。
        val root = java.nio.file.Files.createTempDirectory("tts-prune").toFile()
        try {
            val old = File(root, "zh-aishell3")
            old.mkdirs()
            File(old, "model.onnx").writeText("权重")
            val staging = File(root, ".staging-zh-fanchen-c")
            staging.mkdirs()
            File(staging, "model.onnx").writeText("下了一半")
            File(root, "en-ljs").mkdirs()

            val removed = TtsModelStore.pruneOrphans(root, setOf("zh-fanchen-c", "en-ljs"))

            assertEquals("只该删旧的那一个", listOf("zh-aishell3"), removed)
            assertFalse("旧目录必须真的从磁盘上没了", old.exists())
            assertTrue("下载中的临时目录被删会让下载半路崩掉", staging.isDirectory)
            assertTrue("清单里还活着的模型不能被删", File(root, "en-ljs").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    // ————————————————————————————————————————————————————————————————
    //  APK 内置兜底清单必须与线上发布清单一致（2026-10-15 英式「发不出声音」的真因之一）
    //  判据：把 `TtsModelStore.fallback` 里的英式那一条删掉，下面两条必须变红。
    // ————————————————————————————————————————————————————————————————

    @Test
    fun `内置兜底清单必须与线上发布的三条模型一致`() {
        // 清单有两个来源：联网拉到的那份，和 APK 里编死的这份。用户在**没拉过清单的页面**
        // （单词练习/每日例句/词卡）解析声线时用的就是这一份 ——
        // 少一条的后果不是「少个选项」，而是「下载过、选过的声线解析不出来 → 退回系统朗读」。
        assertEquals(
            "兜底清单必须与线上 manifest.json 的三条逐条对齐",
            listOf("zh-fanchen-c", "en-ljs", "en-vctk-british"),
            TtsModelStore.fallback.map { it.id },
        )
        val british = TtsModelStore.fallback.first { it.id == "en-vctk-british" }
        assertEquals(Accent.British, british.accent)
        assertEquals("en", british.lang)
        assertEquals(30_326_764L, british.bytes)
        assertEquals("8198f6f6545eb012c0af4da00612ce04fc3a1f5cc29075160c2d514d6eb2ea19", british.sha256)
        assertEquals("vits-vctk.int8.onnx", british.modelFile)
        assertEquals(109, british.speakers)
        // 用户偏好里存的就是这两个 id。展开规则一变，「选过」就变成「没选过」。
        assertEquals(
            listOf("builtin-en-vctk-british#101", "builtin-en-vctk-british#43"),
            SpeechCatalog.expand(british, installed = true).map { it.id },
        )
        // 公开分发的资源必须有出处与许可（plan §5 第 3 条）。
        TtsModelStore.fallback.forEach { model ->
            assertTrue("${model.id} 缺下载地址", model.url.startsWith("https://"))
            assertEquals("${model.id} 的 sha256 长度不对（下载校验靠它）", 64, model.sha256.length)
            assertTrue("${model.id} 缺许可", model.license.isNotBlank())
        }
    }

    @Test
    fun `英式离线声线的限制必须如实写在 note 里`() {
        // 词典源自美式 CMU 音素：不许拿它冒充「标准英音」，限制必须随条目带给用户。
        val british = TtsModelStore.fallback.first { it.id == "en-vctk-british" }
        assertTrue("note 要说清词典来源的限制", british.note.contains("词典"))
        assertTrue(SpeechCatalog.expand(british, installed = true).all { it.note == british.note })
    }

    @Test
    fun `系统朗读不可用时的原因必须按口音分开写`() {
        val british = SpeechCatalog.systemVoice(ready = false, accent = Accent.British)
        assertTrue(
            "英式缺的是「英国英语语音数据」，笼统写成「没有引擎」会让用户去改错地方",
            british.unavailableReason.contains("English UK"),
        )
        val american = SpeechCatalog.systemVoice(ready = false, accent = Accent.American)
        assertTrue(american.unavailableReason.isNotBlank())
    }
}
