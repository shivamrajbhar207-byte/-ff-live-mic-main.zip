
package com.fflivemic.app

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class YouTubeLiveChatClient {

    data class ChatMessage(
        val author: String,
        val message: String,
        val publishedAt: String
    )

    data class LiveStats(
        val viewers: Long?,
        val likes: Long?
    )

    interface Listener {
        fun onStatus(status: String)
        fun onMessage(message: ChatMessage)
        fun onError(error: String)

        // पुराने Listener को भी compatible रखने के लिए default method
        fun onStats(stats: LiveStats) {}
    }

    private data class ActiveBroadcast(
        val videoId: String,
        val liveChatId: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    fun start(accessToken: String, listener: Listener) {
        stop()

        if (accessToken.isBlank()) {
            listener.onError("YouTube login token नहीं मिला।")
            return
        }

        running = true

        worker = thread(
            start = true,
            name = "YouTubeLiveChat"
        ) {
            try {
                postStatus(listener, "YouTube Live Chat खोज रहे हैं...")

                val broadcast = getActiveBroadcast(accessToken)

                if (broadcast == null) {
                    postStatus(
                        listener,
                        "कोई चालू YouTube Live Broadcast नहीं मिला।"
                    )
                    return@thread
                }

                postStatus(listener, "YouTube Live Chat कनेक्ट हो गई।")

                var pageToken: String? = null
                var pollingInterval = 5000L
                var lastStatsUpdate = 0L

                while (running && !Thread.currentThread().isInterrupted) {

                    val result = getMessages(
                        accessToken,
                        broadcast.liveChatId,
                        pageToken
                    )

                    pageToken = result.optString(
                        "nextPageToken",
                        pageToken ?: ""
                    ).ifBlank { pageToken }

                    pollingInterval = result.optLong(
                        "pollingIntervalMillis",
                        pollingInterval
                    ).coerceIn(1000L, 30000L)

                    val items = result.optJSONArray("items")

                    if (items != null) {
                        for (i in 0 until items.length()) {
                            if (!running) break

                            val item = items.optJSONObject(i) ?: continue
                            val snippet = item.optJSONObject("snippet")
                                ?: continue
                            val authorDetails =
                                item.optJSONObject("authorDetails")
                                    ?: continue

                            val message = ChatMessage(
                                author = authorDetails.optString(
                                    "displayName",
                                    "Viewer"
                                ),
                                message = snippet.optString(
                                    "displayMessage",
                                    ""
                                ),
                                publishedAt = snippet.optString(
                                    "publishedAt",
                                    ""
                                )
                            )

                            if (message.message.isNotBlank()) {
                                mainHandler.post {
                                    if (running) {
                                        listener.onMessage(message)
                                    }
                                }
                            }
                        }
                    }

                    // हर 30 सेकंड में लाइव दर्शक और लाइक अपडेट करें।
                    val now = System.currentTimeMillis()

                    if (now - lastStatsUpdate >= 30_000L) {
                        lastStatsUpdate = now

                        try {
                            val stats = getLiveStats(
                                accessToken,
                                broadcast.videoId
                            )

                            mainHandler.post {
                                if (running) {
                                    listener.onStats(stats)
                                }
                            }
                        } catch (e: Exception) {
                            postStatus(
                                listener,
                                "चैट चालू है; लाइव आँकड़े अभी उपलब्ध नहीं हैं।"
                            )
                        }
                    }

                    Thread.sleep(pollingInterval)
                }

            } catch (e: InterruptedException) {
                // Chat stopped.
            } catch (e: Exception) {
                postError(
                    listener,
                    e.message ?: "YouTube Live Chat में समस्या आई।"
                )
            } finally {
                running = false
            }
        }
    }

    private fun getActiveBroadcast(token: String): ActiveBroadcast? {
        val url =
            "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
            "?part=snippet" +
            "&broadcastStatus=active"

        val response = apiGet(url, token)
        val items = response.optJSONArray("items") ?: return null

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val snippet = item.optJSONObject("snippet") ?: continue

            val videoId = item.optString("id", "")
            val chatId = snippet.optString("liveChatId", "")

            if (videoId.isNotBlank() && chatId.isNotBlank()) {
                return ActiveBroadcast(
                    videoId = videoId,
                    liveChatId = chatId
                )
            }
        }

        return null
    }

    private fun getLiveStats(
        token: String,
        videoId: String
    ): LiveStats {

        val encodedId = URLEncoder.encode(videoId, "UTF-8")

        val url =
            "https://www.googleapis.com/youtube/v3/videos" +
            "?part=statistics,liveStreamingDetails" +
            "&id=$encodedId"

        val response = apiGet(url, token)
        val items = response.optJSONArray("items")

        val item = items?.optJSONObject(0)
            ?: return LiveStats(null, null)

        val statistics = item.optJSONObject("statistics")
        val liveDetails = item.optJSONObject("liveStreamingDetails")

        val viewers = liveDetails
            ?.optString("concurrentViewers")
            ?.toLongOrNull()

        val likes = statistics
            ?.optString("likeCount")
            ?.toLongOrNull()

        return LiveStats(
            viewers = viewers,
            likes = likes
        )
    }

    private fun getMessages(
        token: String,
        chatId: String,
        pageToken: String?
    ): JSONObject {

        val encodedChatId = URLEncoder.encode(chatId, "UTF-8")

        var url =
            "https://www.googleapis.com/youtube/v3/liveChat/messages" +
            "?liveChatId=$encodedChatId" +
            "&part=snippet,authorDetails"

        if (!pageToken.isNullOrBlank()) {
            val encodedPage = URLEncoder.encode(pageToken, "UTF-8")
            url += "&pageToken=$encodedPage"
        }

        return apiGet(url, token)
    }

    private fun apiGet(
        address: String,
        token: String
    ): JSONObject {

        val connection =
            URL(address).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000

            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            if (code !in 200..299) {
                val errorMessage = try {
                    JSONObject(body)
                        .optJSONObject("error")
                        ?.optString("message")
                } catch (_: Exception) {
                    null
                }

                throw Exception(
                    errorMessage ?: "YouTube API error: HTTP $code"
                )
            }

            return JSONObject(body)

        } finally {
            connection.disconnect()
        }
    }

    private fun postStatus(
        listener: Listener,
        status: String
    ) {
        mainHandler.post {
            listener.onStatus(status)
        }
    }

    private fun postError(
        listener: Listener,
        error: String
    ) {
        mainHandler.post {
            listener.onError(error)
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
