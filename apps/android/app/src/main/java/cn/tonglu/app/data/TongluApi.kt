package cn.tonglu.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.tonglu.app.BuildConfig
import cn.tonglu.app.data.competition.RemoteCompetitionCatalog
import cn.tonglu.app.data.competition.RemoteSubscriptionList
import cn.tonglu.app.data.competition.SubscriptionRequest
import cn.tonglu.app.data.exam.RemoteExamCalendar
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.util.UUID

data class FeedbackSubmission(val submissionId: String, val title: String, val detail: String, val appVersion: String)
data class FeedbackReceipt(val receiptId: String, val acceptedAt: String)

interface TongluApi {
    @POST("feedback") suspend fun submitFeedback(@Body request: FeedbackSubmission): FeedbackReceipt

    @GET("profile/me") suspend fun profile(@Header("Authorization") bearer: String): UserDto
    @retrofit2.http.Multipart @POST("profile/avatar") suspend fun avatar(@Header("Authorization") bearer: String, @retrofit2.http.Part image: okhttp3.MultipartBody.Part): UserDto
    @POST("captcha/challenge") suspend fun captcha(): CaptchaDto
    @POST("auth/email/register-code") suspend fun registerCode(@Body request: RegisterCodeRequest): EmailCodeResponse
    @POST("auth/email/register") suspend fun registerEmail(@Body request: RegisterEmailRequest): AuthResponse
    @POST("learning/ask")
    suspend fun askLearning(@Header("Authorization") bearer: String, @Body request: LearningQuestion): LearningAnswer
    @POST("learning/translate")
    suspend fun translateLearning(@Header("Authorization") bearer: String, @Body request: LearningTranslateRequest): LearningTranslateResponse
    @POST("learning/translate/submit")
    suspend fun translateSubmit(@Header("Authorization") bearer: String, @Body request: LearningTranslateSubmit): LearningTranslateSubmitResult

    @GET("verification/me")
    suspend fun campusIdentity(@Header("Authorization") bearer: String): CampusIdentityDto

    @POST("auth/email/request-code")
    suspend fun requestEmailCode(@Header("Authorization") bearer: String, @Body request: EmailCodeRequest): EmailCodeResponse

    @POST("auth/email/bind")
    suspend fun bindEmail(@Header("Authorization") bearer: String, @Body request: BindEmailRequest)

    @retrofit2.http.Multipart
    @POST("verification/requests")
    suspend fun submitVerification(
        @Header("Authorization") bearer: String,
        @retrofit2.http.PartMap fields: Map<String, @JvmSuppressWildcards okhttp3.RequestBody>,
        @retrofit2.http.Part material: okhttp3.MultipartBody.Part?,
    ): CampusApplicationDto

    @DELETE("verification/requests/{id}")
    suspend fun withdrawVerification(@Header("Authorization") bearer: String, @Path("id") id: String)

    // `auth/register`（纯用户名注册）**客户端已不再调用**：注册改成邮箱验证码
    // （docs/plan/18），声明与 DTO 一并删除，免得后人误以为还有这条路。后端接口保留未动。

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    // ── PC 扫码登录 ──
    // token 是扫出来的二维码内容（tonglu://qr-login?token=…，后端两种形态都认）。

    /** 读会话信息：确认页展示「是哪台电脑在请求登录」（过期返回 400） */
    @GET("auth/qr/ticket")
    suspend fun qrTicket(@Header("Authorization") bearer: String, @Query("token") token: String): QrTicketDto

    /** 确认授权该电脑登录。重复确认/过期都会 400 */
    @POST("auth/qr/confirm")
    suspend fun qrConfirm(@Header("Authorization") bearer: String, @Body request: QrTokenRequest)

    /**
     * 用户名可用性（匿名）。
     *
     * 🔴 调用方**必须防抖**（≥400ms）。后端就这个口的额度收得比登录还紧（20/分），
     * 每敲一个字符发一次会在几秒内把额度打爆，然后用户看到的是「服务暂时不可用」，
     * 而他只是在取名字。
     */
    @GET("auth/username/available")
    suspend fun usernameAvailable(@Query("username") username: String): UsernameAvailableDto

    /** 设置 / 修改用户名。改名后原派生 ID 仍可登录（后端存进 legacyUsername）。 */
    @PATCH("auth/username")
    suspend fun setUsername(
        @Header("Authorization") bearer: String,
        @Body request: SetUsernameRequest,
    ): UserDto

    /**
     * 修改昵称（displayName，登录态）。账号设置页用。
     * 与改名分开：昵称随时可改，用户名有自己的改名引导流程，两条路不要搅在一起。
     */
    @PATCH("auth/display-name")
    suspend fun setDisplayName(
        @Header("Authorization") bearer: String,
        @Body request: SetDisplayNameRequest,
    ): UserDto

    /** 用户明确选择「保留当前用户名」，之后不再弹引导。 */
    @POST("auth/username/skip-prompt")
    suspend fun skipUsernamePrompt(@Header("Authorization") bearer: String)

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout(@Body request: LogoutRequest)

    @GET("courses")
    suspend fun courses(@Header("Authorization") bearer: String): List<CourseDto>

    @POST("courses")
    suspend fun createCourse(
        @Header("Authorization") bearer: String,
        @Body request: CreateCourseRequest,
    ): CourseDto

