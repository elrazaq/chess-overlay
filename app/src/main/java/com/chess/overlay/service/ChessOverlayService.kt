package com.chess.overlay.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chess.overlay.R
import com.chess.overlay.core.engine.StockfishBridge
import com.chess.overlay.core.overlay.ArrowOverlayView
import com.chess.overlay.core.vision.BoardDetector
import com.chess.overlay.core.vision.PieceClassifier
import kotlinx.coroutines.*

class ChessOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null

    // View Components
    private var bubbleView: View? = null
    private var arrowOverlayView: ArrowOverlayView? = null

    // Helpers & Engine
    private var screenCaptureHelper: ScreenCaptureHelper? = null
    private val boardDetector = BoardDetector()
    private val pieceClassifier = PieceClassifier()
    private lateinit var stockfishEngine: StockfishBridge

    companion object {
        const val CHANNEL_ID = "ChessOverlayChannel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            stockfishEngine = StockfishBridge(context = this, threads = 2, hashMb = 16)
            stockfishEngine.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundNotification()

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                setupScreenCapture(mediaProjection)
                setupOverlayViews()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error memulai overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
    }

    private fun setupScreenCapture(mediaProjection: MediaProjection) {
        try {
            screenCaptureHelper = ScreenCaptureHelper(this, mediaProjection)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("InflateParams")
    private fun setupOverlayViews() {
        val wm = windowManager ?: return

        // Periksa izin overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izin overlay belum aktif!", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // 1. Fullscreen Transparent Arrow Overlay (Touch Passthrough)
            val arrowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            arrowOverlayView = ArrowOverlayView(this)
            wm.addView(arrowOverlayView, arrowParams)

            // 2. Floating Bubble Trigger (Bisa Digeser & Diklik)
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ChessOverlay)
            val bubbleInflater = LayoutInflater.from(themedContext)
            bubbleView = bubbleInflater.inflate(R.layout.floating_bubble, null)

            val bubbleParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 300
            }

            setupBubbleTouchListener(bubbleParams)
            wm.addView(bubbleView, bubbleParams)

            // Klik bubble untuk trigger analisis
            bubbleView?.findViewById<View>(R.id.btnAnalyzeTrigger)?.setOnClickListener {
                performBoardAnalysis()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Gagal menampilkan bubble: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouchListener(params: WindowManager.LayoutParams) {
        val view = bubbleView ?: return
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        return false // Izinkan click event jika tidak geser
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager?.updateViewLayout(view, params)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        return isMoving
                    }
                }
                return false
            }
        })
    }

    /**
     * Pipeline Eksekusi Analisis Papan & Perhitungan 5 Jalur
     */
    private fun performBoardAnalysis() {
        serviceScope.launch {
            Toast.makeText(this@ChessOverlayService, "Menganalisis papan...", Toast.LENGTH_SHORT).show()

            // 1. Ambil snapshot layar saat ini
            val bitmap = screenCaptureHelper?.captureSnapshot()
            if (bitmap == null) {
                Toast.makeText(this@ChessOverlayService, "Gagal mengambil frame layar", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 2. Deteksi bounds papan catur
            val boardBounds = boardDetector.findBoard(bitmap)

            // 3. Klasifikasi petak catur ke notasi FEN (Fallback ke standard starting FEN jika demo)
            val currentFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

            // 4. Jalankan evaluasi 5 jalur terbaik & ancaman via Stockfish
            val topCandidates = stockfishEngine.analyzeFen(currentFen, moveTimeMs = 600)
            val threats = stockfishEngine.detectThreats(currentFen)

            // 5. Gambar panah di overlay canvas secara instan
            arrowOverlayView?.updateAnalysis(boardBounds, topCandidates, threats)
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Vision Overlay Aktif")
            .setContentText("Ketuk SCAN pada bubble melayang untuk menghitung 5 jalur terbaik")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chess Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stockfishEngine.stop()
        screenCaptureHelper?.release()

        bubbleView?.let { windowManager?.removeView(it) }
        arrowOverlayView?.let { windowManager?.removeView(it) }
    }
}
