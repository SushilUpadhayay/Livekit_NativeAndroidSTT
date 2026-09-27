package com.example.ondevicestt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TokenResponse(
    val serverUrl: String,
    val participantToken: String
)

class TokenService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "LiveKitChat"
        const val DEFAULT_TOKEN_SERVER_URL = "http://172.16.20.79:8000/token" // change it to the ip of the connected wifi
    }

    suspend fun fetchToken(
        tokenServerUrl: String = DEFAULT_TOKEN_SERVER_URL,
        roomName: String = "mobile-voice-room",
        participantIdentity: String = "android-user-${UUID.randomUUID().toString().take(8)}",
        agentName: String = "my-agent"
    ): TokenResponse = withContext(Dispatchers.IO) {
        val trimmedUrl = tokenServerUrl.trim().trimEnd('/')
        val baseUrl = if (trimmedUrl.endsWith("/token")) trimmedUrl else "$trimmedUrl/token"

        val httpUrl = baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("room_name", roomName)
            ?.addQueryParameter("participant_name", participantIdentity)
            ?.addQueryParameter("agent_name", agentName)
            ?.build()
            ?: throw IOException("Invalid token server URL: $baseUrl")

        Log.d(TAG, "[TOKEN-SERVICE] Fetching token via GET from $httpUrl (pipeline identical to index.html)")

        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IOException("Token fetch failed with HTTP ${response.code}: $errorBody")
            }

            val responseBody = response.body?.string()
                ?: throw IOException("Empty response received from token server")

            val json = JSONObject(responseBody)

            val serverUrl = when {
                json.has("server_url") -> json.getString("server_url")
                json.has("serverUrl") -> json.getString("serverUrl")
                json.has("url") -> json.getString("url")
                else -> throw IOException("server_url / serverUrl missing in response: $responseBody")
            }

            val participantToken = when {
                json.has("participant_token") -> json.getString("participant_token")
                json.has("participantToken") -> json.getString("participantToken")
                json.has("token") -> json.getString("token")
                else -> throw IOException("participant_token / token missing in response: $responseBody")
            }

            Log.i(TAG, "[TOKEN-SERVICE] Token successfully derived from server.py for room '$roomName'!")
            TokenResponse(serverUrl = serverUrl, participantToken = participantToken)
        }
    }
}