    @DELETE("courses/{id}")
    suspend fun deleteCourse(@Header("Authorization") bearer: String, @Path("id") id: String)

    /**
     * 清空本人全部课表。
     * 必须走后端：登录用户的课表在服务端也有一份，只删本地的话
     * 下次 refreshAll() 会把课表原样拉回来 —— 用户会以为「删了没用」。
     */
    @DELETE("courses")
    suspend fun clearCourses(@Header("Authorization") bearer: String): ClearCoursesResponse

    /** 教务导入：整份课表批量写入 */
    @POST("courses/import")
    suspend fun importCourses(
        @Header("Authorization") bearer: String,
        @Body request: ImportCoursesRequest,
    ): ImportCoursesResponse

    /**
     * 云端个人档案（课表 + 成绩 + 学期设置）整包快照。
     * 后端只校验体积、不解释内容，所以加字段不用改服务端；
     * 冲突用乐观锁 baseVersion（见 PutProfileRequest / 后端 sync.service）。
     */
    @GET("sync/profile")
    suspend fun getProfile(@Header("Authorization") bearer: String): ProfileSnapshotDto

    @retrofit2.http.PUT("sync/profile")
    suspend fun putProfile(
        @Header("Authorization") bearer: String,
        @Body request: PutProfileRequest,
    ): ProfilePutResponse

    /** 清空云端档案（本机数据不动；下次上传会重新建立快照）。 */
    @DELETE("sync/profile")
    suspend fun deleteProfile(@Header("Authorization") bearer: String)

    /**
     * 云端阅读数据（进度 / 书签 / 笔记）整包快照。和课表档案是**两张表、两个版本号** ——
     * 读书进度写入频率高得多，共用一份 version 会互相撞 409。
     */
    @GET("sync/reading")
    suspend fun getReading(@Header("Authorization") bearer: String): ReadingSnapshotDto

    @retrofit2.http.PUT("sync/reading")
    suspend fun putReading(
        @Header("Authorization") bearer: String,
        @Body request: PutReadingRequest,
    ): ReadingPutResponse

    /** 清空云端阅读数据（本机进度 / 书签 / 笔记不动）。 */
    @DELETE("sync/reading")
    suspend fun deleteReading(@Header("Authorization") bearer: String)

    /**
     * 云端作答记录整包快照（docs/plan/57 §5.3 T5）。与课表 / 阅读同样是**另一张表、另一个版本号** ——
     * 做题的写入频率与阅读进度是两个节奏，共用一份 version 会互相撞 409。
     */
    @GET("sync/practice")
    suspend fun getPractice(@Header("Authorization") bearer: String): PracticeSnapshotDto

    @retrofit2.http.PUT("sync/practice")
    suspend fun putPractice(
        @Header("Authorization") bearer: String,
        @Body request: PutPracticeRequest,
    ): PracticePutResponse

    /** 清空云端作答记录（本机记录不动）。 */
    @DELETE("sync/practice")
    suspend fun deletePractice(@Header("Authorization") bearer: String)

    /**
     * 作答**增量**拉取（B6，docs/plan/56 §3.3）。
     *
     * `cursor` 是服务端发放的序号（不是手机时间）：传上次拿到的 `nextCursor`，
     * 服务端只回那之后的事件。`items` 里的 `deletedAt` 非空表示这是墓碑，本机要删掉对应记录。
     */
    @GET("sync/practice/changes")
    suspend fun getPracticeChanges(
        @Header("Authorization") bearer: String,
        @Query("cursor") cursor: Long,
        @Query("limit") limit: Int = 50,
    ): PracticeChangesDto

    /**
     * 批量追加作答事件（B6）。**幂等**：同一条 `eventId` 重传不会产生第二条 ——
     * 「超时以为失败、其实写成功」的重传是常态。
     */
    @POST("sync/practice/events")
    suspend fun postPracticeEvents(
        @Header("Authorization") bearer: String,
        @Body request: PostPracticeEventsRequest,
    ): PracticeEventsAckDto

    /**
     * 云端学习计划快照（docs/plan/56 §6.2，B5 A4）。**只同步 active 计划** ——
     * draft 是本机的 AI 草稿，不属于「用户确认过的安排」，不该跑到别的设备上。
     */
    @GET("sync/plan")
    suspend fun getPlan(@Header("Authorization") bearer: String): PlanSnapshotDto

    @retrofit2.http.PUT("sync/plan")
    suspend fun putPlan(
        @Header("Authorization") bearer: String,
        @Body request: PutPlanRequest,
    ): PlanPutResponse

    /** 清空云端计划（本机计划不动）。 */
    @DELETE("sync/plan")
    suspend fun deletePlan(@Header("Authorization") bearer: String)

    /**
     * 资料正文的实时通道。这两个接口**不需要登录**：内容是 GitHub 上的公开 README，
     * 没有任何用户数据，加鉴权只会让首次启动多卡一步。
     */
    @GET("learning/feed/meta")
    suspend fun learningFeedMeta(): LearningFeedMeta
    @GET("learning/feed/catalog")
    suspend fun learningFeedCatalog(): RemoteLearningCatalog

    @GET("learning/feed/article/{id}")
    suspend fun learningFeedArticle(@Path("id") id: String): LearningFeedBody

