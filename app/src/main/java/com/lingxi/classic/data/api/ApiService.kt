package com.lingxi.classic.data.api

import com.lingxi.classic.data.model.*
import okhttp3.MultipartBody
import retrofit2.http.*

/**
 * 凌汐弹幕网 API（手机经典版）
 * 契约以站点 server 源码静态提取文档为准；code==0 成功，其余看 message
 */
interface ApiService {

    companion object {
        const val BASE_URL = "https://lingxi.xiguastudio.top"
    }

    // ==================== 视频 ====================

    @GET("/api/v1/videos")
    suspend fun getVideos(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20,
        @Query("sort") sort: String? = null,
        @Query("partition") partition: String? = null,
        @Query("kind") kind: String? = null,
        @Query("keyword") keyword: String? = null,
        @Query("uid") uid: Long? = null,
        @Query("tag") tag: String? = null
    ): ApiResponse<PageData<Video>>

    @GET("/api/v1/videos/recommend")
    suspend fun getRecommend(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<Video>>

    /** 首页轮播：广告在前、用户海报在后；空数组时前端回落到推荐视频轮播 */
    @GET("/api/v1/ads")
    suspend fun getAds(@Query("position") position: String = "home"): ApiResponse<ItemList<AdItem>>

    @POST("/api/v1/promotions/{type}/{id}/impression")
    suspend fun reportPromotionImpression(@Path("type") type: String, @Path("id") id: Long): ApiResponse<ActionResult>

    @POST("/api/v1/promotions/{type}/{id}/click")
    suspend fun reportPromotionClick(@Path("type") type: String, @Path("id") id: Long): ApiResponse<ActionResult>

    @GET("/api/v1/videos/pinned")
    suspend fun getPinned(@Query("limit") limit: Int = 12): ApiResponse<ItemList<Video>>

    @GET("/api/v1/videos/ranking")
    suspend fun getRanking(): ApiResponse<PageData<Video>>

    @GET("/api/v1/videos/{id}")
    suspend fun getVideoDetail(@Path("id") id: Long): ApiResponse<VideoDetail>

    @GET("/api/v1/videos/{id}/related")
    suspend fun getRelatedVideos(@Path("id") id: Long, @Query("limit") limit: Int = 12): ApiResponse<ItemList<Video>>

    @GET("/api/v1/videos/{id}/viewers")
    suspend fun getViewers(@Path("id") id: Long): ApiResponse<ViewersResult>

    @POST("/api/v1/videos/{id}/view")
    suspend fun reportView(@Path("id") id: Long, @Body body: ViewReportRequest): ApiResponse<ViewReportResult>

    @POST("/api/v1/history")
    suspend fun reportHistory(@Body body: HistoryReportRequest): ApiResponse<ActionResult>

    @POST("/api/v1/presence")
    suspend fun presence(@Body body: Map<String, @JvmSuppressWildcards Long>): ApiResponse<PresenceResult>

    @GET("/api/v1/stats")
    suspend fun getSiteStats(): ApiResponse<SiteStats>

    @GET("/api/v1/partitions")
    suspend fun getPartitions(): ApiResponse<PageData<Partition>>

    @GET("/api/v1/tags/hot")
    suspend fun getHotTags(@Query("limit") limit: Int = 20): ApiResponse<ItemList<Map<String, @JvmSuppressWildcards Any>>>

    @GET("/api/v1/search")
    suspend fun search(
        @Query("q") keyword: String,
        @Query("type") type: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): ApiResponse<SearchResult>

    // ==================== 弹幕 ====================

    @GET("/api/v1/danmaku")
    suspend fun getDanmaku(
        @Query("videoId") videoId: Long,
        @Query("segments") segments: String? = null,
        @Query("all") all: Int? = null
    ): ApiResponse<DanmakuList>

    @POST("/api/v1/danmaku")
    suspend fun sendDanmaku(@Body body: DanmakuSendRequest): ApiResponse<ActionResult>

    // ==================== 评论 ====================

    @GET("/api/v1/videos/{id}/comments")
    suspend fun getComments(
        @Path("id") id: Long,
        @Query("sort") sort: String? = null,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 20
    ): ApiResponse<PageData<Comment>>

    @GET("/api/v1/comments/{id}/replies")
    suspend fun getCommentReplies(@Path("id") id: Long, @Query("page") page: Int = 1): ApiResponse<PageData<Comment>>

    @POST("/api/v1/videos/{id}/comments")
    suspend fun sendComment(@Path("id") id: Long, @Body body: CommentSendRequest): ApiResponse<ActionResult>

    @POST("/api/v1/comments/{id}/like")
    suspend fun likeComment(@Path("id") id: Long): ApiResponse<CommentLikeResult>

    @POST("/api/v1/comments/{id}/pin")
    suspend fun pinComment(@Path("id") id: Long, @Body body: PinRequest): ApiResponse<ActionResult>

    @DELETE("/api/v1/comments/{id}")
    suspend fun deleteComment(@Path("id") id: Long): ApiResponse<ActionResult>

    // ==================== 互动 ====================

    @POST("/api/v1/videos/{id}/like")
    suspend fun likeVideo(@Path("id") id: Long): ApiResponse<LikeResult>

    @POST("/api/v1/videos/{id}/coin")
    suspend fun coinVideo(@Path("id") id: Long, @Body body: CoinRequest): ApiResponse<CoinResult>

    @POST("/api/v1/videos/{id}/favorite")
    suspend fun favoriteVideo(@Path("id") id: Long, @Body body: FavoriteRequest): ApiResponse<FavoriteResult>

    @GET("/api/v1/videos/{id}/interaction")
    suspend fun getInteraction(@Path("id") id: Long): ApiResponse<ViewerInfo>

    // ==================== 收藏夹 ====================

    @GET("/api/v1/favorites/folders")
    suspend fun getMyFolders(): ApiResponse<ItemList<FavoriteFolder>>

    @POST("/api/v1/favorites/folders")
    suspend fun createFolder(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @GET("/api/v1/users/{uid}/folders")
    suspend fun getUserFolders(@Path("uid") uid: Long): ApiResponse<PageData<FavoriteFolder>>

    @GET("/api/v1/favorites/folders/{id}/items")
    suspend fun getFolderItems(
        @Path("id") id: Long,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 24
    ): ApiResponse<PageData<Video>>

    @DELETE("/api/v1/favorites/items/{videoId}")
    suspend fun unfavoriteVideo(@Path("videoId") videoId: Long): ApiResponse<ActionResult>

    // ==================== 历史 ====================

    @GET("/api/v1/history")
    suspend fun getHistory(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<HistoryItem>>

    @DELETE("/api/v1/history/{videoId}")
    suspend fun deleteHistoryItem(@Path("videoId") videoId: Long): ApiResponse<ActionResult>

    @DELETE("/api/v1/history")
    suspend fun clearHistory(): ApiResponse<ActionResult>

    // ==================== 用户 ====================

    @GET("/api/v1/users/me")
    suspend fun getMyProfile(): ApiResponse<MeResponse>

    @HTTP(method = "PATCH", path = "/api/v1/users/me", hasBody = true)
    suspend fun updateMyProfile(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<MeResponse>

    @GET("/api/v1/users/{id}")
    suspend fun getUserSpace(@Path("id") id: Long): ApiResponse<SpaceInfo>

    @GET("/api/v1/users/{id}/videos")
    suspend fun getUserVideos(
        @Path("id") uid: Long,
        @Query("page") page: Int = 1,
        @Query("pageSize") size: Int = 20,
        @Query("keyword") keyword: String? = null,
        @Query("sort") sort: String? = null
    ): ApiResponse<PageData<Video>>

    @POST("/api/v1/users/{id}/follow")
    suspend fun followUser(@Path("id") uid: Long): ApiResponse<FollowResult>

    @GET("/api/v1/users/{id}/followers")
    suspend fun getFollowers(@Path("id") uid: Long, @Query("page") page: Int = 1): ApiResponse<PageData<UserInfo>>

    @GET("/api/v1/users/{id}/following")
    suspend fun getFollowing(@Path("id") uid: Long, @Query("page") page: Int = 1): ApiResponse<PageData<UserInfo>>

    @GET("/api/v1/users/{id}/dynamics")
    suspend fun getUserDynamics(@Path("id") uid: Long, @Query("page") page: Int = 1): ApiResponse<PageData<Dynamic>>

    @GET("/api/v1/users/recommend")
    suspend fun getRecommendedUps(@Query("limit") limit: Int = 10): ApiResponse<ItemList<UserInfo>>

    @POST("/api/v1/users/me/checkin")
    suspend fun checkin(): ApiResponse<CheckinResult>

    @GET("/api/v1/users/me/coins")
    suspend fun getMyCoins(): ApiResponse<CoinsOverview>

    @GET("/api/v1/users/me/exp-logs")
    suspend fun getExpLogs(@Query("page") page: Int = 1): ApiResponse<Any>

    @POST("/api/v1/users/me/password")
    suspend fun changePassword(@Body body: Map<String, @JvmSuppressWildcards String>): ApiResponse<LoginResult>

    // ==================== 动态/关注流 ====================

    @GET("/api/v1/videos/following")
    suspend fun getFollowingVideos(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<Video>>

    @GET("/api/v1/dynamics")
    suspend fun getDynamics(@Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<Dynamic>>

    @POST("/api/v1/dynamics")
    suspend fun postDynamic(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @DELETE("/api/v1/dynamics/{id}")
    suspend fun deleteDynamic(@Path("id") id: Long): ApiResponse<ActionResult>

    // ==================== 认证 ====================

    @GET("/api/v1/captcha")
    suspend fun getCaptcha(): ApiResponse<Captcha>

    @POST("/api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): ApiResponse<LoginResult>

    @POST("/api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): ApiResponse<LoginResult>

    @POST("/api/v1/auth/send-code")
    suspend fun sendEmailCode(@Body body: SendCodeRequest): ApiResponse<SendCodeResult>

    @POST("/api/v1/auth/verify-2fa")
    suspend fun verify2fa(@Body body: Verify2faRequest): ApiResponse<LoginResult>

    @POST("/api/v1/auth/logout")
    suspend fun logout(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    // ==================== 节点 ====================

    @GET("/api/v1/gateway/nodes")
    suspend fun getGatewayNodes(): ApiResponse<GatewayInfo>

    @GET("/api/v1/gateway/probe")
    suspend fun probeNode(@Query("nodeId") nodeId: Long): ApiResponse<Any>

    /** 切节点授权：未调用时节点返回 HTTP 200 + {code:40301}；nodeId=0 回主站 */
    @POST("/api/v1/gateway/handoff")
    suspend fun handoff(@Body body: HandoffRequest): ApiResponse<HandoffResult>

    // ==================== 消息/通知 ====================

    @GET("/api/v1/messages/conversations")
    suspend fun getConversations(@Query("page") page: Int = 1, @Query("limit") limit: Int = 20): ApiResponse<ConversationList>

    @GET("/api/v1/messages/with/{uid}")
    suspend fun getChatWith(
        @Path("uid") uid: Long,
        @Query("pageSize") pageSize: Int = 30,
        @Query("before") before: Long? = null
    ): ApiResponse<ChatHistory>

    @POST("/api/v1/messages")
    suspend fun sendMessage(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @POST("/api/v1/messages/read")
    suspend fun markMessagesRead(@Body body: Map<String, @JvmSuppressWildcards Long>): ApiResponse<ActionResult>

    @GET("/api/v1/notifications")
    suspend fun getNotifications(@Query("type") type: String? = null, @Query("page") page: Int = 1): ApiResponse<PageData<Notification>>

    @POST("/api/v1/notifications/read")
    suspend fun markNotificationsRead(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @GET("/api/v1/notifications/summary")
    suspend fun getNotificationSummary(): ApiResponse<NotificationSummary>

    // ==================== 大会员 ====================

    @GET("/api/v1/vip/me")
    suspend fun getVipMe(): ApiResponse<VipMe>

    @POST("/api/v1/vip/redeem")
    suspend fun redeemVip(@Body body: VipRedeemRequest): ApiResponse<ActionResult>

    @GET("/api/v1/vip/media-ticket")
    suspend fun getVipMediaTicket(): ApiResponse<VipMediaTicket>

    @GET("/api/v1/vip/plans")
    suspend fun getVipPlans(): ApiResponse<Any>

    // ==================== 投稿/上传 ====================

    /** 通用上传：scope ∈ videos / covers / articles / audio / ... */
    @Multipart
    @POST("/api/v1/upload")
    suspend fun upload(@Query("scope") scope: String, @Part file: MultipartBody.Part): ApiResponse<UploadResult>

    @POST("/api/v1/videos")
    suspend fun publishVideo(@Body body: PublishRequest): ApiResponse<PublishResult>

    @POST("/api/v1/articles")
    suspend fun publishArticle(@Body body: PublishRequest): ApiResponse<PublishResult>

    @GET("/api/v1/videos/mine")
    suspend fun getMyVideos(@Query("status") status: String? = null, @Query("page") page: Int = 1): ApiResponse<PageData<Video>>

    @HTTP(method = "PATCH", path = "/api/v1/videos/{id}", hasBody = true)
    suspend fun updateVideo(@Path("id") id: Long, @Body body: PublishRequest): ApiResponse<PublishResult>

    @DELETE("/api/v1/videos/{id}")
    suspend fun deleteVideo(@Path("id") id: Long): ApiResponse<ActionResult>

    @POST("/api/v1/videos/{id}/nudge")
    suspend fun nudgeReview(@Path("id") id: Long): ApiResponse<ActionResult>

    // ==================== 举报 ====================

    @POST("/api/v1/reports")
    suspend fun submitReport(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    // ==================== 管理员：内容审核 ====================

    @GET("/api/v1/admin/overview")
    suspend fun getAdminOverview(): ApiResponse<AdminOverview>

    @GET("/api/v1/admin/todos")
    suspend fun getAdminTodos(): ApiResponse<AdminTodos>

    @GET("/api/v1/admin/queue")
    suspend fun getAdminQueue(@Query("status") status: String? = null, @Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<AdminQueue>

    @POST("/api/v1/admin/videos/{id}/review")
    suspend fun reviewVideo(@Path("id") id: Long, @Body body: ReviewRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/videos/{id}/pin")
    suspend fun pinVideo(@Path("id") id: Long, @Body body: PinRequest): ApiResponse<ActionResult>

    @GET("/api/v1/admin/comments")
    suspend fun getAdminComments(@Query("keyword") keyword: String? = null, @Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<AdminComment>>

    @DELETE("/api/v1/admin/comments/{id}")
    suspend fun deleteAdminComment(@Path("id") id: Long): ApiResponse<ActionResult>

    @GET("/api/v1/admin/danmaku")
    suspend fun getAdminDanmaku(@Query("keyword") keyword: String? = null, @Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<AdminDanmaku>>

    @DELETE("/api/v1/admin/danmaku/{id}")
    suspend fun deleteAdminDanmaku(@Path("id") id: Long): ApiResponse<ActionResult>

    @GET("/api/v1/admin/creator/applications")
    suspend fun getCreatorApplications(@Query("status") status: String? = null, @Query("page") page: Int = 1): ApiResponse<PageData<CreatorApplication>>

    @POST("/api/v1/admin/creator/applications/{id}/review")
    suspend fun reviewCreatorApplication(@Path("id") id: Long, @Body body: ReviewRequest): ApiResponse<ActionResult>

    // ==================== 管理员：站务处理 ====================

    @GET("/api/v1/admin/reports")
    suspend fun getAdminReports(@Query("status") status: String? = null, @Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<AdminReport>>

    @POST("/api/v1/admin/reports/{id}/handle")
    suspend fun handleReport(@Path("id") id: Long, @Body body: HandleReportRequest): ApiResponse<ActionResult>

    @GET("/api/v1/admin/verifications")
    suspend fun getAdminVerifications(@Query("status") status: String? = null, @Query("page") page: Int = 1): ApiResponse<PageData<AdminVerification>>

    @POST("/api/v1/admin/verifications/{id}/review")
    suspend fun reviewVerification(@Path("id") id: Long, @Body body: ReviewRequest): ApiResponse<ActionResult>

    @GET("/api/v1/admin/posters")
    suspend fun getAdminPosters(@Query("status") status: String? = null, @Query("page") page: Int = 1): ApiResponse<PageData<AdminPoster>>

    @HTTP(method = "PATCH", path = "/api/v1/admin/posters/{id}", hasBody = true)
    suspend fun reviewPoster(@Path("id") id: Long, @Body body: PosterReviewRequest): ApiResponse<ActionResult>

    // ==================== 管理员：运营管理 ====================

    @GET("/api/v1/admin/ads")
    suspend fun getAdminAds(): ApiResponse<ItemList<AdminAd>>

    @POST("/api/v1/admin/ads")
    suspend fun createAd(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @HTTP(method = "PATCH", path = "/api/v1/admin/ads/{id}", hasBody = true)
    suspend fun updateAd(@Path("id") id: Long, @Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @DELETE("/api/v1/admin/ads/{id}")
    suspend fun deleteAd(@Path("id") id: Long): ApiResponse<ActionResult>

    @GET("/api/v1/admin/shop/products")
    suspend fun getAdminProducts(): ApiResponse<ItemList<ShopProduct>>

    @POST("/api/v1/admin/shop/products")
    suspend fun createProduct(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @HTTP(method = "PATCH", path = "/api/v1/admin/shop/products/{id}", hasBody = true)
    suspend fun updateProduct(@Path("id") id: Long, @Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @DELETE("/api/v1/admin/shop/products/{id}")
    suspend fun deleteProduct(@Path("id") id: Long): ApiResponse<ActionResult>

    @GET("/api/v1/admin/shop/orders")
    suspend fun getAdminOrders(@Query("status") status: String? = null, @Query("page") page: Int = 1): ApiResponse<PageData<ShopOrder>>

    @HTTP(method = "PATCH", path = "/api/v1/admin/shop/orders/{id}", hasBody = true)
    suspend fun handleOrder(@Path("id") id: Long, @Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    // ==================== 管理员：系统管理 ====================

    @GET("/api/v1/admin/users")
    suspend fun getAdminUsers(@Query("keyword") keyword: String? = null, @Query("page") page: Int = 1, @Query("pageSize") pageSize: Int = 20): ApiResponse<PageData<AdminUser>>

    @POST("/api/v1/admin/users/{id}/ban")
    suspend fun banUser(@Path("id") id: Long, @Body body: BanRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/users/{id}/role")
    suspend fun setUserRole(@Path("id") id: Long, @Body body: RoleRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/users/{id}/exp")
    suspend fun adjustExp(@Path("id") id: Long, @Body body: ExpRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/users/{id}/coins")
    suspend fun adjustCoins(@Path("id") id: Long, @Body body: CoinsRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/users/{id}/badge")
    suspend fun grantBadge(@Path("id") id: Long, @Body body: BadgeRequest): ApiResponse<ActionResult>

    @DELETE("/api/v1/admin/users/{id}")
    suspend fun deleteUser(@Path("id") id: Long): ApiResponse<ActionResult>

    @GET("/api/v1/admin/creator/withdrawals")
    suspend fun getAdminWithdrawals(@Query("status") status: String? = null, @Query("page") page: Int = 1): ApiResponse<PageData<AdminWithdrawal>>

    @POST("/api/v1/admin/creator/withdrawals/{id}/review")
    suspend fun reviewWithdrawal(@Path("id") id: Long, @Body body: ReviewRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/creator/withdrawals/{id}/pay")
    suspend fun payWithdrawal(@Path("id") id: Long, @Body body: Map<String, @JvmSuppressWildcards String>): ApiResponse<ActionResult>

    @GET("/api/v1/admin/storage")
    suspend fun getAdminStorage(@Query("deep") deep: Int = 0): ApiResponse<AdminStorage>

    @POST("/api/v1/admin/storage/rescan")
    suspend fun rescanStorage(): ApiResponse<ActionResult>

    @PUT("/api/v1/admin/storage/settings")
    suspend fun updateStorageSettings(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<ActionResult>

    @GET("/api/v1/admin/gateway")
    suspend fun getAdminGateway(): ApiResponse<AdminGateway>

    @PUT("/api/v1/admin/gateway/settings")
    suspend fun updateGatewaySettings(@Body body: GatewaySettingsRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/gateway/nodes")
    suspend fun createNode(@Body body: NodeEditRequest): ApiResponse<ActionResult>

    @PUT("/api/v1/admin/gateway/nodes/{id}")
    suspend fun updateNode(@Path("id") id: Long, @Body body: NodeEditRequest): ApiResponse<ActionResult>

    @DELETE("/api/v1/admin/gateway/nodes/{id}")
    suspend fun deleteNode(@Path("id") id: Long): ApiResponse<ActionResult>

    @POST("/api/v1/admin/gateway/grants")
    suspend fun addGrant(@Body body: GrantAddRequest): ApiResponse<ActionResult>

    @POST("/api/v1/admin/gateway/grants/{ip}")
    suspend fun handleGrant(@Path("ip") ip: String, @Body body: GrantActionRequest): ApiResponse<ActionResult>
}
