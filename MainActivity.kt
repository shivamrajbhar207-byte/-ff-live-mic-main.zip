package com.fflivemic.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 4401
    private lateinit var status: TextView
    private lateinit var serverInput: EditText
    private lateinit var keyInput: EditText
    private var pendingConfig: YouTubeRtmpConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101010.toInt()
        window.navigationBarColor = 0xFF101010.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 36, 24, 24)
            setBackgroundColor(0xFF101010.toInt())
        }
        root.addView(TextView(this).apply {
            text = "🔥 FF LIVE MIC"
            textSize = 30f; setTextColor(-1)
        })
        root.addView(TextView(this).apply {
            text = "YouTube Live • H.264 + Mixed AAC • Phase 0.8"
            textSize = 13f; setTextColor(0xFFFF8A50.toInt())
            setPadding(0, 8, 0, 20)
        })
        status = TextView(this).apply {
            text = "तैयार — YouTube RTMP details भरें"
            textSize = 15f; setTextColor(-1)
            setPadding(0, 8, 0, 16)
        }
        root.addView(status)
        root.addView(label("YouTube Server URL"))
        serverInput = EditText(this).apply {
            setSingleLine(true)
            setText("rtmp://a.rtmp.youtube.com/live2")
            hint = "rtmp://a.rtmp.youtube.com/live2"
            setTextColor(-1); setHintTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(serverInput, fieldParams())
        root.addView(label("YouTube Stream Key"))
        keyInput = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "YouTube Studio से stream key"
            setTextColor(-1); setHintTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(keyInput, fieldParams())
        root.addView(Button(this).apply {
            text = "START YOUTUBE LIVE"
            setOnClickListener { validateAndRequestCapture() }
        })
        root.addView(Button(this).apply {
            text = "STOP LIVE STREAM"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                status.text = "स्ट्रीम बंद करने का अनुरोध भेजा गया"
            }
        })
        root.addView(TextView(this).apply {
            text = "कैसे इस्तेमाल करें:\n1. YouTube Studio में Live Control Room खोलें।\n2. Server URL और Stream Key यहाँ भरें।\n3. Start दबाकर Android screen-capture अनुमति दें।\n4. Free Fire खोलें; YouTube Studio में Preview जाँचकर Go Live करें।\n\nनोट: गेम का internal audio Android 10+ और गेम की capture policy पर निर्भर है। कुछ गेम playback capture रोकते हैं। Stream key निजी रखें।"
            textSize = 13f; setTextColor(0xFFFFCC80.toInt())
            setPadding(0, 20, 0, 0)
        })
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(0xFFFFCC80.toInt())
        gravity = Gravity.START
    }

    private fun fieldParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 12 }

    private fun validateAndRequestCapture() {
        try {
            pendingConfig = YouTubeRtmpConfig(serverInput.text.toString(), keyInput.text.toString()).also {
                it.validatedPublishUrl()
            }
        } catch (e: IllegalArgumentException) {
            status.text = e.message ?: "RTMP details जाँचें"
            return
        }
        val missingPermissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissions(missingPermissions.toTypedArray(), 4402)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4402) {
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                // Notification permission is optional for capture; Android still shows the
                // foreground-service notice in system UI when notification permission is denied.
                requestScreenCapture()
            } else {
                status.text = "माइक अनुमति अस्वीकार — streaming शुरू नहीं हो सकती"
            }
        }
    }

    @Deprecated("MediaProjection consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val config = pendingConfig ?: run { status.text = "RTMP configuration गायब"; return }
                val service = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_DATA, data)
                    putExtra(CaptureService.EXTRA_SERVER, config.serverUrl)
                    putExtra(CaptureService.EXTRA_KEY, config.streamKey)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
                status.text = "स्ट्रीम शुरू हो रही है — YouTube Studio Preview जाँचें"
                keyInput.text.clear()
                pendingConfig = null
            } else status.text = "स्क्रीन अनुमति रद्द — Streaming शुरू नहीं हुई"
        }
    }
}