    /**
     * 国家考证年度表。不登录可读：它是公开的考试日程，不是用户数据。
     * 有了它，官方每年改考试日期不用重发客户端（#5b）。
     */
    @GET("exam/calendar")
    suspend fun examCalendar(): RemoteExamCalendar

    /**
     * 竞赛目录。不登录可读 —— 公开赛事信息，不是用户数据（照 `exam/calendar`）。
     *
     * 只有已发布的赛事、且已核实的期限会下发；未核实的日期连字段都不来，
     * 所以客户端**不可能**拿一个待核实的日期去做倒计时。
     */
    @GET("competitions")
    suspend fun competitions(@Query("schoolId") schoolId: String? = null): RemoteCompetitionCatalog

    /** 我的竞赛订阅（按登录用户隔离）。 */
    @GET("competitions/subscriptions/mine")
    suspend fun mySubscriptions(@Header("Authorization") bearer: String): RemoteSubscriptionList

    /**
     * 订阅 / 取消订阅（同一端点，幂等）。
     *
     * 未核实的期限服务端会回 400「未知期限不提醒」—— 客户端在这里必须把话说出来，
     * 不能吞掉变成「点了没反应」。
     */
    @retrofit2.http.PUT("competitions/subscriptions/mine")
    suspend fun setSubscription(
        @Header("Authorization") bearer: String,
        @Body request: SubscriptionRequest,
    ): RemoteSubscriptionList

    /**
     * 云端词库清单。不登录可读：词库是公开学习资源，跟以前打包进 APK 的那份内容一致。
     * 客户端拿它显示「换词书」，用户挑中哪本再单独下载。
     */
    @GET("english/books")
    suspend fun englishBooks(): List<EnglishBookDto>

    /**
     * 下载一本词库（gzip 的 jsonl，1~4MB）。
     * `@Streaming` 必须有：没有它 Retrofit 会把整个响应读完再回调，
     * 4MB 的文件在弱网下就是「点了没反应」，而我们要边下边写进度。
     */
    @retrofit2.http.Streaming
    @GET("english/books/{id}/download")
    suspend fun downloadEnglishBook(@Path("id") id: String): okhttp3.ResponseBody

    /**
     * 云端独立题库清单（docs/plan/57 §5.3 T4）。不登录可读。
     *
     * 只回元数据、**不回题面** —— 列表页只要标题/题数/来源说明；
     * 题面走 [downloadQuestionBank] 按需取（一份几十 KB，一次列 20 份是浪费）。
     */
    @GET("question-banks")
    suspend fun questionBanks(@Query("subject") subject: String? = null): List<QuestionBankDto>

    /**
     * 下载一份题库 JSON。`@Streaming` 的理由同词库：要边下边写进度，
     * 且不能把整个响应先读进内存。落盘后按 sha256 校验（见 QuestionBankStore）。
     */
    @retrofit2.http.Streaming
    @GET("question-banks/{id}/download")
    suspend fun downloadQuestionBank(@Path("id") id: String): okhttp3.ResponseBody

    /**
     * **我的题库清单**（`docs/plan/58` §4）：只回当前登录用户自己上传、标为 PRIVATE 的那批。
     *
     * 与 [questionBanks] 是两条互不相干的路 —— 公开口只出 `scope=PUBLIC`，
     * 这里只出 `scope=PRIVATE AND ownerId=自己`。**必须带 Bearer**（服务端 `JwtAuthGuard`）。
     */
    @GET("me/question-banks")
    suspend fun myQuestionBanks(@Header("Authorization") bearer: String): List<QuestionBankDto>

    /**
     * 下载一份**自己的**私有题库。与公开下载同一套落盘/校验路径（见 [QuestionBankStore]），
     * 只是地址换成 `/me` 那条 —— 私有题库 id 在公开口一律 404。
     */
    @retrofit2.http.Streaming
    @GET("me/question-banks/{id}/download")
    suspend fun downloadMyQuestionBank(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): okhttp3.ResponseBody

    /**
     * 学习资料包清单（可加入学习的内容单元，docs/plan/44 §六）。
     *
     * 跟词库一样**不要求登录** —— 这是公开学习资源，拿不到任何用户数据。
     * 资料包和词库是两回事：词库只有一份词表文件，资料包是「manifest + 音频 + 句子时间轴 + 题目」
     * 的目录，下载下来就能直接听、跟读、做题（44 §七 A）。
     */
    @GET("learning/packs")
    suspend fun learningPacks(): List<LearningPackDto>

    /** 取 manifest（含 assets 的 sha256/bytes、segments 时间轴、题目）。不传 version 拿最新版。 */
    @GET("learning/packs/{packageId}/manifest")
    suspend fun learningPackManifest(
        @Path("packageId") packageId: String,
        @Query("version") version: Int? = null,
    ): okhttp3.ResponseBody

    /**
     * 下载包内资产（现在是音频，将来可能多几个）。
     * `@Streaming` 跟词库同理：不流式的话几 MB 音频在弱网下就是「点了没反应」，
     * 而且我们要边下边算 sha256、边报进度。
     */
    @retrofit2.http.Streaming
    @GET("learning/packs/{packageId}/assets/{assetId}")
    suspend fun downloadLearningPackAsset(
        @Path("packageId") packageId: String,
        @Path("assetId") assetId: String,
        @Query("version") version: Int? = null,
    ): okhttp3.ResponseBody

