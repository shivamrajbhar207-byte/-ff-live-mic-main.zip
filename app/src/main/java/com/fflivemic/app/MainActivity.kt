
package com.fflivemic.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : Activity() {

    companion object {
        private const val TAG = "FFLiveMicAuth"
        private const val CAPTURE_REQUEST = 4401
        private const val YOUTUBE_AUTH_REQUEST = 4403
        private const val PERMISSION_REQUEST = 4402
        private const val BACKGROUND_REQUEST = 4404
        private const val BACKGROUND_FILE = "custom_bg.jpg"

        private const val YOUTUBE_SCOPE =
            "https://www.googleapis.com/auth/youtube.readonly"
    }

    private lateinit var status: TextView
    private lateinit var bgImage: ImageView
    private lateinit var bgScrim: View
    private lateinit var serverInput: EditText
    private lateinit var keyInput: EditText

    private lateinit var qualitySpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var shortsStyleSpinner: Spinner

    private lateinit var chatLog: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var liveStatsText: TextView
    private lateinit var chatButton: Button
    private lateinit var streamChatButton: Button

    private var pendingConfig: YouTubeRtmpConfig? = null
    private var youtubeAccessToken: String? = null

    private val chatClient = YouTubeLiveChatClient()

    private var chatEnabled = false
    private var streamChatEnabled = false
    private var authorizationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = 0xFF101010.toInt()
        window.navigationBarColor = 0xFF101010.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }

        root.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ff_logo)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "FF Live Mic"
            },
            LinearLayout.LayoutParams(dp(180), dp(180))
        )

        root.addView(TextView(this).apply {
            text = "YouTube Live • H.264 + Mixed AAC"
            textSize = 13f
            setTextColor(0xFFFF8A50.toInt())
            setPadding(0, 8, 0, 20)
        })

        status = TextView(this).apply {
            text = "तैयार — YouTube अकाउंट कनेक्ट करें"
            textSize = 15f
            setTextColor(-1)
            setPadding(0, 8, 0, 16)
        }

        root.addView(status)

        root.addView(makeButton(BtnStyle.PURPLE).apply {
            text = "CONNECT YOUTUBE ACCOUNT"
            setOnClickListener {
                connectYouTubeAccount()
            }
        })

        root.addView(label("YouTube Server URL"))

        serverInput = EditText(this).apply {
            setSingleLine(true)
            setText("rtmp://a.rtmp.youtube.com/live2")
            hint = "YouTube Server URL"
            setTextColor(-1)
            setHintTextColor(0xFFAAAAAA.toInt())
            styleField()
        }

        root.addView(serverInput, fieldParams())

        root.addView(label("YouTube Stream Key"))

        keyInput = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "YouTube Studio से Stream Key"
            setTextColor(-1)
            setHintTextColor(0xFFAAAAAA.toInt())
            styleField()
        }

        root.addView(keyInput, fieldParams())

        // VIDEO QUALITY

        root.addView(label("VIDEO QUALITY"))

        qualitySpinner = spinner(
            listOf(
                "Auto (recommended)",
                "720p",
                "1080p"
            )
        )

        root.addView(qualitySpinner, fieldParams())

        // STREAM MODE

        root.addView(label("STREAM MODE"))

        modeSpinner = spinner(
            listOf(
                "LONG LIVE - Landscape 16:9",
                "SHORTS LIVE - Portrait 9:16"
            )
        )

        root.addView(modeSpinner, fieldParams())

        // SHORTS STYLE

        root.addView(label("SHORTS LIVE STYLE"))

        shortsStyleSpinner = spinner(
            listOf(
                "Full Portrait Crop",
                "Full Game View"
            )
        )

        root.addView(shortsStyleSpinner, fieldParams())

        modeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    shortsStyleSpinner.isEnabled = position == 1
                    shortsStyleSpinner.alpha =
                        if (position == 1) 1f else 0.45f
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {
                }
            }

        // START LIVE

        root.addView(makeButton(BtnStyle.GO).apply {
            text = "🚀 START YOUTUBE LIVE"
            setOnClickListener {
                validateAndRequestCapture()
            }
        })

        // STOP LIVE

        root.addView(makeButton(BtnStyle.STOP).apply {
            text = "STOP LIVE STREAM"

            setOnClickListener {
                val stopIntent = Intent(
                    this@MainActivity,
                    CaptureService::class.java
                ).setAction(CaptureService.ACTION_STOP)

                startService(stopIntent)
                status.text = "स्ट्रीम बंद करने का अनुरोध भेजा गया"
            }
        })

        // APP BACKGROUND

        root.addView(label("APP BACKGROUND"))

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL

                val change = makeButton(BtnStyle.GHOST).apply {
                    text = "🖼 PHOTO चुनें"
                    setOnClickListener { pickBackground() }
                }
                val reset = makeButton(BtnStyle.GHOST).apply {
                    text = "RESET"
                    setOnClickListener { resetBackground() }
                }

                addView(change, LinearLayout.LayoutParams(0, dp(52), 2f).apply {
                    rightMargin = dp(8)
                })
                addView(reset, LinearLayout.LayoutParams(0, dp(52), 1f))
            },
            fieldParams()
        )

        // LIVE CHAT

        root.addView(label("YOUTUBE LIVE CHAT"))

        chatButton = makeButton(BtnStyle.BLUE).apply {
            text = "APP CHAT ON"

            setOnClickListener {
                if (chatEnabled) {
                    stopAppChat()
                } else {
                    startAppChat()
                }
            }
        }

        root.addView(chatButton, fieldParams())

        streamChatButton = makeButton(BtnStyle.BLUE).apply {
            text = "STREAM CHAT OFF"

            setOnClickListener {
                setStreamChat(!streamChatEnabled)
            }
        }

        root.addView(streamChatButton, fieldParams())
        // FLOATING CHAT PERMISSION

       root.addView(makeButton(BtnStyle.GHOST).apply {
           text = "ENABLE FLOATING CHAT"
           setOnClickListener {
    requestFloatingChatPermission()
           }
       }, fieldParams())

        chatScroll = ScrollView(this).apply {
            background = roundedBg(0xB3101018.toInt(), dp(14), 0x33FFFFFF)
        }

        chatLog = TextView(this).apply {
            text = "Live Chat बंद है।"
            textSize = 14f
            setTextColor(-1)
            setPadding(12, 12, 12, 12)
        }

        chatScroll.addView(chatLog)

        root.addView(
            chatScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(260)
            )
        )

        // LIVE VIEWERS AND LIKES

        liveStatsText = TextView(this).apply {
            text = "👥 लाइव दर्शक: —     👍 लाइक: —"
            textSize = 16f
            setTextColor(0xFFFFCC80.toInt())
            setPadding(12, 16, 12, 16)
            background = roundedBg(0xB3101018.toInt(), dp(14), 0x33FFFFFF)
        }

        root.addView(liveStatsText, fieldParams())

        root.addView(TextView(this).apply {
            text =
                "1. YouTube अकाउंट कनेक्ट करें।\n" +
                "2. Server URL और Stream Key भरें।\n" +
                "3. Video Quality चुनें।\n" +
                "4. LONG LIVE या SHORTS LIVE चुनें।\n" +
                "5. Shorts में Crop या Full Game View चुनें।\n" +
                "6. Start दबाकर Screen Capture अनुमति दें।\n" +
                "7. APP CHAT ON से चैट प्राप्त करें।\n" +
                "8. STREAM CHAT ON से Overlay संदेश भेजें।\n\n" +
                "Stream Key और Access Token निजी रखें।"

            textSize = 13f
            setTextColor(0xFFFFCC80.toInt())
            setPadding(0, 20, 0, 0)
        })

        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is Button) child.layoutParams = fieldParams()
        }

        bgImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        bgScrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
        }

        val frame = FrameLayout(this)
        frame.addView(bgImage, FrameLayout.LayoutParams(-1, -1))
        frame.addView(bgScrim, FrameLayout.LayoutParams(-1, -1))
        frame.addView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(root)
            },
            FrameLayout.LayoutParams(-1, -1)
        )

        setContentView(frame)
        applyBackground()
    }

    // ---------------- YOUTUBE AUTHORIZATION ----------------

    private fun connectYouTubeAccount() {

        if (authorizationInProgress) {
            status.text = "Google अनुमति पहले से खुली है"
            return
        }

        authorizationInProgress = true
        youtubeAccessToken = null

        status.text = "Google अकाउंट की अनुमति माँगी जा रही है..."

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(Scope(YOUTUBE_SCOPE))
            )
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->

                if (isFinishing || isDestroyed) {
                    authorizationInProgress = false
                    return@addOnSuccessListener
                }

                if (result.hasResolution()) {

                    val pendingIntent = result.pendingIntent

                    if (pendingIntent == null) {
                        authorizationInProgress = false
                        status.text =
                            "Google ने अनुमति स्क्रीन नहीं दी। दोबारा प्रयास करें।"
                        return@addOnSuccessListener
                    }

                    try {
                        status.text =
                            "Google अकाउंट चुनें और अनुमति पूरी करें..."

                        startIntentSenderForResult(
                            pendingIntent.intentSender,
                            YOUTUBE_AUTH_REQUEST,
                            null,
                            0,
                            0,
                            0
                        )

                    } catch (e: Exception) {

                        authorizationInProgress = false
                        Log.e(TAG, "Could not open Google authorization", e)

                        status.text =
                            "Google स्क्रीन नहीं खुली: ${e.message}"
                    }

                } else {
                    authorizationInProgress = false
                    acceptAuthorizationResult(result.accessToken)
                }
            }
            .addOnFailureListener { error ->

                authorizationInProgress = false
                Log.e(TAG, "Authorization request failed", error)

                status.text =
                    "Google Login Error: " +
                    (error.message ?: error.javaClass.simpleName)
            }
    }

    private fun acceptAuthorizationResult(token: String?) {

        if (token.isNullOrBlank()) {
            youtubeAccessToken = null
            status.text =
                "Google से Access Token नहीं मिला। अनुमति दोबारा दें।"
            return
        }

        youtubeAccessToken = token

        status.text =
            "YouTube अकाउंट कनेक्ट हो गया! अब APP CHAT चालू कर सकते हैं।"

        appendChat("YouTube अकाउंट कनेक्ट हो गया।")
    }

    @Deprecated("Permission callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == BACKGROUND_REQUEST) {
            if (resultCode == RESULT_OK && data?.data != null) {
                saveBackground(data.data!!)
            }
            return
        }

        // GOOGLE AUTH CALLBACK

        if (requestCode == YOUTUBE_AUTH_REQUEST) {

            authorizationInProgress = false

            Log.i(
                TAG,
                "Google authorization callback. Result code: $resultCode, " +
                    "Intent available: ${data != null}"
            )

            if (data == null) {
                youtubeAccessToken = null

                status.text =
                    "Google ने परिणाम नहीं भेजा। Result code: $resultCode. " +
                    "दोबारा Connect करें।"

                Log.e(TAG, "Google authorization returned null Intent")
                return
            }

            try {

                val result = Identity
                    .getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data)

                Log.i(
                    TAG,
                    "Google authorization returned. Granted scopes: " +
                        result.grantedScopes.joinToString()
                )

                acceptAuthorizationResult(result.accessToken)

            } catch (e: ApiException) {

                youtubeAccessToken = null
                Log.e(TAG, "Google authorization API error", e)

                status.text =
                    "Google Login Error ${e.statusCode}: " +
                    (e.message ?: "अनुमति पूरी नहीं हुई") +
                    " (Result: $resultCode)"

            } catch (e: Exception) {

                youtubeAccessToken = null
                Log.e(TAG, "Authorization result processing failed", e)

                status.text =
                    "Login परिणाम पढ़ने में समस्या: " +
                    (e.message ?: e.javaClass.simpleName)
            }

            return
        }

        // SCREEN CAPTURE CALLBACK

        if (requestCode == CAPTURE_REQUEST) {

            if (resultCode == RESULT_OK && data != null) {

                val config = pendingConfig ?: run {
                    status.text = "RTMP configuration गायब"
                    return
                }

                val selectedQuality =
                    qualitySpinner.selectedItemPosition

                val selectedMode =
                    if (modeSpinner.selectedItemPosition == 0) {
                        CaptureService.MODE_LONG
                    } else if (shortsStyleSpinner.selectedItemPosition == 0) {
                        CaptureService.MODE_CROP
                    } else {
                        CaptureService.MODE_FIT
                    }

                val service = Intent(
                    this,
                    CaptureService::class.java
                ).apply {

                    action = CaptureService.ACTION_START

                    putExtra(
                        CaptureService.EXTRA_RESULT_CODE,
                        resultCode
                    )

                    putExtra(
                        CaptureService.EXTRA_DATA,
                        data
                    )

                    putExtra(
                        CaptureService.EXTRA_SERVER,
                        config.serverUrl
                    )

                    putExtra(
                        CaptureService.EXTRA_KEY,
                        config.streamKey
                    )

                    putExtra(
                        CaptureService.EXTRA_QUALITY,
                        when (selectedQuality) {
                            0 -> CaptureService.QUALITY_AUTO
                            1 -> CaptureService.QUALITY_HD
                            else -> CaptureService.QUALITY_ULTRA
                        }
                    )

                    putExtra(
                        CaptureService.EXTRA_STREAM_MODE,
                        selectedMode
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(service)
                } else {
                    startService(service)
                }

                status.text =
                    "स्ट्रीम शुरू हो रही है — YouTube Studio Preview जाँचें"

                keyInput.text.clear()
                pendingConfig = null

            } else {
                status.text =
                    "स्क्रीन अनुमति रद्द — Streaming शुरू नहीं हुई"
            }
        }
    }
    // ---------------- FLOATING CHAT PERMISSION ----------------

