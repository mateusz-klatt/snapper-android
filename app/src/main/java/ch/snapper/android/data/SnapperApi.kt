package ch.snapper.android.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

internal interface SnapperApi {
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