    @GET("posts")
    suspend fun posts(@Query("category") category: String? = null): List<PostDto>

    @POST("posts")
    suspend fun createPost(
        @Header("Authorization") bearer: String,
        @Body request: CreatePostRequest,
    ): PostDto

    /** 帖子详情（公开）。被隔离/被下线的内容服务端返回 404。 */
    @GET("posts/{id}")
    suspend fun postDetail(@Path("id") id: String): PostDetailDto

    /** 楼层列表：顶层按楼层号升序，每层带自己的楼中楼。 */
    @GET("posts/{id}/comments")
    suspend fun postComments(@Path("id") id: String, @Query("cursor") cursor: String? = null, @Query("order") order: String = "oldest"): List<PostCommentDto>
    @GET("posts/{id}/comment-likes") suspend fun commentLikes(@Header("Authorization") bearer: String, @Path("id") id: String): List<String>
    @retrofit2.http.PUT("posts/{id}/comments/{commentId}/like") suspend fun likeComment(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("commentId") commentId: String): LikeResponse
    @DELETE("posts/{id}/comments/{commentId}/like") suspend fun unlikeComment(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("commentId") commentId: String): LikeResponse
    @DELETE("posts/{id}/comments/{commentId}") suspend fun deleteComment(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("commentId") commentId: String)
    @POST("posts/{id}/comments/{commentId}/reports") suspend fun reportComment(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("commentId") commentId: String, @Body reason: CreateReportRequest)

    /** 发回复。不传 parentId 就是新开一层楼。 */
    @POST("posts/{id}/comments")
    suspend fun createComment(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body request: CreateCommentRequest,
    ): PostCommentDto

    @POST("posts/{id}/reports")
    suspend fun reportPost(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body request: CreateReportRequest,
    )

    /** 我发布的内容（含被隔离 / 被拒绝的 —— 作者始终能看到自己的） */
    @GET("posts/mine")
    suspend fun myPosts(@Header("Authorization") bearer: String): List<MyPostDto>

    // ---- 帖子级点赞 / 收藏（2026-10-13 全链路；与评论点赞同口径）----

    @GET("posts/{id}/like") suspend fun postLikeState(@Header("Authorization") bearer: String, @Path("id") id: String): PostLikeState
    @retrofit2.http.PUT("posts/{id}/like") suspend fun likePost(@Header("Authorization") bearer: String, @Path("id") id: String): LikeResponse
    @DELETE("posts/{id}/like") suspend fun unlikePost(@Header("Authorization") bearer: String, @Path("id") id: String): LikeResponse
    @GET("posts/{id}/favorite") suspend fun postFavoriteState(@Header("Authorization") bearer: String, @Path("id") id: String): FavoriteState
    @retrofit2.http.PUT("posts/{id}/favorite") suspend fun favoritePost(@Header("Authorization") bearer: String, @Path("id") id: String): FavoriteState
    @DELETE("posts/{id}/favorite") suspend fun unfavoritePost(@Header("Authorization") bearer: String, @Path("id") id: String): FavoriteState
    @GET("posts/favorites") suspend fun myFavorites(@Header("Authorization") bearer: String): List<FavoritePostDto>

    // ---------------------------------------------------------------- 等级（docs/plan/66）
    //
    // 🔴 `me/level` 返回**本人**全量（含 TL）；`users/{id}/level-card` **不含 TL**
    // （红线 8：TL 是风控面，公开等于给刷子画靶子）。别把两者混用一个 DTO。

    /** 我的等级：EXP 双线、Lv、TL 及逐条件达成态、宽限截止。仅本人。 */
    @GET("me/level")
    suspend fun myLevel(@Header("Authorization") bearer: String): MyLevelDto

    /** 学习计数上报。幂等、服务端按日截断，只收数字。 */
    @POST("me/level/learn-report")
    suspend fun reportLearn(
        @Header("Authorization") bearer: String,
        @Body request: LearnReportRequest,
    ): LearnReportResult

    /** 关闭/重开「参与成长等级」。关闭后学习通道停收，已获得的保留。 */
    // 🔴 字段名是 `optedOut` 不是 `optOut`（后端 GrowthOptOutBody 就叫这个名字）。
    // 写错编译器拦不住，只会换来一个 400 —— 而调用方当时还把错误藏起来了，
    // 表现就是「开关点了没反应」（BUGS A28）。改字段名时请同时跑 scripts/audit_api_contract.py。
    @POST("me/level/opt-out")
    suspend fun setGrowthOptOut(
        @Header("Authorization") bearer: String,
        @Body body: Map<String, Boolean>,
    ): Map<String, Boolean>

    @GET("me/notices")
    suspend fun notices(
        @Header("Authorization") bearer: String,
        @Query("take") take: Int = 30,
    ): NoticesDto

    @POST("me/notices/{id}/read")
    suspend fun readNotice(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): Map<String, Boolean>

    /** TL 变动记录（含人类可读原因）。 */
    @GET("me/trust-logs")
    suspend fun trustLogs(@Header("Authorization") bearer: String): List<TrustLogDto>

    /** 他人等级卡：Lv + 进度，**没有 TL 字段**。 */
    @GET("users/{id}/level-card")
    suspend fun levelCard(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): LevelCardDto

