
package com.fflivemic.app

import android.app.*
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.ActivityManager
import android.util.Log
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.TextFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.rtmp.RtmpStream

class CaptureService : Service(), ConnectChecker, StreamChatBus.Listener {

    companion object {
        const val ACTION_START = "com.fflivemic.app.START_CAPTURE"
        const val ACTION_STOP = "com.fflivemic.app.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_SERVER = "rtmp_server"
        const val EXTRA_KEY = "rtmp_key"

        const val EXTRA_QUALITY = "stream_quality"
        const val QUALITY_AUTO = "AUTO"
        const val QUALITY_HD = "HD"
        const val QUALITY_ULTRA = "ULTRA"

        const val EXTRA_STREAM_MODE = "stream_mode"
        const val MODE_LONG = "LONG"
        const val MODE_CROP = "CROP"
        const val MODE_FIT = "FIT"

        private const val CHANNEL = "ff_live_stream"
        private const val NOTIFICATION_ID = 304
        private const val TAG = "FFLiveMic"
        private const val AUDIO_RATE = 44100
        private const val MAX_CHAT_LINES = 4
    }

    private data class VideoProfile(
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val label: String
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chatLines = mutableListOf<String>()

    private var projection: MediaProjection? = null
    private var rtmpStream: RtmpStream? = null
    private var chatFilter: TextFilterRender? = null
    private var floatingChat: FloatingChatOverlay? = null

    @Volatile private var stopped = false
    @Volatile private var overlayEnabled = false

    override fun onCreate() {
        super.onCreate()
        StreamChatBus.register(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPipeline()
                stopSelf()
            }
            ACTION_START -> {
                if (rtmpStream == null) startPipeline(intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onChatEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        Log.i(TAG, "onChatEnabled called: $enabled")

        mainHandler.post {
            try {
                Toast.makeText(
                    applicationContext,
                    if (enabled) "FF LIVE MIC: Chat ON" else "FF LIVE MIC: Chat OFF",
                    Toast.LENGTH_SHORT
                ).show()

                if (enabled) {
                    if (!android.provider.Settings.canDrawOverlays(this)) {
                        Log.w(TAG, "Overlay permission is not granted")
                        Toast.makeText(
                            applicationContext,
                            "Floating chat permission नहीं मिली",
                            Toast.LENGTH_LONG
                        ).show()
                        return@post
                    }

                    if (floatingChat == null) {
                        floatingChat = FloatingChatOverlay(this)
                    }
                    floatingChat?.show()
                } else {
                    floatingChat?.hide()
                }

                updateChatOverlay()
            } catch (e: Exception) {
                Log.e(TAG, "Floating chat service error", e)
                Toast.makeText(
                    applicationContext,
                    "Chat overlay error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onChatMessage(line: StreamChatBus.ChatLine) {
        mainHandler.post {
            if (!overlayEnabled || stopped) return@post

            val author = line.author.replace("\n", " ").take(22)
            val message = line.message.replace("\n", " ").take(100)

            chatLines.add("$author: $message")
            floatingChat?.addMessage(author, message)
            while (chatLines.size > MAX_CHAT_LINES) {
                chatLines.removeAt(0)
            }
            updateChatOverlay()
        }
    }

    private fun updateChatOverlay() {
        val stream = rtmpStream ?: return

        try {
            if (!overlayEnabled || stopped) {
                if (chatFilter != null) {
                    if (stream.getGlInterface().filtersCount() > 0) {
                        stream.getGlInterface().removeFilter(0)
                    }
                    chatFilter = null
                }
                return
            }

            var filter = chatFilter
            if (filter == null) {
                filter = TextFilterRender()
                filter.setText("LIVE CHAT", 18f, Color.WHITE)
                filter.setScale(75f, 35f)
                filter.setPosition(TranslateTo.BOTTOM_LEFT)
                stream.getGlInterface().setFilter(filter)
                chatFilter = filter
            }

            val displayText = buildString {
                append("LIVE CHAT")
                chatLines.forEach {
                    append("\n")
                    append(it)
                }
            }

            filter.setText(displayText, 18f, Color.WHITE)

        } catch (e: Exception) {
            Log.e(TAG, "Chat overlay update failed", e)
        }
    }

    private fun chooseProfile(
        requested: String,
        portrait: Boolean
    ): VideoProfile {
        val metrics = resources.displayMetrics
        val rawW = metrics.widthPixels.coerceAtLeast(2)
        val rawH = metrics.heightPixels.coerceAtLeast(2)

        val activityManager =
            getSystemService(ACTIVITY_SERVICE) as ActivityManager

        val memoryMb = activityManager.memoryClass

        val maxLongSide = when (requested) {
            QUALITY_HD -> 1280
            QUALITY_ULTRA -> 1920
            else -> if (memoryMb >= 6) 1920 else 1280
        }

        val fps = when (requested) {
            QUALITY_HD -> 30
            QUALITY_ULTRA -> 60
            else -> if (memoryMb >= 6) 60 else 30
        }

        val bitrate = when (requested) {
            QUALITY_HD -> 3_500_000
            QUALITY_ULTRA -> 8_000_000
            else -> if (memoryMb >= 6) 5_000_000 else 2_500_000
        }

        val longSide = maxLongSide

        val width: Int
        val height: Int

        if (portrait) {
            width = (longSide * 9 / 16 / 2) * 2
            height = longSide
        } else {
            width = longSide
            height = (longSide * 9 / 16 / 2) * 2
        }

        val codec = findH264Encoder(width, height, fps, bitrate)

        val safeFps = if (codec != null) fps else 30
        val safeBitrate = if (codec != null) bitrate else 2_500_000

        return VideoProfile(
            width.coerceAtLeast(2),
            height.coerceAtLeast(2),
            safeFps,
            safeBitrate,
            "${width}x${height} @ ${safeFps} FPS"
        )
    }

    private fun findH264Encoder(
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): String? {
        return try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)

            codecs.codecInfos.firstOrNull { info ->
                if (!info.isEncoder) return@firstOrNull false

                info.supportedTypes.any { type ->
                    if (!type.equals(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            ignoreCase = true
                        )
                    ) return@any false

                    try {
                        val caps = info.getCapabilitiesForType(type)
                        caps.videoCapabilities.areSizeAndRateSupported(
                            width,
                            height,
                            fps.toDouble()
                        )
                    } catch (_: Exception) {
                        false
                    }
                }
            }?.name
        } catch (e: Exception) {
            Log.w(TAG, "H264 capability check failed", e)
            null
        }
    }

    private fun startPipeline(intent: Intent) {
        stopped = false
        createChannel()

        val notification = buildNotification("YouTube Live तैयार हो रहा है…")

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

            @Suppress("DEPRECATION")
            val resultData =
                intent.getParcelableExtra<Intent>(EXTRA_DATA)
                    ?: error("MediaProjection permission data missing")

            val config = YouTubeRtmpConfig(
                intent.getStringExtra(EXTRA_SERVER).orEmpty(),
                intent.getStringExtra(EXTRA_KEY).orEmpty()
            )

            val publishUrl = config.validatedPublishUrl()

            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            val mp = manager.getMediaProjection(resultCode, resultData)
                ?: error("MediaProjection could not be created")

            projection = mp

            mp.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        mainHandler.post {
                            stopPipeline()
                            stopSelf()
                        }
                    }
                },
                mainHandler
            )

            val requested =
                intent.getStringExtra(EXTRA_QUALITY) ?: QUALITY_AUTO

            val mode =
                intent.getStringExtra(EXTRA_STREAM_MODE) ?: MODE_LONG

            val portrait = mode != MODE_LONG
            val profile = chooseProfile(requested, portrait)

            Log.i(
                TAG,
                "Mode=$mode, profile=${profile.label}, bitrate=${profile.bitrate}"
            )

            val mixedAudio: AudioSource =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MixAudioSource(mp).apply {
                        microphoneVolume = 1.5f
                        internalVolume = 0.85f
                    }
                } else {
                    MicrophoneSource()
                }

            val stream = RtmpStream(
                applicationContext,
                this,
                ScreenSource(applicationContext, mp),
                mixedAudio
            )

            rtmpStream = stream

            // Stream orientation and source fitting.
            stream.getGlInterface().setStreamIsPortrait(portrait)

            stream.getGlInterface().setAspectRatioMode(
                when (mode) {
                    MODE_CROP -> AspectRatioMode.Fill
                    MODE_FIT -> AspectRatioMode.Adjust
                    else -> AspectRatioMode.Fill
                }
            )

            check(
                stream.prepareVideo(
                    profile.width,
                    profile.height,
                    profile.bitrate,
                    profile.fps,
                    2,
                    0
                )
            ) {
                "H.264 video encoder preparation failed"
            }

            check(
                stream.prepareAudio(
                    AUDIO_RATE,
                    true,
                    128_000,
                    true,
                    true
                )
            ) {
                "AAC audio encoder preparation failed"
            }

            updateChatOverlay()
            stream.startStream(publishUrl)

            updateNotification(
                "YouTube Live शुरू • $mode • ${profile.label}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Live pipeline start failed", e)
            updateNotification(
                "स्ट्रीम शुरू नहीं हुई: ${e.message ?: "त्रुटि"}"
            )
            stopPipeline()
            stopSelf()
        }
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message.take(120))
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(message: String): Notification {
        val stopIntent =
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP)

        val stopPendingIntent = PendingIntent.getService(
            this,
            305,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("FF LIVE MIC")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "STOP LIVE",
                stopPendingIntent
            )
            .build()
    }

    private fun stopPipeline() {
        if (stopped) return
        stopped = true

        try {
            rtmpStream?.stopStream()
        } catch (e: Exception) {
            Log.w(TAG, "RTMP stop", e)
        }

        try {
            rtmpStream?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Encoder release", e)
        }

        rtmpStream = null
        chatFilter = null
        chatLines.clear()
        floatingChat?.close()
        floatingChat = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onConnectionStarted(url: String) {
        updateNotification("YouTube RTMP कनेक्शन शुरू…")
    }

    override fun onConnectionSuccess() {
        updateNotification("YouTube RTMP कनेक्टेड")
    }

    override fun onConnectionFailed(reason: String) {
        updateNotification("RTMP कनेक्शन विफल — नेटवर्क जाँचें")
    }

    override fun onNewBitrate(bitrate: Long) {
        updateNotification("लाइव • ${bitrate / 1000} kbps")
    }

    override fun onDisconnect() {
        updateNotification("YouTube RTMP डिस्कनेक्ट हुआ")
    }

    override fun onAuthError() {
        updateNotification("YouTube ने Stream Key अस्वीकार की")
    }

    override fun onAuthSuccess() {
        updateNotification("YouTube RTMP प्रमाणीकरण सफल")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "FF Live Mic streaming",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    override fun onDestroy() {
        StreamChatBus.unregister(this)
        stopPipeline()
        super.onDestroy()
    }
}
