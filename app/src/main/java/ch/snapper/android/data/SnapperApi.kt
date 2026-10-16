package ch.snapper.android.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Query
import retrofit2.http.Path

internal interface SnapperApi {
    @GET("api/processes/summary") suspend fun processSummary(): Envelope<ProcessSummaryDto>
    @GET("api/processes/configured") suspend fun configuredProcesses(): Envelope<List<ConfiguredProcessDto>>
    @POST("api/processes/{name}/start") suspend fun startProcess(@Path("name") name: String, @Body request: Envelope<ProcessStartPayload>): Envelope<ProcessStartDto>
    @POST("api/processes/{name}/stop") suspend fun stopProcess(@Path("name") name: String): Envelope<ProcessStopDto>
    @PATCH("api/processes/{name}/desired-state") suspend fun processDesiredState(@Path("name") name: String, @Body request: Envelope<ProcessDesiredStatePayload>): Envelope<ProcessDesiredStateDto>
    @GET("api/ai-reviews") suspend fun auditReviews(): AiReviewListResponse<AiAuditReviewDto>
    @GET("api/ai-reviews/pending") suspend fun pendingReviews(): AiReviewListResponse<AiPendingReviewDto>
    @POST("api/ai-reviews/{review}/decision") suspend fun submitDecision(@Path("review") review: String, @Body request: Envelope<AiDecisionPayload>): AiDecisionResponse
    @POST("api/execution-plans") suspend fun createBracket(@Body request: Envelope<PositionBracketPayload>): Envelope<ExecutionPlanDto>
    @POST("api/trailing-stops") suspend fun createTrailing(@Body request: Envelope<PositionTrailingPayload>): Envelope<ExecutionPlanDto>

    @GET("api/operators") suspend fun desks(): Envelope<List<DeskDto>>
    @POST("api/auth/desks/{operator}/members/{username}") suspend fun attachViewer(
        @Path("operator") operator: String, @Path("username") username: String,
    ): Envelope<String>
    @GET("api/health") suspend fun systemHealth(): Envelope<SystemHealthCheckDataDto>
    @GET("api/status") suspend fun systemStatus(): Envelope<SystemSystemStatusDataDto>
    @GET("api/strategies") suspend fun systemStrategies(): Envelope<List<SystemStrategyProcessDto>>
    @GET("api/backtests") suspend fun systemBacktests(): Envelope<List<SystemBacktestRunDataDto>>
    @GET("api/auth/users") suspend fun systemUsers(): Envelope<List<SystemUserProfileDto>>
    @GET("api/ai-delegates") suspend fun systemDelegates(): Envelope<List<SystemDelegateReadDto>>
    @GET("api/executions") suspend fun executions(@Query("wallet_public_id") walletPublicId: String?): Envelope<List<ExecutionDto>>
    @GET("api/alert_defaults") suspend fun notificationDefaults(): Envelope<List<NotificationDefaultDto>>
    @GET("api/signals") suspend fun signals(@Query("wallet_public_id") walletPublicId: String?): Envelope<List<SignalDto>>
    @GET("api/portfolio/accounts") suspend fun venueAccounts(@Query("wallet_public_id") walletPublicId: String?): Envelope<List<VenueAccountDto>>
    @GET("api/portfolio/pnl/series") suspend fun pnlSeries(
        @Query("wallet_public_id") walletPublicId: String, @Query("mode") mode: String,
        @Query("granularity") granularity: String, @Query("from") from: String,
        @Query("to") to: String, @Query("valuation_ccy") valuationCcy: String,
    ): Envelope<PnlSeriesDto>
    @PATCH("api/alert_defaults") suspend fun updateNotificationDefaults(@Body request: Envelope<UpdateNotificationDefaultPayload>): Envelope<NotificationDefaultDto>
    @GET("api/exchanges") suspend fun exchanges(): Envelope<List<String>>
    @GET("api/exchanges/{exchange}/instruments/detail") suspend fun instruments(@Path("exchange") exchange: String): Envelope<List<InstrumentDto>>
    @POST("api/orders") suspend fun createOrder(@Body request: Envelope<CreateOrderPayload>): Envelope<ExecutionPlanDto>
    @POST("api/orders/{plan}/cancel") suspend fun cancelOrder(@Path("plan") plan: String, @Body request: Envelope<CancelOrderPayload>): Envelope<ExecutionPlanDto>
    @POST("api/auth/login") suspend fun login(@Body request: Envelope<LoginPayload>): Envelope<AuthPayload>
    @POST("api/auth/refresh") suspend fun refresh(@Body request: Envelope<RefreshPayload>): Envelope<AuthPayload>
    @POST("api/auth/logout") suspend fun logout(@Header("Cookie") cookies: String, @Header("X-CSRF-Token") csrf: String?): Response<Unit>
    @GET("api/auth/me") suspend fun me(): Envelope<UserDto>
    @GET("api/wallets") suspend fun wallets(): Envelope<List<WalletDto>>
    @GET("api/positions") suspend fun positions(@Query("wallet_public_id") walletPublicId: String?): Envelope<List<PositionDto>>
    @GET("api/orders") suspend fun orders(@Query("wallet_public_id") walletPublicId: String?): Envelope<List<OrderDto>>
    @GET("api/alerts/history") suspend fun alerts(@Query("limit") limit: Int = 100): Envelope<List<AlertDto>>
    @GET("api/candles") suspend fun candles(
        @Query("exchange") exchange: String,
        @Query("instrument") instrument: String,
        @Query("timeframe") timeframe: String,
        @Query("limit") limit: Int,
    ): Envelope<List<CandleDto>>
}