    /** 对被处置的内容提交申诉 */
    @POST("posts/{id}/appeals")
    suspend fun submitAppeal(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body request: CreateAppealRequest,
    ): PostAppealDto

    /** 查看自己对某条内容的申诉进展；没申诉过时服务端返回 null */
    @GET("posts/{id}/appeal")
    suspend fun myAppeal(@Header("Authorization") bearer: String, @Path("id") id: String): PostAppealDto?

    /** 编辑自己的帖子（改标题/正文；编辑后重新进机审，服务端口径） */
    @retrofit2.http.PATCH("posts/{id}")
    suspend fun updatePost(@Header("Authorization") bearer: String, @Path("id") id: String, @Body body: UpdatePostRequest): PostDetailDto

    /** 编辑自己的回复（楼中楼与楼层同口径） */
    @retrofit2.http.PATCH("posts/{id}/comments/{commentId}")
    suspend fun updateComment(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("commentId") commentId: String, @Body body: UpdateCommentRequest): PostCommentDto

    /** 问答帖采纳答案（仅发帖人；允许改选） */
    @POST("posts/{id}/accept/{commentId}")
    suspend fun acceptAnswer(@Header("Authorization") bearer: String, @Path("id") id: String, @Path("commentId") commentId: String): AcceptAnswerResponse

    /** 撤销采纳（回到待解答） */
    @DELETE("posts/{id}/accept")
    suspend fun unacceptAnswer(@Header("Authorization") bearer: String, @Path("id") id: String): AcceptAnswerResponse

    @GET("clubs")
    suspend fun clubs(): List<ClubDto>

    /**
     * 我加入的社团（含我创建的）+ 我待审的申请。
     *
     * 界面据此显示「已加入 / 申请中 / 我创建的」，而不是从别处推——服务端算好的口径
     * 与审批流一致（`memberships` + `clubJoinRequest`），客户端自己拼会漂移。
     */
    @GET("clubs/mine")
    suspend fun clubMine(@Header("Authorization") bearer: String): ClubMineDto

    @POST("clubs")
    suspend fun createClub(
        @Header("Authorization") bearer: String,
        @Body request: CreateClubRequest,
    ): ClubDto

    @POST("clubs/{id}/join")
    suspend fun joinClub(@Header("Authorization") bearer: String, @Path("id") id: String)

    /**
     * 社团待审申请（**仅创建者**，非创建者服务端返回 403）。
     *
     * 这是「团长管理入口」的数据源 —— 普通成员看不到这个入口（也拿不到数据），
     * 两个入口从一开始就是分开的，不是靠前端藏。
     */
    @GET("clubs/{id}/requests")
    suspend fun clubJoinRequests(@Header("Authorization") bearer: String, @Path("id") id: String): List<ClubJoinRequestDto>

    /** 审批申请（仅创建者）。服务端对非 PENDING 的申请返回 409（幂等保护，重复点击不会双入伙）。 */
    @POST("clubs/{id}/requests/{requestId}/review")
    suspend fun reviewClubJoinRequest(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Path("requestId") requestId: String,
        @Body body: ReviewClubRequestRequest,
    ): ClubJoinRequestDecisionDto

    // ---------------------------------------------------------------- 社团活动（活动即帖子）

    /**
     * 某个社团的活动（公开）。只返回**对外可见**的（帖子已上架 + 机审 CLEAR）。
     * 🔴 所以「列表里没有」既可能是没办过，也可能是被机审隔离了 —— 界面上不做两者区分推断。
     */
    @GET("clubs/{id}/activities")
    suspend fun clubActivities(@Path("id") id: String): List<ClubActivityDto>

    // `GET clubs/activities/upcoming`（全站近期活动）**本轮不接**：没有调用点的客户端方法
    // 就是死代码。等真有全站活动入口那一轮再加回来（服务端接口本来就在）。

    /** 我的报名状态 map（刷新后恢复按钮态）。 */
    @GET("clubs/rsvps/mine")
    suspend fun myClubRsvps(@Header("Authorization") bearer: String): ClubRsvpMapDto

    /** 报名 / 改意向 / 取消（社团成员限定；服务端对非成员返回 403）。 */
    @PUT("clubs/activities/{activityId}/rsvp")
    suspend fun rsvpClubActivity(
        @Header("Authorization") bearer: String,
        @Path("activityId") activityId: String,
        @Body body: ClubRsvpRequest,
    ): ClubRsvpResponse

    // ---------------------------------------------------------------- 社团频道（Telegram 频道模式）

    /** 频道列表（首次访问时服务端惰性创建默认频道：公告 + 闲聊） */
    @GET("clubs/{id}/channels")
    suspend fun clubChannels(@Header("Authorization") bearer: String, @Path("id") id: String): List<ClubChannelDto>

    /** 频道消息：`before` = 往前翻历史（排他游标），`after` = 增量补拉；都不带 = 最新一页 */
    @GET("clubs/channels/{id}/messages")
    suspend fun clubMessages(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Query("before") before: String? = null,
        @Query("after") after: String? = null,
        @Query("limit") limit: Int? = null,
    ): ClubMessagesDto

    @POST("clubs/channels/{id}/messages")
    suspend fun sendClubMessage(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
        @Body body: SendChannelMessageRequest,
    ): ClubMessageDto

    /** 撤回自己的消息（软删；服务端幂等） */
    @DELETE("clubs/messages/{seq}")
    suspend fun deleteClubMessage(@Header("Authorization") bearer: String, @Path("seq") seq: String)

