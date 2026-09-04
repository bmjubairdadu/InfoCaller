package com.infocaller.app.data.remote

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

data class OwnerOtpRequest(val phone: String)
data class OwnerOtpVerify(val phone: String, val code: String)
data class OwnerOtpVerifyResponse(val ownerToken: String?, val expiresAt: String?)
data class OwnerProfileRequest(
    val phone: String,
    val displayName: String,
    val photoUrl: String? = null,
    val businessName: String? = null,
    val businessCategory: String? = null,
    val country: String? = null,
    val isBusiness: Boolean = false,
    val visibility: String = "public",
    val consentGranted: Boolean = true
)
data class OwnerProfilePatch(
    val displayName: String? = null,
    val photoUrl: String? = null,
    val businessName: String? = null,
    val businessCategory: String? = null,
    val country: String? = null,
    val isBusiness: Boolean? = null,
    val visibility: String? = null,
    val consentGranted: Boolean? = null
)
data class OwnerSpamReport(val phone: String, val reason: String = "spam")

interface OwnerBackendApi {
    @POST("api/v1/owner/otp/request")
    suspend fun requestOtp(@Body body: OwnerOtpRequest): Response<JsonObject>

    @POST("api/v1/owner/otp/verify")
    suspend fun verifyOtp(@Body body: OwnerOtpVerify): Response<OwnerOtpVerifyResponse>

    @GET("api/v1/owner/profile/me")
    suspend fun myProfile(@Header("Authorization") auth: String): Response<JsonObject>

    @POST("api/v1/owner/profile")
    suspend fun createProfile(@Header("Authorization") auth: String, @Body body: OwnerProfileRequest): Response<JsonObject>

    @PATCH("api/v1/owner/profile")
    suspend fun updateProfile(@Header("Authorization") auth: String, @Body body: OwnerProfilePatch): Response<JsonObject>

    @DELETE("api/v1/owner/profile")
    suspend fun deleteProfile(@Header("Authorization") auth: String): Response<JsonObject>

    @POST("api/v1/owner/spam-report")
    suspend fun spamReport(@Body body: OwnerSpamReport): Response<JsonObject>

    @GET("api/v1/owner/lookup")
    suspend fun lookup(@Query("phone") phone: String): Response<JsonObject>
}
