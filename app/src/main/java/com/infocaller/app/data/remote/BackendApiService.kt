package com.infocaller.app.data.remote

import com.infocaller.app.data.remote.model.InfoCallerLookupResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

data class PhoneLookupRequest(
    val phoneNumber: String
)

interface BackendApiService {

    @POST("api/v1/lookup/phone")
    suspend fun lookupPhone(
        @Body request: PhoneLookupRequest
    ): Response<InfoCallerLookupResponse>

}