    // ---------------------------------------------------------------- 社团帖（C0 契约 §3.2 冻结，C1 服务端已实施）
    //
    // 🔴 与公共帖路由是**两条路**：`clubs/{clubId}/posts/{postId}` 走社团成员/年级矩阵，
    // `posts/{id}` 只有 PUBLIC。不能「拿公共详情 URL 打社团帖」——服务端 404 是契约行为，
    // 客户端也不要做任何降级重试去别的入口碰同一个 id。
    // 列表/详情/评论/点赞收藏的响应形状与公共帖一致（同一 PostsService 主干），
    // 所以直接复用 `PostDto`/`PostDetailDto`/`PostCommentDto`/`LikeResponse`；
    // 作者额外带 realName/cohortYear（社团身份投影，Gson 忽略公共帖里不存在的字段）。

    /**
     * 频道帖子流（跨年级讨论 / 年级专区）。`channelId` 不传 = 全频道混合流。
     * cursor 分页：传上一页最后一条的 id（服务端 skip:1 + cursor 语义，与公共流不同源）。
     */
    @GET("clubs/{id}/posts")
    suspend fun clubPosts(
        @Header("Authorization") bearer: String,
        @Path("id") clubId: String,
        @Query("channelId") channelId: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int? = null,
    ): List<PostDto>

    /** 发社团讨论（visibility 只允许 MEMBERS/COHORT，由频道类型决定，不由用户选）。 */
    @POST("clubs/{id}/posts")
    suspend fun createClubPost(
        @Header("Authorization") bearer: String,
        @Path("id") clubId: String,
        @Body request: CreateClubPostRequest,
    ): PostDetailDto

    /** 社团帖详情（成员/年级矩阵通过才返回，否则 404，不泄露存在性）。 */
    @GET("clubs/{id}/posts/{postId}")
    suspend fun clubPostDetail(
        @Header("Authorization") bearer: String,
        @Path("id") clubId: String,
        @Path("postId") postId: String,
    ): PostDetailDto

    @GET("clubs/{id}/posts/{postId}/comments")
    suspend fun clubPostComments(
        @Header("Authorization") bearer: String,
        @Path("id") clubId: String,
        @Path("postId") postId: String,
        @Query("cursor") cursor: String? = null,
        @Query("order") order: String = "oldest",
    ): List<PostCommentDto>

    @POST("clubs/{id}/posts/{postId}/comments")
    suspend fun createClubComment(
        @Header("Authorization") bearer: String,
        @Path("id") clubId: String,
        @Path("postId") postId: String,
        @Body request: CreateCommentRequest,
    ): PostCommentDto

    @GET("clubs/{id}/posts/{postId}/like") suspend fun clubPostLikeState(@Header("Authorization") bearer: String, @Path("id") clubId: String, @Path("postId") postId: String): PostLikeState
    @retrofit2.http.PUT("clubs/{id}/posts/{postId}/like") suspend fun clubLikePost(@Header("Authorization") bearer: String, @Path("id") clubId: String, @Path("postId") postId: String): LikeResponse
    @DELETE("clubs/{id}/posts/{postId}/like") suspend fun clubUnlikePost(@Header("Authorization") bearer: String, @Path("id") clubId: String, @Path("postId") postId: String): LikeResponse
    @GET("clubs/{id}/posts/{postId}/favorite") suspend fun clubPostFavoriteState(@Header("Authorization") bearer: String, @Path("id") clubId: String, @Path("postId") postId: String): FavoriteState
    @retrofit2.http.PUT("clubs/{id}/posts/{postId}/favorite") suspend fun clubFavoritePost(@Header("Authorization") bearer: String, @Path("id") clubId: String, @Path("postId") postId: String): FavoriteState
    @DELETE("clubs/{id}/posts/{postId}/favorite") suspend fun clubUnfavoritePost(@Header("Authorization") bearer: String, @Path("id") clubId: String, @Path("postId") postId: String): FavoriteState

    /** 举报社团帖（能看到才举报：服务端同一矩阵；记录进同一张 PostReport 表）。 */
    @POST("clubs/{id}/posts/{postId}/reports")
    suspend fun clubReportPost(
        @Header("Authorization") bearer: String,
        @Path("id") clubId: String,
        @Path("postId") postId: String,
        @Body request: CreateReportRequest,
    )

    // ---------------------------------------------------------------- 内置网盘
    // 全部走后端，但**字节不走后端**：服务端只签发预签名 URL，
    // 上传是 App 直接 PUT 到 R2，下载是 App 拿短期链接直接 GET R2。
    // 以前不是这样 —— 那时候文件在**用户自己的阿里云盘**上，把直链交给手机
    // 等于让每个下载者的 IP 直接落到云盘面前（官方禁止「帐号被多 IP 访问」），
    // 所以只能让服务器把字节捞回来再转发。换成 R2（我们自己的桶）之后这条约束消失。
    // 推导见 docs/plan/31 与 data/NetdiskCloud.kt。

    @GET("netdisk/status")
    suspend fun netdiskStatus(@Header("Authorization") bearer: String): NetdiskStatusDto

    @GET("netdisk/objects")
    suspend fun netdiskObjects(@Header("Authorization") bearer: String): NetdiskListDto

