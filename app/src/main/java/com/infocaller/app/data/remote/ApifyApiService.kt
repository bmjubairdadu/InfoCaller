package com.infocaller.app.data.remote

import com.infocaller.app.data.remote.model.ApifyLookupItem
import com.infocaller.app.data.remote.model.ApifyLookupRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

interface ApifyApiService {

    @POST("acts/eduair94~whatsapp-data-lookup/run-sync-get-dataset-items")
    suspend fun lookupNumbers(
        @Body request: ApifyLookupRequest,
        @Query("token") token: String
    ): Response<List<ApifyLookupItem>>

}
