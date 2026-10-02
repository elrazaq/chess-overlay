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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chess.overlay.R
import com.chess.overlay.core.engine.StockfishBridge
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.MoveCandidate
import com.chess.overlay.core.overlay.ArrowOverlayView
import com.chess.overlay.core.vision.BoardDetector
import com.chess.overlay.core.vision.PieceClassifier
import kotlinx.coroutines.*

class ChessOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null

    // Overlay Views
    private var arrowOverlayView: ArrowOverlayView? = null
    private var panelView: View? = null

    // Engine & Helpers
    private var screenCaptureHelper: ScreenCaptureHelper? = null
    private val boardDetector = BoardDetector()
    private val pieceClassifier = PieceClassifier()
    private lateinit var stockfishEngine: StockfishBridge

    // State Pengaturan Catur
    private var isWhiteBottom = false // Default Hitam di bawah (sesuai game yang sedang dimainkan user)
    private var isWhiteToMove = false // Default giliran Hitam
    private var isPanelMinimized = false
    private var lastBoardBounds: BoardBounds? = null
    private var lastCandidates: List<MoveCandidate> = emptyList()
    private var lastThreats: List<MoveCandidate> = emptyList()

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

            arrowOverlayView = ArrowOverlayView(this).apply {
                showOnlyBestMove = true // Default 1 panah bersih seperti Image 2
            }
            wm.addView(arrowOverlayView, arrowParams)

            // 2. Floating Analysis Panel (Mirip Sidebar di Image 2)
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_ChessOverlay)
            val panelInflater = LayoutInflater.from(themedContext)
            panelView = panelInflater.inflate(R.layout.floating_analysis_panel, null)

            val panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 120
            }

            setupPanelTouchAndControls(panelParams)
            wm.addView(panelView, panelParams)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Gagal memasang overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun setupPanelTouchAndControls(params: WindowManager.LayoutParams) {
        val view = panelView ?: return
        val header = view.findViewById<View>(R.id.panelHeader)
        val content = view.findViewById<View>(R.id.panelContent)
        val btnMinimize = view.findViewById<TextView>(R.id.btnToggleMinimize)
        val btnFlip = view.findViewById<Button>(R.id.btnFlipBoard)
        val btnTurn = view.findViewById<Button>(R.id.btnToggleTurn)
        val btnScan = view.findViewById<Button>(R.id.btnScanAction)

        // Inisialisasi teks tombol
        btnFlip.text = if (isWhiteBottom) "🔄 Putih" else "🔄 Hitam"
        btnTurn.text = if (isWhiteToMove) "⏱️ Giliran: ⚪" else "⏱️ Giliran: ⚫"

        // Drag panel via Header
        header.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

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
                        windowManager?.updateViewLayout(view, params)
                        return true
                    }
                }
                return false
            }
        })

        // Minimize / Expand Panel
        btnMinimize.setOnClickListener {
            isPanelMinimized = !isPanelMinimized
            content.visibility = if (isPanelMinimized) View.GONE else View.VISIBLE
            btnMinimize.text = if (isPanelMinimized) "▲" else "▼"
        }

        // Toggle Balik Papan (Putih Bawah / Hitam Bawah)
        btnFlip.setOnClickListener {
            isWhiteBottom = !isWhiteBottom
            btnFlip.text = if (isWhiteBottom) "🔄 Putih" else "🔄 Hitam"
            lastBoardBounds?.let {
                val updatedBounds = it.copy(isWhiteBottom = isWhiteBottom)
                lastBoardBounds = updatedBounds
                arrowOverlayView?.updateAnalysis(updatedBounds, lastCandidates, lastThreats)
            }
            Toast.makeText(this, "Orientasi: ${if (isWhiteBottom) "Putih di bawah" else "Hitam di bawah"}", Toast.LENGTH_SHORT).show()
        }

        // Toggle Giliran (Putih / Hitam)
        btnTurn.setOnClickListener {
            isWhiteToMove = !isWhiteToMove
            btnTurn.text = if (isWhiteToMove) "⏱️ Giliran: ⚪" else "⏱️ Giliran: ⚫"
            performBoardAnalysis()
        }

        // Eksekusi Scan
        btnScan.setOnClickListener {
            performBoardAnalysis()
        }
    }

    /**
     * Pipeline Analisis Papan & Perhitungan 5 Jalur Terbaik
     */
    @SuppressLint("SetTextI18n")
    private fun performBoardAnalysis() {
        serviceScope.launch {
            val view = panelView ?: return@launch
            val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)
            val tvLine1 = view.findViewById<TextView>(R.id.tvLine1)
            val tvLine2 = view.findViewById<TextView>(R.id.tvLine2)
            val tvLine3 = view.findViewById<TextView>(R.id.tvLine3)
            val tvLine4 = view.findViewById<TextView>(R.id.tvLine4)
            val tvLine5 = view.findViewById<TextView>(R.id.tvLine5)

            tvEngineTitle.text = "Menganalisis layar..."
            tvLine1.text = "Mengambil frame papan..."

            // 1. Ambil snapshot layar
            val bitmap = screenCaptureHelper?.captureSnapshot()
            if (bitmap == null) {
                tvLine1.text = "Gagal mengambil frame layar"
                return@launch
            }

            // 2. Deteksi batas presisi papan catur
            val boardBounds = boardDetector.findBoard(bitmap, isWhiteBottom)
            lastBoardBounds = boardBounds

            // 3. Ekstrak notasi FEN dari gambar petak
            val detectedFen = pieceClassifier.extractFenFromBoard(bitmap, boardBounds, isWhiteToMove)

            tvEngineTitle.text = "Stockfish 19 • Depth 14 • ${if (isWhiteToMove) "Putih" else "Hitam"}"

            // 4. Hitung 5 jalur terbaik & ancaman via Stockfish
            val topCandidates = stockfishEngine.analyzeFen(detectedFen, moveTimeMs = 700)
            val threats = stockfishEngine.detectThreats(detectedFen)

            lastCandidates = topCandidates
            lastThreats = threats

            // 5. Tampilkan daftar langkah di panel seperti Image 2
            val textViews = listOf(tvLine1, tvLine2, tvLine3, tvLine4, tvLine5)
            for (i in 0 until 5) {
                if (i < topCandidates.size) {
                    val cand = topCandidates[i]
                    val scoreText = if (cand.isMate) "M${cand.mateMoves}" else {
                        val sign = if (cand.scoreCp >= 0) "+" else ""
                        String.format("%s%.1f", sign, cand.scoreCp / 100.0)
                    }
                    val movesString = cand.pvLine.take(4).joinToString(" ")
                    textViews[i].text = "#${cand.rankOrder}  [$scoreText]  $movesString"
                    textViews[i].visibility = View.VISIBLE
                } else {
                    textViews[i].visibility = View.GONE
                }
            }

            // 6. Gambar panah bersih semi-transparan di atas papan catur
            arrowOverlayView?.updateAnalysis(boardBounds, topCandidates, threats)
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Vision Overlay Aktif")
            .setContentText("Panel analisis catur siap digunakan di layar")
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

        panelView?.let { windowManager?.removeView(it) }
        arrowOverlayView?.let { windowManager?.removeView(it) }
    }
}