    /** 第一步：申请直传链接。服务端只落一条 PENDING 行 + 签名，不碰字节。 */
    @POST("netdisk/uploads")
    suspend fun netdiskBeginUpload(
        @Header("Authorization") bearer: String,
        @Body body: NetdiskBeginUploadDto,
    ): NetdiskUploadTicketDto

    /** 第三步：确认。服务端会 HEAD 存储复核真实大小；**幂等**，断线可直接重试。 */
    @POST("netdisk/uploads/{id}/complete")
    suspend fun netdiskCompleteUpload(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): NetdiskObjectDto

    /** 取短期直链（10 分钟）。字节自己从存储取，见 `netdiskGetFromStorage`。 */
    @GET("netdisk/objects/{id}/url")
    suspend fun netdiskObjectUrl(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): NetdiskUrlDto

    /**
     * 确保这条文件有分享链接与提取码（幂等）。
     * 正常情况下 `netdiskCompleteUpload` 已经带回来了，这里只兜底。
     */
    @POST("netdisk/objects/{id}/share")
    suspend fun netdiskEnsureShare(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): NetdiskShareInfoDto

    /**
     * 换一把新钥匙：新链接 + 新提取码，**旧链接立刻失效**。
     * 链接发错人（或发到群里想收回）时用；只动服务端的令牌，不碰存储上的文件。
     */
    @POST("netdisk/objects/{id}/share/renew")
    suspend fun netdiskRenewShare(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): NetdiskShareInfoDto

    @DELETE("netdisk/objects/{id}")
    suspend fun netdiskDelete(@Header("Authorization") bearer: String, @Path("id") id: String)
}

/**
 * 云端词库清单里的一本（后端 `english/books`）。
 *
 * 字段全可空 + 带默认值：Gson 是绕开构造器直接塞字段的，服务端以后加字段不会把这里打崩。
 * 校验集中在 `EnglishBookStore.toBook()` —— **缺 id / 缺词数的一本直接丢掉**，
 * 因为少一本词库用户还能学，多一本「点了没反应」的词库就很难解释了。
 */
data class EnglishBookDto(
    val id: String? = null, val name: String? = null, val category: String? = null,
    val count: Int? = null, val revision: String? = null, val bytes: Int? = null,
    val sha256: String? = null, val updatedAt: String? = null,
    // 出处与许可**三项分开**（plan §5 第 3 条）。服务端 2026-10-11 起一起下发；
    // 老服务端只给 `source` → 后两项为空，界面要如实显示「未登记」。
    val source: String? = null, val license: String? = null, val sourceUrl: String? = null,
)

/**
 * 云端题库清单里的一条（后端 `question-banks`，docs/plan/57 §5.3 T4）。
 *
 * 字段全可空 + 带默认值，理由同 `EnglishBookDto`：Gson 绕开构造器直接塞字段，
 * 服务端以后加字段不能把这里打崩。**校验集中在 `QuestionBankStore`** ——
 * 缺 id / 缺来源说明的一条直接丢掉（打标红线：说不清出处的题库不上架）。
 */
data class QuestionBankDto(
    val id: String? = null, val subject: String? = null, val level: String? = null,
    val title: String? = null, val sourceNote: String? = null, val license: String? = null,
    val sourceUrl: String? = null, val version: Int? = null, val count: Int? = null,
    val bytes: Int? = null, val sha256: String? = null,
    val publishedAt: String? = null, val updatedAt: String? = null,
)

/**
 * 学习资料包清单里的一条（后端 `learning/packs`）。
 *
 * 字段全可空 + 带默认值，理由同 `EnglishBookDto`：Gson 绕开构造器直接塞字段，
 * 服务端以后加字段不能把这里打崩。**校验集中在 `LearningPacks.toPack()`** ——
 * 缺 packageId / 缺 version / 没有 audio 资产的一条直接丢掉，不给用户看「点了没反应」的卡片。
 *
 * `bytes` 是整包（manifest + 全部资产）的总和，界面用它显示「约 2.1MB」；
 * 真正下载时逐资产按 manifest 里各自的 bytes/sha256 校验，不靠这个总数。
 */
data class LearningPackDto(
    val packageId: String? = null, val version: Int? = null,
    val title: String? = null, val summary: String? = null,
    val language: String? = null, val level: String? = null,
    val author: String? = null, val sourceUrl: String? = null,
    val license: String? = null, val licenseUrl: String? = null,
    val attribution: String? = null,
    val capabilities: List<String>? = null,
    val bytes: Int? = null, val sha256: String? = null, val updatedAt: String? = null,
)

object ApiFactory {
    /**
     * 直连存储（R2）用的裸客户端。
     *
     * 为什么不复用 Retrofit 那一条：它的 `baseUrl` 是我们自己的后端，而直传/直下的目标是
     * 预签名 URL 里的**绝对地址**。用 Retrofit 得为每个请求改 baseUrl，还会带上我们的拦截器。
     *
     * 两个刻意的选择：
     * - **不打印完整 URL**。预签名链接本身就是一个 capability（拿到就能读写那个对象），
     *   `HttpLoggingInterceptor` 的 BASIC 级别正好会把整条 URL（含 `X-Amz-Signature`）打进日志。
     *   这里换成只打 `scheme://host/路径`，排查「传到哪了」够用，签名不外泄。
     * - **读写超时给到 90 秒**（默认是 12/10 秒）。100MB 的文件在校园网里推一分钟很正常，
     *   按默认值会在中途被自己掐断，而症状是「传到一半就失败」，很难联想到超时配置。
     */
    val storageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val safe = request.newBuilder()
                    .url("${request.url.scheme}://${request.url.host}${request.url.encodedPath}")
                    .build()
                if (BuildConfig.DEBUG) android.util.Log.d("TongluStorage", "${safe.method} ${safe.url}")
                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    fun create(readTimeoutSeconds: Long = 12): TongluApi = createService(TongluApi::class.java, readTimeoutSeconds)

