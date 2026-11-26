package com.openvcam.core

import com.openvcam.R
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class FloatingControlService : Service() {

    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var panel: View? = null
    private var playButton: Button? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(1002, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildNotification(): Notification =
        Notification.Builder(this, Notifications.CHANNEL_CONTROL)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(getString(R.string.notification_control_title))
            .setContentText(getString(R.string.notification_control_text))
            .setOngoing(true)
            .build()

    private fun addOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val bubble = Button(this).apply {
            text = "VC"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = roundedDrawable("#FF6750A4")
            alpha = 0.92f
        }

        val title = TextView(this).apply {
            text = "OpenVCam controls"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(24, 18, 24, 12)
        }

        val grid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
        }

        fun addButton(label: String, action: String, value: Any? = null) {
            val b = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = roundedDrawable("#FF3A3A48")
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(6, 6, 6, 6)
                }
                setOnClickListener {
                    val cfg = ConfigStore.get(this@FloatingControlService).applyControl(action, value)
                    if (action == "togglePlay") refreshPlayLabel(cfg)
                }
            }
            if (action == "togglePlay") playButton = b
            grid.addView(b)
        }

        addButton("Play/Pause", "togglePlay")
        addButton("Virtual/Real", "toggleVirtual")
        addButton("Rotate", "rotate", 90)
        addButton("Mirror", "mirror")
        addButton("Zoom +", "zoomIn")
        addButton("Zoom -", "zoomOut")
        addButton("Speed +", "speedUp")
        addButton("Speed -", "speedDown")
        addButton("Reset", "reset")
        addButton("Main", "preset", 0)
        addButton("Preset 1", "preset", 1)
        addButton("Preset 2", "preset", 2)
        addButton("Preset 3", "preset", 3)
        addButton("Preset 4", "preset", 4)
        addButton("Audio", "audio", "video")
        addButton("Close", "__close")

        val closeButton = grid.getChildAt(grid.childCount - 1) as Button
        closeButton.setOnClickListener { stopSelf() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable("#E622222C")
            addView(title)
            addView(grid)
        }

        val panelView = ScrollView(this).apply {
            addView(card)
            visibility = View.GONE
        }
        panel = panelView

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(panelView)
            addView(bubble)
        }
        root = container

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 180
        }

        title.setOnTouchListener(dragListener(params, wm))
        bubble.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = kotlin.math.abs(event.rawX - initialTouchX) +
                            kotlin.math.abs(event.rawY - initialTouchY) > 12
                        if (!moved) {
                            val p = panelView
                            p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        wm.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(container, params)
        refreshPlayLabel(ConfigStore.get(this).snapshotJson())
    }

    private fun dragListener(
        params: WindowManager.LayoutParams,
        wm: WindowManager
    ): View.OnTouchListener = object : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    root?.let { wm.updateViewLayout(it, params) }
                    return true
                }
            }
            return false
        }
    }

    private fun refreshPlayLabel(json: String) {
        val playing = try {
            org.json.JSONObject(json).optBoolean("playing", true)
        } catch (_: Throwable) {
            true
        }
        playButton?.text = if (playing) "Pause" else "Play"
    }

    private fun roundedDrawable(color: String): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.parseColor(color))
            cornerRadius = 20f
        }

    override fun onDestroy() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