private fun requestFloatingChatPermission() {

    if (android.provider.Settings.canDrawOverlays(this)) {
        status.text = "Floating Chat की अनुमति पहले से चालू है।"
        return
    }

    try {
        val permissionIntent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )

        startActivity(permissionIntent)

    } catch (e: Exception) {
        Log.e(TAG, "Floating Chat permission error", e)
        status.text = "Floating Chat Permission स्क्रीन नहीं खुली।"
    }
}

override fun onResume() {
    super.onResume()

    if (::status.isInitialized &&
        android.provider.Settings.canDrawOverlays(this)
    ) {
        status.text = "Floating Chat की अनुमति चालू है।"
    }
}
    // ---------------- LIVE CHAT ----------------

    private fun setStreamChat(enabled: Boolean) {

        streamChatEnabled = enabled
        StreamChatBus.setOverlayEnabled(enabled)

        streamChatButton.text =
            if (enabled) {
                "STREAM CHAT ON"
            } else {
                "STREAM CHAT OFF"
            }

        status.text =
            if (enabled) {
                "Stream Chat Overlay संदेश भेजना चालू"
            } else {
                "Stream Chat Overlay संदेश भेजना बंद"
            }
    }

    private fun startAppChat() {

        val token = youtubeAccessToken

        if (token.isNullOrBlank()) {
            status.text = "पहले Connect YouTube Account करें"
            return
        }

        chatEnabled = true
        chatButton.text = "APP CHAT OFF"
        chatLog.text = "YouTube Live Chat कनेक्ट हो रही है...\n"

        liveStatsText.text = "👥 लाइव दर्शक: —     👍 लाइक: —"

        chatClient.start(
            token,
            object : YouTubeLiveChatClient.Listener {

                override fun onStatus(statusText: String) {
                    runOnUiThread {
                        status.text = statusText
                        appendChat(statusText)
                    }
                }

                override fun onMessage(
                    message: YouTubeLiveChatClient.ChatMessage
                ) {
                    appendChat(
                        "${message.author}: ${message.message}"
                    )

                    StreamChatBus.publish(
                        message.author,
                        message.message
                    )
                }

                override fun onStats(
                    stats: YouTubeLiveChatClient.LiveStats
                ) {
                    runOnUiThread {
                        val viewers = stats.viewers?.toString() ?: "—"
                        val likes = stats.likes?.toString() ?: "—"

                        liveStatsText.text =
                            "👥 लाइव दर्शक: $viewers     👍 लाइक: $likes"
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        status.text = "Chat Error: $error"
                        appendChat("त्रुटि: $error")

                        chatEnabled = false
                        chatButton.text = "APP CHAT ON"
                    }
                }
            }
        )
    }

    private fun stopAppChat() {

        chatClient.stop()

        chatEnabled = false
        chatButton.text = "APP CHAT ON"

        status.text = "Live Chat बंद कर दी गई"
        appendChat("Live Chat बंद है।")
    }

    private fun appendChat(message: String) {

        runOnUiThread {

            if (isFinishing) return@runOnUiThread

            val lines =
                (chatLog.text.toString() + "\n" + message)
                    .lines()
                    .takeLast(100)

            chatLog.text = lines.joinToString("\n")

            chatScroll.post {
                chatScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private enum class BtnStyle(val start: Int, val end: Int) {
        GO(0xFFFF6E28.toInt(), 0xFFE53935.toInt()),
        STOP(0xFF8E0000.toInt(), 0xFFD50000.toInt()),
        PURPLE(0xFF7C4DFF.toInt(), 0xFF5E35B1.toInt()),
        BLUE(0xFF2979FF.toInt(), 0xFF1565C0.toInt()),
        GHOST(0x66000000, 0x66000000)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundedBg(fill: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun makeButton(style: BtnStyle): Button {
        return Button(this).apply {
            val shape = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(style.start, style.end)
            ).apply {
                cornerRadius = dp(14).toFloat()
                if (style == BtnStyle.GHOST) setStroke(dp(1), 0xAAFFFFFF.toInt())
            }
            background = RippleDrawable(
                ColorStateList.valueOf(0x55FFFFFF),
                shape,
                null
            )
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            minHeight = dp(52)
            minimumHeight = dp(52)
            stateListAnimator = null
            elevation = dp(2).toFloat()
        }
    }

    private fun EditText.styleField() {
        background = roundedBg(0xB3101018.toInt(), dp(12), 0x55FFFFFF)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    // ---------------- CUSTOM BACKGROUND ----------------

    private fun backgroundFile() = File(filesDir, BACKGROUND_FILE)

    private fun pickBackground() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent.createChooser(intent, "Background photo चुनें"),
                BACKGROUND_REQUEST
            )
        } catch (e: Exception) {
            Log.e(TAG, "Background picker failed", e)
            status.text = "Photo picker नहीं खुला"
        }
    }

    private fun saveBackground(uri: Uri) {
        status.text = "Background सेट हो रहा है…"
        Thread {
            val ok = try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                if (bmp == null) {
                    false
                } else {
                    backgroundFile().outputStream().use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, it)
                    }
                    bmp.recycle()
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background save failed", e)
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                status.text =
                    if (ok) "Background बदल दिया गया" else "Photo लोड नहीं हो सकी"
                applyBackground()
            }
        }.start()
    }

    private fun resetBackground() {
        backgroundFile().delete()
        applyBackground()
        status.text = "Default background वापस लगाया गया"
    }

    private fun applyBackground() {
        val file = backgroundFile()
        val bmp = if (file.exists()) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else null

        if (bmp != null) {
            bgImage.setImageBitmap(bmp)
            bgScrim.visibility = View.VISIBLE
        } else {
            bgImage.setImageDrawable(
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFF2A0F4D.toInt(), 0xFF120B24.toInt(), 0xFF07070C.toInt())
                )
            )
            bgScrim.visibility = View.GONE
        }
    }

    // ---------------- UI HELPERS ----------------

    private fun spinner(items: List<String>): Spinner {

        return Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                items
            ) {
                override fun getView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View {
                    return (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.WHITE)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }
                }
            }.also {
                it.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item
                )
            }
            background = roundedBg(0xB3101018.toInt(), dp(12), 0x55FFFFFF)
        }
    }

    private fun label(value: String): TextView {

        return TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(0xFFFFCC80.toInt())
            gravity = Gravity.START
            setPadding(dp(2), dp(6), 0, dp(4))
        }
    }

    private fun fieldParams() =
        LinearLayout.LayoutParams(
            
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = dp(10)
    }

    // ---------------- SCREEN CAPTURE ----------------

    private fun validateAndRequestCapture() {

        try {
            pendingConfig = YouTubeRtmpConfig(
                serverInput.text.toString(),
                keyInput.text.toString()
            ).also {
                it.validatedPublishUrl()
            }

        } catch (e: IllegalArgumentException) {
            status.text = e.message ?: "RTMP details जाँचें"
            return
        }

        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            requiredPermissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val missingPermissions = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST
            )
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        try {
            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            status.text = "स्क्रीन रिकॉर्डिंग की अनुमति दें..."

            startActivityForResult(
                manager.createScreenCaptureIntent(),
                CAPTURE_REQUEST
            )

        } catch (e: Exception) {
            Log.e(TAG, "Screen capture permission failed", e)

            status.text =
                "Screen Capture Error: ${e.message ?: "दोबारा प्रयास करें"}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == PERMISSION_REQUEST) {

            val allGranted =
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }

            if (allGranted) {
                requestScreenCapture()
            } else {
                status.text =
                    "Microphone और Notification की जरूरी अनुमति दें।"
            }
        }
    }
}
