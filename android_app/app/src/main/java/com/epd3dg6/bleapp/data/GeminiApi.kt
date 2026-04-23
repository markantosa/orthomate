package com.epd3dg6.bleapp.data

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.GET

interface GeminiService {
    @GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String
    ): ModelListResponse

    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

data class GeminiRequest(val contents: List<Content>)
data class Content(val parts: List<Part>)
data class Part(val text: String?)

data class GeminiResponse(val candidates: List<Candidate>?)
data class Candidate(val content: GeminiContent?, val finishReason: String?)
data class GeminiContent(val parts: List<Part>?)

data class ModelListResponse(val models: List<GeminiModel>)
data class GeminiModel(val name: String, val displayName: String, val supportedGenerationMethods: List<String>)