    /** 独立模块共用相同超时和安全日志，不复制一套登录客户端。 */
    fun <T> createService(type: Class<T>, readTimeoutSeconds: Long = 12): T {
        val logger = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logger)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(type)
    }
}

data class LocalSession(
    val token: String,
    val username: String,
    val displayName: String,
    /** 后端用户 id；老版本存下的 session 里没有这个字段，读出来是空串 */
    val userId: String = "",
    val refreshToken: String? = null,
    /** 每次登录重新生成；令牌轮换不改变它，用于隔离换账号前尚未完成的请求。 */
    val sessionId: String = UUID.randomUUID().toString(),
    val avatarUrl: String? = null,
)

class SessionStore(context: Context) {
    private val app = context.applicationContext
    private fun openPreferences(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            SESSION_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val preferences: android.content.SharedPreferences
        get() = cachedPreferences ?: openPreferences().also { cachedPreferences = it }

    fun read(): LocalSession? = synchronized(STORE_LOCK) {
        val token = preferences.getString("token", null) ?: return@synchronized null
        LocalSession(
            token = token,
            username = preferences.getString("username", "").orEmpty(),
            displayName = preferences.getString("displayName", "同学").orEmpty(),
            userId = preferences.getString("userId", "").orEmpty(),
            avatarUrl = preferences.getString("avatarUrl", null),
            refreshToken = preferences.getString("refreshToken", null)?.takeIf { it.isNotBlank() },
            sessionId = preferences.getString("sessionId", null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString(),
        )
    }

    fun save(session: LocalSession) = synchronized(STORE_LOCK) {
        try {
            writeSession(session)
        } catch (bad: Exception) {
            if (!hasBrokenKeyset(bad)) {
                throw IOException("服务器已验证账号，但本机无法保存登录状态，请解锁设备并检查存储空间后重试", bad)
            }
            // Re-login supplied a fresh server credential. Replace only the broken session
            // container, never the shared master key, academic data, or personal AI settings.
            android.util.Log.w("TongluSession", "重建损坏的登录凭据容器：${bad::class.java.simpleName}")
            cachedPreferences = null
            if (!app.deleteSharedPreferences(SESSION_FILE)) {
                throw IOException("本机旧登录凭据无法重建，请重启应用后再登录", bad)
            }
            try { writeSession(session) }
            catch (retry: Exception) {
                throw IOException("账号验证成功，但本机安全存储仍不可用，请解锁设备后重试", retry)
            }
        }
    }

    private fun writeSession(session: LocalSession) {
        val saved = preferences.edit()
            .putString("token", session.token)
            .putString("username", session.username)
            .putString("displayName", session.displayName)
            .putString("userId", session.userId)
            .putString("avatarUrl", session.avatarUrl)
            .putString("refreshToken", session.refreshToken)
            .putString("sessionId", session.sessionId)
            .commit()
        if (!saved) throw IOException("登录凭据未能保存，请检查设备存储空间")
    }

    fun clear() = synchronized(STORE_LOCK) {
        if (!preferences.edit().clear().commit()) throw IOException("登录凭据未能清除，请重试")
    }

    private fun hasBrokenKeyset(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any {
            it is javax.crypto.AEADBadTagException || it is javax.crypto.BadPaddingException ||
                it.javaClass.simpleName == "InvalidProtocolBufferException" ||
                (it is java.security.GeneralSecurityException &&
                    it.message?.contains("decryption failed", ignoreCase = true) == true)
        }

    /**
     * 当前身份标识，用来给「本机数据」分桶（课表缓存、成绩存档）。
     *
     * 为什么必须有这个：这些缓存原本是**设备级**的，A 退出、B 登录会看到 A 的课表；
     * 而且退出登录只清了令牌，课表还留在磁盘上，重启 App 又被读回来 ——
     * 用户表达为「退出账号了课表怎么还删不掉」。
     */
    fun ownerKey(): String {
        val session = runCatching { read() }.getOrNull() ?: return LOCAL_OWNER
        return session.userId.takeIf { it.isNotBlank() }
            ?: session.username.takeIf { it.isNotBlank() }
            ?: LOCAL_OWNER
    }

    companion object {
        private const val SESSION_FILE = "tonglu_session"
        // Widgets and the foreground login must share one keyset initialization and repair lock.
        private val STORE_LOCK = Any()
        private var cachedPreferences: android.content.SharedPreferences? = null
        const val LOCAL_OWNER = "local"

        /** 给不需要自己 new 一个加密存储的地方用（提醒服务、开机重排） */
        fun currentOwner(context: Context): String =
            runCatching { SessionStore(context).ownerKey() }.getOrDefault(LOCAL_OWNER)
    }
}
