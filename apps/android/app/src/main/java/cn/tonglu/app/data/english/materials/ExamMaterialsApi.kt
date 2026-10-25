package cn.tonglu.app.data.english.materials

import cn.tonglu.app.data.ApiFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 考试材料的 Retrofit 接口。
 *
 * 为什么不挂在 TongluApi 上：契约（docs/english/exam-entry-contract.md）要求 B 不改共享
 * 调用点，而 [ApiFactory.createService] 是公开泛型——自己声明接口、共用同一个 Retrofit
 * 基座（超时/日志/BaseURL 一致），两边零耦合。
 *
 * 三个读接口服务端都**不要求登录**（与 learning/feed 同口径）：目录是公开学习资料清单，
 * 考试入口只是同一批文章的另一种浏览方式。
 */
interface ExamMaterialsApi {
    @GET("learning/exam/options")
    suspend fun options(): List<RemoteExamOption>

    @GET("learning/exam/materials")
    suspend fun materials(
        @Query("examType") examType: String? = null,
        @Query("recommended") recommended: String? = null,
        @Query("language") language: String? = null,
        @Query("readingType") readingType: String? = null,
    ): RemoteExamMaterialsPage

    @GET("learning/exam/materials/{articleId}")
    suspend fun detail(@Path("articleId") articleId: String): RemoteExamMaterialDetail
}

/** 共享 Retrofit 基座的入口；与 TongluApi 同源（同 BaseURL/超时/日志），不另起炉灶。 */
object ExamMaterialsClient {
    val api: ExamMaterialsApi by lazy { ApiFactory.createService(ExamMaterialsApi::class.java) }
}
