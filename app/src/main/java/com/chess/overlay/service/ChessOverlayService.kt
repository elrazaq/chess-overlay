package com.chess.overlay.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
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
import com.chess.overlay.core.model.Square
import com.chess.overlay.core.overlay.ArrowOverlayView
import com.chess.overlay.core.vision.BoardDetector
import com.chess.overlay.core.vision.PieceClassifier
import kotlinx.coroutines.*

class ChessOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null

    // Screen Capture & Vision
    private var screenCaptureHelper: ScreenCaptureHelper? = null
    private val boardDetector = BoardDetector()
    private val pieceClassifier = PieceClassifier()

    // Overlay Views
    private var arrowOverlayView: ArrowOverlayView? = null
    private var panelView: View? = null
    private var arrowLayoutParams: WindowManager.LayoutParams? = null
    private var setupBoardView: com.chess.overlay.core.overlay.SetupBoardView? = null

    // Live Auto-Tracking State
    private var liveTrackingJob: Job? = null
    private val lastSquareLuminances = FloatArray(64)
    private var hasBaseline = false

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

            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                try {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    if (projection != null) {
                        screenCaptureHelper = ScreenCaptureHelper(this, projection)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
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

        // Toggle Engine Start / Pause (Live Tracking Otomatis Tanpa Jeda Sentuhan!)
        btnToggleEngine.setOnClickListener {
            isEngineRunning = !isEngineRunning
            if (isEngineRunning) {
                btnToggleEngine.text = "🟢 AKTIF"
                btnToggleEngine.setBackgroundColor(getColor(R.color.threat_arrow))
                enableTouchInputMode(false) // Sentuhan tembus 100% ke game catur tanpa halangan!
                hasBaseline = false
                startLiveTracking()
                calculateStockfishMoves()
                Toast.makeText(this, "Auto-Tracking aktif! Gerakkan bidak catur Anda seperti biasa.", Toast.LENGTH_SHORT).show()
            } else {
                btnToggleEngine.text = "▶️ START"
                btnToggleEngine.setBackgroundColor(getColor(R.color.accent))
                enableTouchInputMode(false)
                stopLiveTracking()
                tvEngineTitle.text = "Engine Dijeda (Sentuhan Bebas)"
                arrowOverlayView?.clearOverlay()
            }
        }

        // Terapkan Rekomendasi #1 Otomatis
        btnApply.setOnClickListener {
            val best = currentCandidates.firstOrNull()
            if (best != null) {
                boardState.makeMove(best.from, best.to, skipValidation = true)
                Toast.makeText(this, "Langkah diterapkan: ${best.from.toUci()} -> ${best.to.toUci()}", Toast.LENGTH_SHORT).show()
                hasBaseline = false
                setupBoardView?.invalidate()
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
                hasBaseline = false
                setupBoardView?.invalidate()
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
            hasBaseline = false
            setupBoardView?.invalidate()
            Toast.makeText(this, "32 Bidak direset ke posisi awal! Silakan mulai melangkah.", Toast.LENGTH_SHORT).show()
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

        // Setup Posisi Papan (Mid/Endgame)
        val btnToggleSetup = view.findViewById<Button>(R.id.btnToggleSetup)
        val setupPanel = view.findViewById<LinearLayout>(R.id.setupPanel)
        val setupBoard = view.findViewById<com.chess.overlay.core.overlay.SetupBoardView>(R.id.setupBoardView)
        setupBoardView = setupBoard
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

        setupBoard.boardState = boardState
        setupBoard.isWhiteBottom = isWhiteBottom

        var isSetupVisible = false
        btnToggleSetup.setOnClickListener {
            isSetupVisible = !isSetupVisible
            setupPanel.visibility = if (isSetupVisible) View.VISIBLE else View.GONE
            btnToggleSetup.text = if (isSetupVisible) "▲ Tutup Setup Posisi" else "🛠️ Setup Posisi Papan (Mid/Endgame)"
            if (isSetupVisible) {
                setupBoard.isWhiteBottom = isWhiteBottom
                setupBoard.invalidate()
            }
        }

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
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.MOVE
            selectToolButton(btnToolMove)
        }
        btnToolPawn.setOnClickListener {
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.PLACE
            setupBoard.activePieceType = com.chess.overlay.core.model.PieceType.PAWN
            selectToolButton(btnToolPawn)
        }
        btnToolKnight.setOnClickListener {
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.PLACE
            setupBoard.activePieceType = com.chess.overlay.core.model.PieceType.KNIGHT
            selectToolButton(btnToolKnight)
        }
        btnToolBishop.setOnClickListener {
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.PLACE
            setupBoard.activePieceType = com.chess.overlay.core.model.PieceType.BISHOP
            selectToolButton(btnToolBishop)
        }
        btnToolRook.setOnClickListener {
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.PLACE
            setupBoard.activePieceType = com.chess.overlay.core.model.PieceType.ROOK
            selectToolButton(btnToolRook)
        }
        btnToolQueen.setOnClickListener {
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.PLACE
            setupBoard.activePieceType = com.chess.overlay.core.model.PieceType.QUEEN
            selectToolButton(btnToolQueen)
        }
        btnToolKing.setOnClickListener {
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.PLACE
            setupBoard.activePieceType = com.chess.overlay.core.model.PieceType.KING
            selectToolButton(btnToolKing)
        }
        btnToolDelete.setOnClickListener {
            setupBoard.currentTool = com.chess.overlay.core.overlay.SetupTool.DELETE
            selectToolButton(btnToolDelete)
        }

        btnFinishSetup.setOnClickListener {
            val (wk, bk) = boardState.countKings()
            if (wk == 0 || bk == 0) {
                Toast.makeText(this, "Posisi harus memiliki minimal 1 Raja Putih dan 1 Raja Hitam!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            isSetupVisible = false
            setupPanel.visibility = View.GONE
            btnToggleSetup.text = "🛠️ Setup Posisi Papan (Mid/Endgame)"

            // Jalankan Stockfish langsung dari posisi baru ini
            isEngineRunning = true
            btnToggleEngine.text = "⏸️ PAUSE"
            btnToggleEngine.setBackgroundColor(getColor(R.color.threat_arrow))
            enableTouchInputMode(true)
            calculateStockfishMoves()
            Toast.makeText(this, "Posisi berhasil disetup! Stockfish mulai menghitung.", Toast.LENGTH_SHORT).show()
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
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            btnInput?.text = "🖐️ Tap: ON"
            btnInput?.setBackgroundColor(getColor(R.color.threat_arrow))
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            overlay.selectedSquare = null
            sourceSquare = null
            btnInput?.text = "🖐️ Tap: OFF"
            btnInput?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        windowManager?.updateViewLayout(overlay, params)
        overlay.invalidate()
    }

    /**
     * Mengeksekusi pergerakan catur baik dari tap layar penuh atau drag jari
     */
    private fun executeMove(from: Square, to: Square): Boolean {
        val moved = boardState.makeMove(from, to)
        if (moved) {
            sourceSquare = null
            arrowOverlayView?.selectedSquare = null
            arrowOverlayView?.invalidate()

            if (isEngineRunning) {
                calculateStockfishMoves()
            } else {
                arrowOverlayView?.clearOverlay()
            }
            return true
        } else {
            Toast.makeText(this, "Langkah tidak sah!", Toast.LENGTH_SHORT).show()
            sourceSquare = null
            arrowOverlayView?.selectedSquare = null
            arrowOverlayView?.invalidate()
            return false
        }
    }

    /**
     * Logika sentuhan dua petak (From -> To) saat menggerakkan anak catur secara manual di layar besar
     */
    private fun handleSquareTapped(square: Square) {
        if (sourceSquare == null) {
            val piece = boardState.getPiece(square)
            if (piece != null) {
                sourceSquare = square
                arrowOverlayView?.selectedSquare = square
                arrowOverlayView?.invalidate()
            } else {
                Toast.makeText(this, "Petak ${square.toUci()} kosong", Toast.LENGTH_SHORT).show()
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

    private fun stopLiveTracking() {
        liveTrackingJob?.cancel()
        liveTrackingJob = null
        hasBaseline = false
    }

    private fun startLiveTracking() {
        stopLiveTracking()
        liveTrackingJob = serviceScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(380)
                if (!isEngineRunning) continue

                val helper = screenCaptureHelper ?: continue
                val bitmap = helper.captureSnapshot() ?: continue

                // Pastikan batas papan catur sudah terdeteksi
                var bounds = currentBoardBounds
                if (bounds == null) {
                    bounds = boardDetector.findBoard(bitmap, isWhiteBottom)
                    currentBoardBounds = bounds
                    withContext(Dispatchers.Main) {
                        arrowOverlayView?.boardBounds = bounds
                    }
                }

                val sq = bounds.squareSize
                val currentLums = FloatArray(64)

                // Hitung rata-rata luminansi piksel tengah pada masing-masing 64 petak
                for (rank in 0..7) {
                    for (file in 0..7) {
                        val col = if (isWhiteBottom) file else (7 - file)
                        val row = if (isWhiteBottom) (7 - rank) else rank

                        val startX = (bounds.left + col * sq).toInt().coerceIn(0, bitmap.width - 1)
                        val startY = (bounds.top + row * sq).toInt().coerceIn(0, bitmap.height - 1)
                        val s = sq.toInt().coerceAtMost(bitmap.width - startX).coerceAtMost(bitmap.height - startY)
                        if (s <= 10) continue

                        val m = (s * 0.22f).toInt()
                        var sum = 0L
                        var count = 0
                        for (y in m until (s - m) step 2) {
                            val py = startY + y
                            if (py >= bitmap.height) continue
                            for (x in m until (s - m) step 2) {
                                val px = startX + x
                                if (px >= bitmap.width) continue
                                val p = bitmap.getPixel(px, py)
                                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                                sum += lum
                                count++
                            }
                        }

                        val avg = if (count > 0) sum.toFloat() / count else 0f
                        currentLums[rank * 8 + file] = avg
                    }
                }

                if (!hasBaseline) {
                    System.arraycopy(currentLums, 0, lastSquareLuminances, 0, 64)
                    hasBaseline = true
                    continue
                }

                val changedSquares = mutableListOf<Square>()
                for (rank in 0..7) {
                    for (file in 0..7) {
                        val idx = rank * 8 + file
                        val diff = Math.abs(currentLums[idx] - lastSquareLuminances[idx])
                        if (diff > 20f) {
                            changedSquares.add(Square(file, rank))
                        }
                    }
                }

                if (changedSquares.size in 2..4) {
                    var validFrom: Square? = null
                    var validTo: Square? = null

                    for (from in changedSquares) {
                        val p = boardState.getPiece(from)
                        if (p != null && p.isWhite == boardState.isWhiteToMove) {
                            for (to in changedSquares) {
                                if (from != to && boardState.isValidMove(from, to)) {
                                    validFrom = from
                                    validTo = to
                                    break
                                }
                            }
                        }
                        if (validFrom != null) break
                    }

                    if (validFrom != null && validTo != null) {
                        System.arraycopy(currentLums, 0, lastSquareLuminances, 0, 64)
                        val fromSq = validFrom
                        val toSq = validTo

                        withContext(Dispatchers.Main) {
                            val moved = boardState.makeMove(fromSq, toSq)
                            if (moved) {
                                setupBoardView?.invalidate()
                                calculateStockfishMoves()
                            }
                        }
                        delay(350)
                    }
                }
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
        stopLiveTracking()
        serviceScope.cancel()
        stockfishEngine.stop()
        screenCaptureHelper?.release()
        screenCaptureHelper = null

        panelView?.let { windowManager?.removeView(it) }
        arrowOverlayView?.let { windowManager?.removeView(it) }
    }
}
