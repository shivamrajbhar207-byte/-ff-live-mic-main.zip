
package com.fflivemic.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class FloatingChatOverlay(
    private val context: Context
) {

    companion object {
        private const val TAG = "FFFloatingChat"
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val windowType =
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private var chatPanel: LinearLayout? = null
    private var bubble: TextView? = null
    private var chatText: TextView? = null

    private var chatParams: WindowManager.LayoutParams? = null

    private var visible = false
    private var closed = false
    private var scale = 1.0f

    private val messages = mutableListOf<String>()

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density)
            .roundToInt()
    }

    private fun background(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }
    }

    fun show() {
        if (closed || visible) return

        if (!Settings.canDrawOverlays(context)) {
            showError("Display over other apps अनुमति बंद है।")
            return
        }

        try {
            removeBubble()

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = this@FloatingChatOverlay.background(
                    Color.argb(115, 0, 0, 0)
                )
            }

            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val title = TextView(context).apply {
                text = "☰ LIVE CHAT"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(4), dp(4), dp(8), dp(4))
            }

            val hideButton = Button(context).apply {
                text = "Hide"
                textSize = 10f
                setOnClickListener {
                    hide()
                }
            }

            header.addView(
                title,
                LinearLayout.LayoutParams(0, dp(42), 1f)
            )

            header.addView(
                hideButton,
                LinearLayout.LayoutParams(dp(65), dp(42))
            )

            val text = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(4))
                this.text = messages.joinToString("\n")
                    .ifBlank { "अभी कोई चैट संदेश नहीं है।" }
            }

            panel.addView(header)
            panel.addView(text)

            chatPanel = panel
            chatText = text

            val params = WindowManager.LayoutParams(
                dp(270),
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(100)
            }

            chatParams = params

            // केवल LIVE CHAT शीर्षक को खींचने/resize करने के लिए।
            setupTouch(title, panel)

            windowManager.addView(panel, params)
            visible = true

            Log.i(TAG, "Floating chat panel displayed")

        } catch (e: Exception) {
            visible = false
            chatPanel = null
            chatText = null
            chatParams = null

            Log.e(TAG, "Overlay show failed", e)

            showError(
                "Floating Chat Error: ${e.message ?: "Unknown error"}"
            )
        }
    }

    private fun setupTouch(dragView: View, panel: View) {

        val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    scale = (scale * detector.scaleFactor)
                        .coerceIn(0.65f, 1.8f)

                    panel.scaleX = scale
                    panel.scaleY = scale

                    return true
                }
            }
        )

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        dragView.setOnTouchListener { _, event ->

            detector.onTouchEvent(event)

            val params = chatParams
                ?: return@setOnTouchListener true

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!detector.isInProgress &&
                        event.pointerCount == 1
                    ) {
                        params.x = startX +
                            (event.rawX - touchX).roundToInt()

                        params.y = startY +
                            (event.rawY - touchY).roundToInt()

                        try {
                            windowManager.updateViewLayout(
                                panel,
                                params
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Panel movement failed", e)
                        }
                    }
                }
            }

            true
        }
    }

    fun addMessage(author: String, message: String) {

        if (closed) return

        val safeAuthor = author
            .replace("\n", " ")
            .take(22)

        val safeMessage = message
            .replace("\n", " ")
            .take(100)

        mainHandler.post {
            if (closed) return@post

            messages.add("$safeAuthor: $safeMessage")

            while (messages.size > 8) {
                messages.removeAt(0)
            }

            chatText?.text = messages.joinToString("\n")
        }
    }

    fun hide() {
        if (closed) return

        removeChatPanel()
        visible = false
        showBubble()
    }

    private fun showBubble() {

        if (bubble != null || closed) return

        if (!Settings.canDrawOverlays(context)) {
            showError("Floating Chat अनुमति बंद है।")
            return
        }

        try {
            val button = TextView(context).apply {
                text = "CHAT"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))

                background = this@FloatingChatOverlay.background(
                    Color.argb(230, 180, 35, 20)
                )

                setOnClickListener {
                    show()
                }
            }

            val params = WindowManager.LayoutParams(
                dp(72),
                dp(48),
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(100)
            }

            windowManager.addView(button, params)

            bubble = button

            Log.i(TAG, "Floating chat bubble displayed")

        } catch (e: Exception) {
            bubble = null

            Log.e(TAG, "Bubble show failed", e)

            showError(
                "CHAT Button Error: ${e.message ?: "Unknown error"}"
            )
        }
    }

    private fun showError(message: String) {
        mainHandler.post {
            if (!closed) {
                Toast.makeText(
                    context,
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun removeChatPanel() {
        chatPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Panel removal failed", e)
            }
        }

        chatPanel = null
        chatText = null
        chatParams = null
    }

    private fun removeBubble() {
        bubble?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Bubble removal failed", e)
            }
        }

        bubble = null
    }

    fun close() {
        closed = true

        removeChatPanel()
        removeBubble()

        messages.clear()
        visible = false
    }
}
