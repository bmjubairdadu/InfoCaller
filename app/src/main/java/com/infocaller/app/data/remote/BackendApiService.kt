package com.infocaller.app.data.remote

import com.infocaller.app.data.remote.model.InfoCallerLookupResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Header

data class PhoneLookupRequest(
    val phoneNumber: String
)

interface BackendApiService {

    @POST("api/v1/lookup/phone")
    suspend fun lookupPhone(
        @Body request: PhoneLookupRequest,
        @Header("x-api-key") apiKey: String = com.infocaller.app.BuildConfig.BACKEND_API_KEY
    ): Response<InfoCallerLookupResponse>

    @POST("api/v1/registry/publish")
    suspend fun publishToRegistry(
        @Body record: InfoCallerLookupResponse,
        @Header("x-api-key") apiKey: String = com.infocaller.app.BuildConfig.BACKEND_API_KEY
    ): Response<com.google.gson.JsonObject>

}
