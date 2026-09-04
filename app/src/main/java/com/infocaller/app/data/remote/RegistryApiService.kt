package com.infocaller.app.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface RegistryApiService {
    @GET
    suspend fun fetchManifest(@Url url: String): Response<com.google.gson.JsonObject>

    @GET("api/v1/registry/lookup/{number}")
    suspend fun lookupInRegistry(
        @retrofit2.http.Path("number") number: String
    ): Response<com.infocaller.app.data.remote.model.RegistryLookupResponse>
}
