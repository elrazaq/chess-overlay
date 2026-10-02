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
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chess.overlay.R
import com.chess.overlay.core.engine.StockfishBridge
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.MoveCandidate
import com.chess.overlay.core.model.Square
import com.chess.overlay.core.overlay.ArrowOverlayView
import kotlinx.coroutines.*

class ChessOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null

    // Overlay Views
    private var arrowOverlayView: ArrowOverlayView? = null
    private var panelView: View? = null
    private var arrowLayoutParams: WindowManager.LayoutParams? = null

    // Engine & Board State Virtual
    private val boardState = BoardState()
    private lateinit var stockfishEngine: StockfishBridge

    // State Pengaturan Papan & Kalibrasi
    private var isWhiteBottom = false // Default Hitam di bawah (sesuai perspektif user)
    private var isEngineRunning = false
    private var isPanelMinimized = false
    private var isCalibrationVisible = false

    // Kalibrasi posisi & ukuran papan
    private var boardTopY = 480f
    private var boardWidth = 1080f
    private var currentBoardBounds: BoardBounds? = null
    private var currentCandidates: List<MoveCandidate> = emptyList()

    // Temporary selection untuk tap gerak manual
    private var sourceSquare: Square? = null

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
        initScreenMetrics()

        try {
            stockfishEngine = StockfishBridge(context = this, threads = 2, hashMb = 16)
            stockfishEngine.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initScreenMetrics() {
        val wm = windowManager ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        boardWidth = metrics.widthPixels.toFloat()
        // Estimasi posisi atas papan catur di layar Android (di bawah header)
        boardTopY = (metrics.heightPixels - boardWidth) / 2.3f
        updateBoardBounds()
    }

    private fun updateBoardBounds() {
        currentBoardBounds = BoardBounds(
            left = 0f,
            top = boardTopY,
            size = boardWidth,
            isWhiteBottom = isWhiteBottom
        )
        arrowOverlayView?.boardBounds = currentBoardBounds
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForegroundNotification()
            setupOverlayViews()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error memulai overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }

        return START_NOT_STICKY
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

            // 1. Fullscreen Transparent Arrow Overlay
            arrowLayoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            arrowOverlayView = ArrowOverlayView(this).apply {
                boardBounds = currentBoardBounds
                boardState = this@ChessOverlayService.boardState
            }

            // Handler ketika petak disentuh (Mode Tap Gerak)
            arrowOverlayView?.onSquareTapped = { square ->
                handleSquareTapped(square)
            }

            wm.addView(arrowOverlayView, arrowLayoutParams)

            // 2. Floating Analysis & Control Panel
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
                y = 100
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
        val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)
        val btnToggleEngine = view.findViewById<Button>(R.id.btnToggleEngine)
        val btnMinimize = view.findViewById<TextView>(R.id.btnToggleMinimize)

        // Actions
        val btnApply = view.findViewById<Button>(R.id.btnApplyBestMove)
        val btnInput = view.findViewById<Button>(R.id.btnInputManual)
        val btnUndo = view.findViewById<Button>(R.id.btnUndoMove)
        val btnReset = view.findViewById<Button>(R.id.btnResetBoard)
        val btnFlip = view.findViewById<Button>(R.id.btnFlipBoard)
        val btnToggleCalib = view.findViewById<Button>(R.id.btnToggleCalibrate)
        val calibrationPanel = view.findViewById<View>(R.id.calibrationPanel)

        // Calibration buttons
        val btnUp = view.findViewById<Button>(R.id.btnMoveUp)
        val btnDown = view.findViewById<Button>(R.id.btnMoveDown)
        val btnPlus = view.findViewById<Button>(R.id.btnScalePlus)
        val btnMinus = view.findViewById<Button>(R.id.btnScaleMinus)

        btnFlip.text = if (isWhiteBottom) "🔄 Putih" else "🔄 Hitam"

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

        // Minimize / Expand
        btnMinimize.setOnClickListener {
            isPanelMinimized = !isPanelMinimized
            content.visibility = if (isPanelMinimized) View.GONE else View.VISIBLE
            btnMinimize.text = if (isPanelMinimized) "▲" else "▼"
        }

        // Toggle Engine Start / Pause
        btnToggleEngine.setOnClickListener {
            isEngineRunning = !isEngineRunning
            if (isEngineRunning) {
                btnToggleEngine.text = "⏸️ PAUSE"
                btnToggleEngine.setBackgroundColor(getColor(R.color.threat_arrow))
                calculateStockfishMoves()
            } else {
                btnToggleEngine.text = "▶️ START"
                btnToggleEngine.setBackgroundColor(getColor(R.color.accent))
                tvEngineTitle.text = "Engine Dijeda"
            }
        }

        // Terapkan Rekomendasi #1 Otomatis
        btnApply.setOnClickListener {
            val best = currentCandidates.firstOrNull()
            if (best != null) {
                boardState.makeMove(best.from, best.to)
                Toast.makeText(this, "Langkah diterapkan: ${best.from.toUci()} -> ${best.to.toUci()}", Toast.LENGTH_SHORT).show()
                if (isEngineRunning) {
                    calculateStockfishMoves()
                } else {
                    arrowOverlayView?.clearOverlay()
                }
            } else {
                Toast.makeText(this, "Belum ada rekomendasi langkah", Toast.LENGTH_SHORT).show()
            }
        }

        // Mode Input Langkah Manual di Papan
        btnInput.setOnClickListener {
            enableTouchInputMode(!arrowOverlayView!!.isInputMoveMode)
        }

        // Undo Move
        btnUndo.setOnClickListener {
            if (boardState.undoMove()) {
                Toast.makeText(this, "Langkah diurungkan (Undo)", Toast.LENGTH_SHORT).show()
                sourceSquare = null
                arrowOverlayView?.selectedSquare = null
                if (isEngineRunning) calculateStockfishMoves()
                else arrowOverlayView?.clearOverlay()
            }
        }

        // Reset Massal 32 Anak Catur ke Posisi Awal
        btnReset.setOnClickListener {
            boardState.resetToStartingPosition()
            sourceSquare = null
            arrowOverlayView?.selectedSquare = null
            Toast.makeText(this, "32 Bidak catur berhasil dipetakan ke posisi awal!", Toast.LENGTH_SHORT).show()
            if (isEngineRunning) calculateStockfishMoves()
            else arrowOverlayView?.clearOverlay()
        }

        // Balik Papan (Putih Bawah / Hitam Bawah)
        btnFlip.setOnClickListener {
            isWhiteBottom = !isWhiteBottom
            btnFlip.text = if (isWhiteBottom) "🔄 Putih" else "🔄 Hitam"
            updateBoardBounds()
            arrowOverlayView?.invalidate()
            if (isEngineRunning) calculateStockfishMoves()
        }

        // Toggle Kalibrasi Papan
        btnToggleCalib.setOnClickListener {
            isCalibrationVisible = !isCalibrationVisible
            calibrationPanel.visibility = if (isCalibrationVisible) View.VISIBLE else View.GONE
            arrowOverlayView?.isMappingMode = isCalibrationVisible
            arrowOverlayView?.invalidate()
        }

        // Kalibrasi Buttons
        btnUp.setOnClickListener {
            boardTopY -= 15f
            updateBoardBounds()
            arrowOverlayView?.invalidate()
        }
        btnDown.setOnClickListener {
            boardTopY += 15f
            updateBoardBounds()
            arrowOverlayView?.invalidate()
        }
        btnPlus.setOnClickListener {
            boardWidth += 15f
            updateBoardBounds()
            arrowOverlayView?.invalidate()
        }
        btnMinus.setOnClickListener {
            boardWidth -= 15f
            updateBoardBounds()
            arrowOverlayView?.invalidate()
        }
    }

    /**
     * Mengatur apakah sentuhan layar masuk ke overlay catur atau tembus ke game catur
     */
    private fun enableTouchInputMode(enable: Boolean) {
        val overlay = arrowOverlayView ?: return
        val params = arrowLayoutParams ?: return
        val btnInput = panelView?.findViewById<Button>(R.id.btnInputManual)

        overlay.isInputMoveMode = enable
        if (enable) {
            // Hilangkan FLAG_NOT_TOUCHABLE agar petak overlay bisa disentuh
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            btnInput?.text = "❌ Batal Tap"
            btnInput?.setBackgroundColor(getColor(R.color.threat_arrow))
            Toast.makeText(this, "Sentuh bidak asal lalu sentuh petak tujuan", Toast.LENGTH_SHORT).show()
        } else {
            // Aktifkan kembali FLAG_NOT_TOUCHABLE agar sentuhan tembus ke game catur
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            overlay.selectedSquare = null
            sourceSquare = null
            btnInput?.text = "🖐️ Tap Gerak"
            btnInput?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        windowManager?.updateViewLayout(overlay, params)
        overlay.invalidate()
    }

    /**
     * Logika sentuhan dua petak (From -> To) saat menggerakkan anak catur secara manual
     */
    private fun handleSquareTapped(square: Square) {
        if (sourceSquare == null) {
            // Tap pertama: pilih bidak asal
            val piece = boardState.getPiece(square)
            if (piece != null) {
                sourceSquare = square
                arrowOverlayView?.selectedSquare = square
                arrowOverlayView?.invalidate()
            } else {
                Toast.makeText(this, "Petak ${square.toUci()} kosong", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Tap kedua: petak tujuan
            val from = sourceSquare!!
            if (from == square) {
                // Batalkan seleksi
                sourceSquare = null
                arrowOverlayView?.selectedSquare = null
                arrowOverlayView?.invalidate()
                return
            }

            val moved = boardState.makeMove(from, square)
            if (moved) {
                Toast.makeText(this, "Gerak: ${from.toUci()} -> ${square.toUci()}", Toast.LENGTH_SHORT).show()
                // Otomatis kembalikan mode tembus layar agar tidak menghalangi
                enableTouchInputMode(false)

                if (isEngineRunning) {
                    calculateStockfishMoves()
                }
            } else {
                sourceSquare = null
                arrowOverlayView?.selectedSquare = null
                arrowOverlayView?.invalidate()
            }
        }
    }

    /**
     * Menjalankan kalkulasi Stockfish dari posisi FEN resmi BoardState
     */
    @SuppressLint("SetTextI18n")
    private fun calculateStockfishMoves() {
        serviceScope.launch {
            val view = panelView ?: return@launch
            val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)
            val tvLine1 = view.findViewById<TextView>(R.id.tvLine1)
            val tvLine2 = view.findViewById<TextView>(R.id.tvLine2)
            val tvLine3 = view.findViewById<TextView>(R.id.tvLine3)
            val tvLine4 = view.findViewById<TextView>(R.id.tvLine4)
            val tvLine5 = view.findViewById<TextView>(R.id.tvLine5)

            val fen = boardState.toFen()
            val sideText = if (boardState.isWhiteToMove) "Putih" else "Hitam"
            tvEngineTitle.text = "Stockfish 19 • Depth 15 • Giliran: $sideText"
            tvLine1.text = "Mengkalkulasi..."

            val candidates = stockfishEngine.analyzeFen(fen, moveTimeMs = 650)
            currentCandidates = candidates

            // Render 5 Jalur Terbaik di Panel
            val textViews = listOf(tvLine1, tvLine2, tvLine3, tvLine4, tvLine5)
            for (i in 0 until 5) {
                if (i < candidates.size) {
                    val cand = candidates[i]
                    val scoreText = if (cand.isMate) "M${cand.mateMoves}" else {
                        val sign = if (cand.scoreCp >= 0) "+" else ""
                        String.format("%s%.1f", sign, cand.scoreCp / 100.0)
                    }
                    val movesString = cand.pvLine.take(5).joinToString(" ")
                    textViews[i].text = "#${cand.rankOrder}  [$scoreText]  $movesString"
                    textViews[i].visibility = View.VISIBLE
                } else {
                    textViews[i].visibility = View.GONE
                }
            }

            // Render panah Cyan bersih di papan catur
            currentBoardBounds?.let { bounds ->
                arrowOverlayView?.updateAnalysis(bounds, candidates, emptyList())
            }
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Vision Overlay Aktif")
            .setContentText("Panel analisis & pemetaan catur aktif di layar")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
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

        panelView?.let { windowManager?.removeView(it) }
        arrowOverlayView?.let { windowManager?.removeView(it) }
    }
}
