package com.chess.overlay.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.chess.overlay.R
import com.chess.overlay.core.engine.StockfishBridge
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.MoveCandidate
import com.chess.overlay.core.model.PieceType
import com.chess.overlay.core.model.Square
import com.chess.overlay.core.overlay.ArrowOverlayView
import com.chess.overlay.core.overlay.SetupBoardView
import com.chess.overlay.core.overlay.SetupTool
import kotlinx.coroutines.*

class ChessOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null

    // Overlay Views
    private var arrowOverlayView: ArrowOverlayView? = null
    private var panelView: View? = null
    private var arrowLayoutParams: WindowManager.LayoutParams? = null
    private var setupBoardView: SetupBoardView? = null

    // Engine & Virtual Board State
    private val boardState = BoardState()
    private lateinit var stockfishEngine: StockfishBridge
    private var currentCandidates: List<MoveCandidate> = emptyList()

    // Mode & Timer Jeda
    private var isEngineRunning = false
    private var isPanelMinimized = false
    private var isCalibrationVisible = false
    private var isWhiteBottom = false // Default Hitam di bawah (sesuai preferensi user)
    private var delayDurationSeconds = 3 // Default 3 detik jeda gerak bebas
    private var countdownJob: Job? = null
    private var isMappingModeActive = false

    // Kalibrasi posisi & ukuran papan
    private var boardTopY = 480f
    private var boardWidth = 1080f
    private var currentBoardBounds: BoardBounds? = null

    // Temporary selection untuk tap gerak manual di layar besar
    private var sourceSquare: Square? = null

    companion object {
        const val CHANNEL_ID = "ChessOverlayChannel"
        const val NOTIFICATION_ID = 1001
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
            // Default: FLAG_NOT_TOUCHABLE (sentuhan tembus 100% ke game catur)
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

            arrowOverlayView?.onSquareTapped = { square: Square ->
                handleSquareTapped(square)
            }
            arrowOverlayView?.onMoveDragged = { from: Square, to: Square ->
                executeMove(from, to)
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
                y = 80
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

        // Pengatur Jeda Detik
        val btnDelayMinus = view.findViewById<Button>(R.id.btnDelayMinus)
        val btnDelayPlus = view.findViewById<Button>(R.id.btnDelayPlus)
        val tvDelayDuration = view.findViewById<TextView>(R.id.tvDelayDuration)
        tvDelayDuration.text = "${delayDurationSeconds} dtk"

        btnDelayMinus.setOnClickListener {
            if (delayDurationSeconds > 1) {
                delayDurationSeconds--
                tvDelayDuration.text = "${delayDurationSeconds} dtk"
                Toast.makeText(this, "Jeda gerak bebas: ${delayDurationSeconds} detik", Toast.LENGTH_SHORT).show()
            }
        }

        btnDelayPlus.setOnClickListener {
            if (delayDurationSeconds < 15) {
                delayDurationSeconds++
                tvDelayDuration.text = "${delayDurationSeconds} dtk"
                Toast.makeText(this, "Jeda gerak bebas: ${delayDurationSeconds} detik", Toast.LENGTH_SHORT).show()
            }
        }

        // Actions
        val btnApply = view.findViewById<Button>(R.id.btnApplyBestMove)
        val btnUndo = view.findViewById<Button>(R.id.btnUndoMove)
        val btnFlip = view.findViewById<Button>(R.id.btnFlipBoard)
        val btnReset = view.findViewById<Button>(R.id.btnResetBoard)
        val btnToggleSetup = view.findViewById<Button>(R.id.btnToggleSetup)
        val btnToggleCalib = view.findViewById<Button>(R.id.btnToggleCalibrate)

        // Mini Board
        val setupBoard = view.findViewById<SetupBoardView>(R.id.setupBoardView)
        setupBoardView = setupBoard
        setupBoard.boardState = boardState
        setupBoard.isWhiteBottom = isWhiteBottom
        setupBoard.isPlayMode = true
        setupBoard.onMoveMade = { from, to ->
            executeMove(from, to)
        }

        // Sub-panels
        val setupPanel = view.findViewById<LinearLayout>(R.id.setupPanel)
        val calibrationPanel = view.findViewById<LinearLayout>(R.id.calibrationPanel)

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

        // Toggle START / PAUSE
        btnToggleEngine.setOnClickListener {
            isEngineRunning = !isEngineRunning
            if (isEngineRunning) {
                // START / RESUME: Masuk ke mode jeda buffer terlebih dahulu
                btnToggleEngine.text = "⏸️ PAUSE"
                btnToggleEngine.setBackgroundColor(getColor(R.color.threat_arrow))
                calculateStockfishMoves()
                startFreeMoveDelay(delayDurationSeconds, isResumeBuffer = true)
                Toast.makeText(this, "Game Dimulai! Jeda ${delayDurationSeconds}s sebelum mode sentuh overlay.", Toast.LENGTH_SHORT).show()
            } else {
                // PAUSE: Batalkan timer dan bebaskan sentuhan layar penuh
                btnToggleEngine.text = "▶️ START"
                btnToggleEngine.setBackgroundColor(getColor(R.color.accent))
                pauseGameAndFreeScreen()
                Toast.makeText(this, "Engine Dijeda (Sentuhan Bebas)", Toast.LENGTH_SHORT).show()
            }
        }

        // Terapkan Rekomendasi #1
        btnApply.setOnClickListener {
            val best = currentCandidates.firstOrNull()
            if (best != null) {
                executeMove(best.from, best.to)
            } else {
                Toast.makeText(this, "Menunggu rekomendasi Stockfish...", Toast.LENGTH_SHORT).show()
            }
        }

        // Undo Move
        btnUndo.setOnClickListener {
            val undone = boardState.undoMove()
            if (undone) {
                sourceSquare = null
                arrowOverlayView?.selectedSquare = null
                setupBoardView?.selectedSquare = null
                setupBoardView?.candidates = emptyList()
                setupBoardView?.invalidate()
                arrowOverlayView?.invalidate()
                enterMappingMode()
                calculateStockfishMoves()
                Toast.makeText(this, "Langkah di-undo ↩️", Toast.LENGTH_SHORT).show()
            }
        }

        // Balik Papan (Putih / Hitam di bawah)
        btnFlip.setOnClickListener {
            isWhiteBottom = !isWhiteBottom
            btnFlip.text = if (isWhiteBottom) "🔄 Putih" else "🔄 Hitam"
            setupBoard.isWhiteBottom = isWhiteBottom
            setupBoard.invalidate()
            updateBoardBounds()
            calculateStockfishMoves()
            Toast.makeText(this, if (isWhiteBottom) "Perspektif: Putih di bawah" else "Perspektif: Hitam di bawah", Toast.LENGTH_SHORT).show()
        }

        // Reset Board ke Posisi Standar 32 Bidak
        btnReset.setOnClickListener {
            countdownJob?.cancel()
            boardState.resetToStartingPosition()
            setupBoard.selectedSquare = null
            setupBoard.candidates = emptyList()
            setupBoard.invalidate()
            arrowOverlayView?.clearOverlay()
            if (isEngineRunning) {
                calculateStockfishMoves()
                enterMappingMode()
            }
            Toast.makeText(this, "Papan catur direset ke posisi awal (32 bidak)", Toast.LENGTH_SHORT).show()
        }

        // Toggle Setup Palette
        var isSetupPaletteOpen = false
        btnToggleSetup.setOnClickListener {
            isSetupPaletteOpen = !isSetupPaletteOpen
            setupPanel.visibility = if (isSetupPaletteOpen) View.VISIBLE else View.GONE
            btnToggleSetup.text = if (isSetupPaletteOpen) "▲ Selesai Edit" else "🛠️ Edit Bidak"
            setupBoard.isPlayMode = !isSetupPaletteOpen
            setupBoard.selectedSquare = null
            setupBoard.invalidate()
        }

        // Palette Setup Controls
        val btnSetupClear = view.findViewById<Button>(R.id.btnSetupClear)
        val btnSetupDefault32 = view.findViewById<Button>(R.id.btnSetupDefault32)
        val btnSetupTurn = view.findViewById<Button>(R.id.btnSetupTurn)
        val btnPieceColorToggle = view.findViewById<Button>(R.id.btnPieceColorToggle)
        val btnFinishSetup = view.findViewById<Button>(R.id.btnFinishSetup)

        val btnToolMove = view.findViewById<Button>(R.id.btnToolMove)
        val btnToolPawn = view.findViewById<Button>(R.id.btnToolPawn)
        val btnToolKnight = view.findViewById<Button>(R.id.btnToolKnight)
        val btnToolBishop = view.findViewById<Button>(R.id.btnToolBishop)
        val btnToolRook = view.findViewById<Button>(R.id.btnToolRook)
        val btnToolQueen = view.findViewById<Button>(R.id.btnToolQueen)
        val btnToolKing = view.findViewById<Button>(R.id.btnToolKing)
        val btnToolDelete = view.findViewById<Button>(R.id.btnToolDelete)

        btnSetupClear.setOnClickListener {
            boardState.clearBoard()
            setupBoard.invalidate()
            arrowOverlayView?.clearOverlay()
        }

        btnSetupDefault32.setOnClickListener {
            boardState.resetToStartingPosition()
            setupBoard.invalidate()
        }

        btnSetupTurn.text = if (boardState.isWhiteToMove) "Giliran: Putih" else "Giliran: Hitam"
        btnSetupTurn.setOnClickListener {
            boardState.isWhiteToMove = !boardState.isWhiteToMove
            btnSetupTurn.text = if (boardState.isWhiteToMove) "Giliran: Putih" else "Giliran: Hitam"
            calculateStockfishMoves()
        }

        btnPieceColorToggle.setOnClickListener {
            setupBoard.activePieceIsWhite = !setupBoard.activePieceIsWhite
            btnPieceColorToggle.text = if (setupBoard.activePieceIsWhite) "Warna Bidak: ⚪ Putih" else "Warna Bidak: ⚫ Hitam"
        }

        val toolButtons = listOf(btnToolMove, btnToolPawn, btnToolKnight, btnToolBishop, btnToolRook, btnToolQueen, btnToolKing, btnToolDelete)
        fun selectToolButton(activeBtn: Button) {
            toolButtons.forEach {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.setTextColor(Color.parseColor("#94A3B8"))
            }
            activeBtn.setBackgroundColor(Color.parseColor("#0284C7"))
            activeBtn.setTextColor(Color.WHITE)
        }

        btnToolMove.setOnClickListener {
            setupBoard.currentTool = SetupTool.MOVE
            selectToolButton(btnToolMove)
        }
        btnToolPawn.setOnClickListener {
            setupBoard.currentTool = SetupTool.PLACE
            setupBoard.activePieceType = PieceType.PAWN
            selectToolButton(btnToolPawn)
        }
        btnToolKnight.setOnClickListener {
            setupBoard.currentTool = SetupTool.PLACE
            setupBoard.activePieceType = PieceType.KNIGHT
            selectToolButton(btnToolKnight)
        }
        btnToolBishop.setOnClickListener {
            setupBoard.currentTool = SetupTool.PLACE
            setupBoard.activePieceType = PieceType.BISHOP
            selectToolButton(btnToolBishop)
        }
        btnToolRook.setOnClickListener {
            setupBoard.currentTool = SetupTool.PLACE
            setupBoard.activePieceType = PieceType.ROOK
            selectToolButton(btnToolRook)
        }
        btnToolQueen.setOnClickListener {
            setupBoard.currentTool = SetupTool.PLACE
            setupBoard.activePieceType = PieceType.QUEEN
            selectToolButton(btnToolQueen)
        }
        btnToolKing.setOnClickListener {
            setupBoard.currentTool = SetupTool.PLACE
            setupBoard.activePieceType = PieceType.KING
            selectToolButton(btnToolKing)
        }
        btnToolDelete.setOnClickListener {
            setupBoard.currentTool = SetupTool.DELETE
            selectToolButton(btnToolDelete)
        }

        btnFinishSetup.setOnClickListener {
            val (wk, bk) = boardState.countKings()
            if (wk == 0 || bk == 0) {
                Toast.makeText(this, "Harus ada minimal 1 Raja Putih dan 1 Raja Hitam!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            isSetupPaletteOpen = false
            setupPanel.visibility = View.GONE
            btnToggleSetup.text = "🛠️ Edit Bidak"
            setupBoard.isPlayMode = true
            setupBoard.selectedSquare = null
            setupBoard.invalidate()

            isEngineRunning = true
            btnToggleEngine.text = "⏸️ PAUSE"
            btnToggleEngine.setBackgroundColor(getColor(R.color.threat_arrow))
            calculateStockfishMoves()
            enterMappingMode()
            Toast.makeText(this, "Posisi disimpan! Mode mapping aktif.", Toast.LENGTH_SHORT).show()
        }

        // Toggle Calibration Sub-panel
        btnToggleCalib.setOnClickListener {
            isCalibrationVisible = !isCalibrationVisible
            calibrationPanel.visibility = if (isCalibrationVisible) View.VISIBLE else View.GONE
            btnToggleCalib.setTextColor(if (isCalibrationVisible) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
        }

        btnUp.setOnClickListener {
            boardTopY -= 15f
            updateBoardBounds()
            calculateStockfishMoves()
        }
        btnDown.setOnClickListener {
            boardTopY += 15f
            updateBoardBounds()
            calculateStockfishMoves()
        }
        btnPlus.setOnClickListener {
            boardWidth += 15f
            updateBoardBounds()
            calculateStockfishMoves()
        }
        btnMinus.setOnClickListener {
            boardWidth -= 15f
            updateBoardBounds()
            calculateStockfishMoves()
        }
    }

    /**
     * Memulai jeda waktu gerak bebas (Overlay tembus sentuh 100%).
     * Dijalankan HANYA setelah langkah legal selesai dilakukan di mode overlay,
     * atau saat tombol resume ditekan.
     */
    @SuppressLint("SetTextI18n")
    private fun startFreeMoveDelay(seconds: Int, isResumeBuffer: Boolean = false) {
        countdownJob?.cancel()
        setOverlayTouchable(false) // Overlay hilang/tembus sentuh agar bebas gerak di aplikasi catur
        isMappingModeActive = false

        countdownJob = serviceScope.launch {
            val view = panelView ?: return@launch
            val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)

            for (sec in seconds downTo 1) {
                if (!isEngineRunning) return@launch
                val prefix = if (isResumeBuffer) "Persiapan" else "Gerak Bidak!"
                tvEngineTitle.text = "🎮 $prefix (${sec}s)"
                delay(1000)
            }

            if (isEngineRunning) {
                enterMappingMode()
            }
        }
    }

    /**
     * Mengaktifkan Mode Mapping (Overlay aktif menangkap tap langkah From -> To).
     */
    @SuppressLint("SetTextI18n")
    private fun enterMappingMode() {
        if (!isEngineRunning) return
        countdownJob?.cancel()
        countdownJob = null

        setOverlayTouchable(true) // Overlay menangkap sentuhan di layar
        isMappingModeActive = true

        val view = panelView ?: return
        val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)
        val sideText = if (boardState.isWhiteToMove) "Putih" else "Hitam"
        tvEngineTitle.text = "🖐️ Giliran $sideText: Tap Petak"
    }

    /**
     * Menjeda game dan membebaskan layar dari semua sentuhan overlay.
     */
    @SuppressLint("SetTextI18n")
    private fun pauseGameAndFreeScreen() {
        countdownJob?.cancel()
        countdownJob = null
        isMappingModeActive = false
        setOverlayTouchable(false) // Tembus sentuhan 100%

        val view = panelView ?: return
        val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)
        tvEngineTitle.text = "Engine Dijeda (Sentuhan Bebas)"
    }

    /**
     * Mengatur apakah Fullscreen Arrow Overlay menangkap sentuhan atau tembus
     */
    private fun setOverlayTouchable(touchable: Boolean) {
        val overlay = arrowOverlayView ?: return
        val params = arrowLayoutParams ?: return

        overlay.isInputMoveMode = touchable
        if (touchable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            overlay.selectedSquare = null
            sourceSquare = null
        }
        windowManager?.updateViewLayout(overlay, params)
        overlay.invalidate()
    }

    /**
     * Mengeksekusi langkah catur legal.
     * Jika sukses: memperbarui minimap, panah, kalkulasi Stockfish,
     * lalu otomatis mengaktifkan jeda timer gerak bebas!
     */
    private fun executeMove(from: Square, to: Square): Boolean {
        val moved = boardState.makeMove(from, to)
        if (moved) {
            sourceSquare = null
            arrowOverlayView?.selectedSquare = null
            setupBoardView?.selectedSquare = null
            setupBoardView?.candidates = emptyList()
            setupBoardView?.invalidate()
            arrowOverlayView?.invalidate()

            // Hitung rekomendasi langkah berikutnya
            calculateStockfishMoves()
            vibrateDevice(40)

            // Jeda hanya terjadi jika pergerakan legal berhasil dilakukan
            if (isEngineRunning) {
                startFreeMoveDelay(delayDurationSeconds, isResumeBuffer = false)
            }
            return true
        } else {
            Toast.makeText(this, "Langkah tidak sah!", Toast.LENGTH_SHORT).show()
            sourceSquare = null
            arrowOverlayView?.selectedSquare = null
            setupBoardView?.selectedSquare = null
            setupBoardView?.invalidate()
            arrowOverlayView?.invalidate()
            return false
        }
    }

    private fun handleSquareTapped(square: Square) {
        if (sourceSquare == null) {
            val piece = boardState.getPiece(square)
            if (piece != null) {
                sourceSquare = square
                arrowOverlayView?.selectedSquare = square
                arrowOverlayView?.invalidate()
            }
        } else {
            val from = sourceSquare!!
            if (from == square) {
                sourceSquare = null
                arrowOverlayView?.selectedSquare = null
                arrowOverlayView?.invalidate()
                return
            }
            val moved = executeMove(from, square)
            if (!moved) {
                sourceSquare = null
                arrowOverlayView?.selectedSquare = null
                arrowOverlayView?.invalidate()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun calculateStockfishMoves() {
        serviceScope.launch {
            val view = panelView ?: return@launch
            val tvLine1 = view.findViewById<TextView>(R.id.tvLine1)
            val tvLine2 = view.findViewById<TextView>(R.id.tvLine2)
            val tvLine3 = view.findViewById<TextView>(R.id.tvLine3)
            val tvLine4 = view.findViewById<TextView>(R.id.tvLine4)
            val tvLine5 = view.findViewById<TextView>(R.id.tvLine5)
            val textViews = listOf(tvLine1, tvLine2, tvLine3, tvLine4, tvLine5)

            val fen = boardState.toFen()
            tvLine1?.text = "#1: Mengkalkulasi..."

            val candidates = stockfishEngine.analyzeFen(fen, moveTimeMs = 600)
            currentCandidates = candidates

            if (candidates.isNotEmpty()) {
                for (i in 0 until 5) {
                    if (i < candidates.size) {
                        val cand = candidates[i]
                        val scoreText = if (cand.isMate) "M${cand.mateMoves}" else {
                            val sign = if (cand.scoreCp >= 0) "+" else ""
                            String.format("%s%.1f", sign, cand.scoreCp / 100.0)
                        }
                        val movesString = cand.pvLine.take(4).joinToString(" ")
                        textViews[i]?.text = "#${i + 1} [$scoreText] $movesString"
                        textViews[i]?.visibility = View.VISIBLE
                    } else {
                        textViews[i]?.visibility = View.GONE
                    }
                }
            } else {
                tvLine1?.text = "Tidak ada langkah valid"
                for (i in 1 until 5) {
                    textViews[i]?.visibility = View.GONE
                }
            }

            // Update Mini Board dengan hingga 5 panah rekomendasi
            setupBoardView?.candidates = candidates
            setupBoardView?.invalidate()

            // Update Panah Fullscreen Multi-PV (hingga 5 panah arah berbeda dengan warna unik)
            currentBoardBounds?.let { bounds ->
                arrowOverlayView?.updateAnalysis(bounds, candidates, emptyList())
            }
        }
    }

    private fun vibrateDevice(ms: Long = 40) {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Vision Overlay Aktif")
            .setContentText("Mode jeda & auto-mapping catur siap")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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
        countdownJob?.cancel()
        serviceScope.cancel()
        stockfishEngine.stop()

        panelView?.let { windowManager?.removeView(it) }
        arrowOverlayView?.let { windowManager?.removeView(it) }
    }
}
