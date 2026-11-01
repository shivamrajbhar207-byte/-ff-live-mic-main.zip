package com.fflivemic.app

import android.app.*
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.rtmp.RtmpStream

/** Foreground MediaProjection -> RootEncoder H.264/AAC -> YouTube RTMP pipeline. */
class CaptureService : Service(), ConnectChecker {
    companion object {
        const val ACTION_START = "com.fflivemic.app.START_CAPTURE"
        const val ACTION_STOP = "com.fflivemic.app.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        const val EXTRA_SERVER = "rtmp_server"
        const val EXTRA_KEY = "rtmp_key"
        private const val CHANNEL = "ff_live_stream"
        private const val NOTIFICATION_ID = 304
        private const val TAG = "FFLiveMic"
        private const val AUDIO_RATE = 44100
    }

    private var projection: MediaProjection? = null
    private var rtmpStream: RtmpStream? = null
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPipeline(); stopSelf() }
            ACTION_START -> if (rtmpStream == null) startPipeline(intent)
        }
        return START_NOT_STICKY
    }

    private fun startPipeline(intent: Intent) {
        // Reset before any operation so failures during permission/config/encoder setup
        // still run the complete cleanup path.
        stopped = false
        createChannel()
        val notification = buildNotification("YouTube Live तैयार हो रहा है…")
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else startForeground(NOTIFICATION_ID, notification)

        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                ?: error("MediaProjection permission data missing")
            val config = YouTubeRtmpConfig(
                intent.getStringExtra(EXTRA_SERVER).orEmpty(),
                intent.getStringExtra(EXTRA_KEY).orEmpty()
            )
            val publishUrl = config.validatedPublishUrl()

            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android MediaProjection could not be created")
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Handler(Looper.getMainLooper()).post {
                        stopPipeline()
                        stopSelf()
                    }
                }
            }, Handler(Looper.getMainLooper()))

            // RootEncoder owns MediaProjection screen capture and H.264 surface encoding.
            val metrics = resources.displayMetrics
            val rawW = metrics.widthPixels.coerceAtLeast(2)
            val rawH = metrics.heightPixels.coerceAtLeast(2)
            val scale = minOf(1f, 1280f / maxOf(rawW, rawH).toFloat())
            val width = ((rawW * scale).toInt() / 2) * 2
            val height = ((rawH * scale).toInt() / 2) * 2

            // MixAudioSource captures mic + permitted internal playback as PCM and feeds AAC encoder.
            val mixedAudio: AudioSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MixAudioSource(mp).apply {
                    microphoneVolume = 1.5f
                    internalVolume = 0.85f
                }
            } else {
                // Android 8/9 can stream mic audio; internal playback capture needs Android 10+.
                MicrophoneSource()
            }
            val stream = RtmpStream(
                applicationContext,
                this,
                ScreenSource(applicationContext, mp),
                mixedAudio
            )
            rtmpStream = stream

            check(stream.prepareVideo(width, height, 2_500_000, 30, 2, 0)) {
                "H.264 video encoder preparation failed on this device"
            }
            check(stream.prepareAudio(AUDIO_RATE, true, 128_000, true, true)) {
                "AAC audio encoder preparation failed on this device"
            }
            stream.startStream(publishUrl)
            updateNotification("YouTube RTMP से कनेक्ट हो रहा है…")
        } catch (e: Exception) {
            Log.e(TAG, "Live pipeline start failed", e)
            updateNotification("स्ट्रीम शुरू नहीं हुई: ${e.message ?: "त्रुटि"}")
            stopPipeline()
            stopSelf()
        }
    }

    private fun updateNotification(message: String) {
        val notification = buildNotification(message.take(120))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }


    private fun buildNotification(message: String): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 305, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("FF LIVE MIC")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "STOP LIVE", stopPendingIntent)
            .build()
    }

    private fun stopPipeline() {
        if (stopped) return
        stopped = true
        try { rtmpStream?.stopStream() } catch (e: Exception) { Log.w(TAG, "RTMP stop", e) }
        try { rtmpStream?.release() } catch (e: Exception) { Log.w(TAG, "Encoder release", e) }
        rtmpStream = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onConnectionStarted(url: String) { updateNotification("YouTube RTMP कनेक्शन शुरू…") }
    override fun onConnectionSuccess() { updateNotification("<LIVE> YouTube RTMP कनेक्टेड") }
    override fun onConnectionFailed(reason: String) {
        // Do not include the publish URL: it contains the private stream key.
        // Avoid logging callback text because some servers may echo endpoint details.
        updateNotification("RTMP कनेक्शन विफल — server URL/network जाँचें")
    }
    override fun onNewBitrate(bitrate: Long) { updateNotification("लाइव • ${bitrate / 1000} kbps") }
    override fun onDisconnect() { updateNotification("YouTube RTMP डिस्कनेक्ट हुआ") }
    override fun onAuthError() { updateNotification("YouTube ने stream key अस्वीकार की") }
    override fun onAuthSuccess() { updateNotification("YouTube RTMP प्रमाणीकरण सफल") }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "FF Live Mic streaming", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() { stopPipeline(); super.onDestroy() }
}
