package com.infocaller.app.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url

interface RegistryApiService {
    @GET
    suspend fun fetchManifest(@Url url: String): Response<com.google.gson.JsonObject>
}
