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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import kotlin.math.hypot

class ChessOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var windowManager: WindowManager? = null

    // Overlay Views
    private var arrowOverlayView: ArrowOverlayView? = null
    private var panelView: View? = null
    private var holdButtonView: View? = null

    private var arrowLayoutParams: WindowManager.LayoutParams? = null
    private var holdLayoutParams: WindowManager.LayoutParams? = null
    private var setupBoardView: SetupBoardView? = null

    // Engine & Virtual Board State
    private val boardState = BoardState()
    private lateinit var stockfishEngine: StockfishBridge
    private var currentCandidates: List<MoveCandidate> = emptyList()

    // Mode: 1-Tap Auto-Lepas vs Jeda Timer
    private var isHoldMode = true // true = 1-Tap Auto-Lepas, false = Jeda Timer
    private var isMappingActive = false
    private var isEngineRunning = false
    private var isPanelMinimized = false
    private var isCalibrationVisible = false
    private var isWhiteBottom = true
    private var isPerspectiveManuallySet = false

    // Jeda Waktu Gerak Bebas (Mode Jeda Timer)
    private var delayDurationSeconds = 3
    private var countdownJob: Job? = null

    // Kalibrasi posisi & ukuran papan catur di layar
    private var boardTopY = 480f
    private var boardWidth = 1080f
    private var currentBoardBounds: BoardBounds? = null

    // Posisi tombol bulat mengambang (1-Tap Button)
    private var holdButtonX = 30
    private var holdButtonY = 850

    // Temporary selection untuk tap From -> To di layar
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
        val defaultWidth = metrics.widthPixels.toFloat()
        val defaultTopY = (metrics.heightPixels - defaultWidth) / 2.3f

        val prefs = getSharedPreferences("ChessOverlayPrefs", Context.MODE_PRIVATE)
        boardWidth = prefs.getFloat("saved_board_width", defaultWidth)
        boardTopY = prefs.getFloat("saved_board_top_y", defaultTopY)
        isWhiteBottom = prefs.getBoolean("saved_is_white_bottom", true)
        delayDurationSeconds = prefs.getInt("saved_delay_duration", 3)
        holdButtonX = prefs.getInt("saved_hold_btn_x", 30)
        holdButtonY = prefs.getInt("saved_hold_btn_y", (metrics.heightPixels * 0.55f).toInt())

        updateBoardBounds()
    }

    private fun saveCalibrationPrefs() {
        val prefs = getSharedPreferences("ChessOverlayPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("saved_board_width", boardWidth)
            .putFloat("saved_board_top_y", boardTopY)
            .putBoolean("saved_is_white_bottom", isWhiteBottom)
            .putInt("saved_delay_duration", delayDurationSeconds)
            .putInt("saved_hold_btn_x", holdButtonX)
            .putInt("saved_hold_btn_y", holdButtonY)
            .apply()
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
            // Default: FLAG_NOT_TOUCHABLE (sentuhan 100% tembus ke aplikasi catur di bawahnya)
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

            // 3. Floating Draggable Circular 1-Tap Button
            val holdInflater = LayoutInflater.from(themedContext)
            holdButtonView = holdInflater.inflate(R.layout.floating_hold_button, null)

            val initialHoldParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = holdButtonX
                y = holdButtonY
            }
            holdLayoutParams = initialHoldParams

            setupHoldButtonTouchAndDrag(initialHoldParams)
            wm.addView(holdButtonView, initialHoldParams)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Gagal memasang overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Konfigurasi Tombol Bulat 1-Tap Toggle:
     * - TAP SEKALI: Masuk Mode Mapping (menangkap tap 2x di papan).
     * - SETELAH 2x TAP: Otomatis LEPAS seketika (100% tembus sentuh).
     * - DRAG: Dapat digeser kemana saja di layar agar pas di jangkauan jempol.
     */
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun setupHoldButtonTouchAndDrag(params: WindowManager.LayoutParams) {
        val button = holdButtonView ?: return

        button.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > 15) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = (initialX + dx).toInt()
                            params.y = (initialY + dy).toInt()
                            windowManager?.updateViewLayout(button, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            holdButtonX = params.x
                            holdButtonY = params.y
                            saveCalibrationPrefs()
                        } else {
                            // TAP SEKALI: TOGGLE MAPPING
                            toggleMappingMode()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun toggleMappingMode() {
        if (!isEngineRunning) {
            Toast.makeText(this, "Tekan START terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return
        }
        if (isMappingActive) {
            disableMappingMode()
            Toast.makeText(this, "Mode Tembus Sentuh (Bebas)", Toast.LENGTH_SHORT).show()
        } else {
            enableMappingMode()
            Toast.makeText(this, "Mode Mapping Aktif: Tap 2x di papan!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableMappingMode() {
        isMappingActive = true
        vibrateDevice(25)
        val button = holdButtonView
        val container = button?.findViewById<FrameLayout>(R.id.holdButtonContainer)
        val tvIcon = button?.findViewById<TextView>(R.id.tvHoldIcon)
        val tvLabel = button?.findViewById<TextView>(R.id.tvHoldLabel)

        container?.setBackgroundResource(R.drawable.bg_hold_button_active)
        tvIcon?.text = "🖐️"
        tvLabel?.text = "AKTIF"
        tvLabel?.setTextColor(Color.WHITE)

        setArrowOverlayTouchable(true)
        arrowOverlayView?.isInputMoveMode = true
        panelView?.findViewById<TextView>(R.id.tvEngineTitle)?.text = "🖐️ MAPPING: Tap 2x di Papan!"
    }

    private fun disableMappingMode() {
        isMappingActive = false
        val button = holdButtonView
        val container = button?.findViewById<FrameLayout>(R.id.holdButtonContainer)
        val tvIcon = button?.findViewById<TextView>(R.id.tvHoldIcon)
        val tvLabel = button?.findViewById<TextView>(R.id.tvHoldLabel)

        container?.setBackgroundResource(R.drawable.bg_hold_button_idle)
        tvIcon?.text = "🎯"
        tvLabel?.text = "TAP MAP"
        tvLabel?.setTextColor(Color.parseColor("#38BDF8"))

        setArrowOverlayTouchable(false)
        arrowOverlayView?.isInputMoveMode = false
        sourceSquare = null
        arrowOverlayView?.selectedSquare = null
        arrowOverlayView?.invalidate()

        val sideText = if (boardState.isWhiteToMove) "Putih" else "Hitam"
        panelView?.findViewById<TextView>(R.id.tvEngineTitle)?.text = "🎯 Giliran $sideText (Tembus)"
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun setupPanelTouchAndControls(params: WindowManager.LayoutParams) {
        val view = panelView ?: return
        val header = view.findViewById<View>(R.id.panelHeader)
        val content = view.findViewById<View>(R.id.panelContent)
        val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)
        val btnToggleEngine = view.findViewById<Button>(R.id.btnToggleEngine)
        val btnMinimize = view.findViewById<TextView>(R.id.btnToggleMinimize)
        val btnToggleMode = view.findViewById<Button>(R.id.btnToggleMode)
        val delayContainer = view.findViewById<LinearLayout>(R.id.delayContainer)

        // Pengatur Jeda Detik (Mode Jeda Timer)
        val btnDelayMinus = view.findViewById<Button>(R.id.btnDelayMinus)
        val btnDelayPlus = view.findViewById<Button>(R.id.btnDelayPlus)
        val tvDelayDuration = view.findViewById<TextView>(R.id.tvDelayDuration)
        tvDelayDuration.text = "${delayDurationSeconds} dtk"

        btnDelayMinus.setOnClickListener {
            if (delayDurationSeconds > 1) {
                delayDurationSeconds--
                tvDelayDuration.text = "${delayDurationSeconds} dtk"
                saveCalibrationPrefs()
                Toast.makeText(this, "Jeda gerak bebas: ${delayDurationSeconds} detik", Toast.LENGTH_SHORT).show()
            }
        }

        btnDelayPlus.setOnClickListener {
            if (delayDurationSeconds < 15) {
                delayDurationSeconds++
                tvDelayDuration.text = "${delayDurationSeconds} dtk"
                saveCalibrationPrefs()
                Toast.makeText(this, "Jeda gerak bebas: ${delayDurationSeconds} detik", Toast.LENGTH_SHORT).show()
            }
        }

        fun updateModeUI() {
            if (isHoldMode) {
                btnToggleMode.text = "🎯 MODE: 1-TAP (AUTO-LEPAS)"
                btnToggleMode.setBackgroundColor(Color.parseColor("#059669"))
                holdButtonView?.visibility = if (isEngineRunning) View.VISIBLE else View.GONE
                delayContainer.visibility = View.GONE
                countdownJob?.cancel()
                disableMappingMode()
            } else {
                btnToggleMode.text = "⏳ MODE: JEDA TIMER (COUNTDOWN)"
                btnToggleMode.setBackgroundColor(Color.parseColor("#2563EB"))
                holdButtonView?.visibility = View.GONE
                delayContainer.visibility = View.VISIBLE
                if (isEngineRunning) {
                    startFreeMoveDelay(delayDurationSeconds, isResumeBuffer = true)
                }
            }
        }
        updateModeUI()

        btnToggleMode.setOnClickListener {
            isHoldMode = !isHoldMode
            updateModeUI()
            if (isHoldMode) {
                Toast.makeText(this, "Mode 1-Tap: Tekan tombol 🎯 sekali, tap 2x di papan -> otomatis lepas!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Mode Jeda Timer: Jeda ${delayDurationSeconds}s sebelum mode sentuh overlay.", Toast.LENGTH_LONG).show()
            }
        }

        // Actions
        val btnApply = view.findViewById<Button>(R.id.btnApplyBestMove)
        val btnUndo = view.findViewById<Button>(R.id.btnUndoMove)
        val btnRedo = view.findViewById<Button>(R.id.btnRedoMove)
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
            val p = boardState.getPiece(from)
            if (p != null && p.isWhite != boardState.isWhiteToMove) {
                boardState.isWhiteToMove = p.isWhite
            }
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
                btnToggleEngine.text = "⏸️ PAUSE"
                btnToggleEngine.setBackgroundColor(getColor(R.color.threat_arrow))
                calculateStockfishMoves()
                if (isHoldMode) {
                    holdButtonView?.visibility = View.VISIBLE
                    disableMappingMode()
                    Toast.makeText(this, "Game Dimulai! Tekan tombol 🎯 untuk mapping.", Toast.LENGTH_SHORT).show()
                } else {
                    holdButtonView?.visibility = View.GONE
                    startFreeMoveDelay(delayDurationSeconds, isResumeBuffer = true)
                    Toast.makeText(this, "Game Dimulai! Jeda ${delayDurationSeconds}s sebelum mode sentuh.", Toast.LENGTH_SHORT).show()
                }
            } else {
                btnToggleEngine.text = "▶️ START"
                btnToggleEngine.setBackgroundColor(getColor(R.color.accent))
                countdownJob?.cancel()
                setArrowOverlayTouchable(false)
                holdButtonView?.visibility = View.GONE
                tvEngineTitle.text = "Engine Dijeda (Sentuhan Bebas)"
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
                updateMoveHistoryDisplay()
                calculateStockfishMoves()
                Toast.makeText(this, "Langkah di-undo ↩️", Toast.LENGTH_SHORT).show()
            }
        }

        // Redo Move
        btnRedo?.setOnClickListener {
            val redone = boardState.redoMove()
            if (redone) {
                sourceSquare = null
                arrowOverlayView?.selectedSquare = null
                setupBoardView?.selectedSquare = null
                setupBoardView?.candidates = emptyList()
                setupBoardView?.invalidate()
                arrowOverlayView?.invalidate()
                updateMoveHistoryDisplay()
                calculateStockfishMoves()
                Toast.makeText(this, "Langkah di-redo ↪️", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Tidak ada langkah untuk di-redo", Toast.LENGTH_SHORT).show()
            }
        }

        // Balik Papan (Putih / Hitam di bawah)
        btnFlip.setOnClickListener {
            isWhiteBottom = !isWhiteBottom
            isPerspectiveManuallySet = true
            btnFlip.text = if (isWhiteBottom) "🔄 Putih" else "🔄 Hitam"
            setupBoard.isWhiteBottom = isWhiteBottom
            setupBoard.invalidate()
            updateBoardBounds()
            saveCalibrationPrefs()
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
            updateMoveHistoryDisplay()
            if (isEngineRunning) {
                calculateStockfishMoves()
                if (!isHoldMode) {
                    startFreeMoveDelay(delayDurationSeconds, isResumeBuffer = true)
                }
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
            updateMoveHistoryDisplay()
        }

        btnSetupDefault32.setOnClickListener {
            boardState.resetToStartingPosition()
            setupBoard.invalidate()
            updateMoveHistoryDisplay()
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
            if (isHoldMode) {
                holdButtonView?.visibility = View.VISIBLE
                disableMappingMode()
            } else {
                holdButtonView?.visibility = View.GONE
                startFreeMoveDelay(delayDurationSeconds, isResumeBuffer = true)
            }
            Toast.makeText(this, "Posisi disimpan!", Toast.LENGTH_SHORT).show()
        }

        // Toggle Calibration Sub-panel (Penyelarasan Papan Catur Layar)
        btnToggleCalib.setOnClickListener {
            isCalibrationVisible = !isCalibrationVisible
            calibrationPanel.visibility = if (isCalibrationVisible) View.VISIBLE else View.GONE
            btnToggleCalib.setTextColor(if (isCalibrationVisible) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
            arrowOverlayView?.isCalibrationMode = isCalibrationVisible
            arrowOverlayView?.invalidate()
        }

        btnUp.setOnClickListener {
            boardTopY -= 15f
            updateBoardBounds()
            saveCalibrationPrefs()
            arrowOverlayView?.invalidate()
            calculateStockfishMoves()
        }
        btnDown.setOnClickListener {
            boardTopY += 15f
            updateBoardBounds()
            saveCalibrationPrefs()
            arrowOverlayView?.invalidate()
            calculateStockfishMoves()
        }
        btnPlus.setOnClickListener {
            boardWidth += 15f
            updateBoardBounds()
            saveCalibrationPrefs()
            arrowOverlayView?.invalidate()
            calculateStockfishMoves()
        }
        btnMinus.setOnClickListener {
            boardWidth -= 15f
            updateBoardBounds()
            saveCalibrationPrefs()
            arrowOverlayView?.invalidate()
            calculateStockfishMoves()
        }

        updateMoveHistoryDisplay()
    }

    /**
     * Memulai jeda waktu gerak bebas (Mode Jeda Timer).
     * Selama countdown berlangsung, overlay 100% tembus sentuh.
     */
    @SuppressLint("SetTextI18n")
    private fun startFreeMoveDelay(seconds: Int, isResumeBuffer: Boolean = false) {
        countdownJob?.cancel()
        setArrowOverlayTouchable(false)

        countdownJob = serviceScope.launch {
            val view = panelView ?: return@launch
            val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)

            for (sec in seconds downTo 1) {
                if (!isEngineRunning) return@launch
                val prefix = if (isResumeBuffer) "Persiapan" else "Gerak Bidak!"
                tvEngineTitle.text = "🎮 $prefix (${sec}s)"
                delay(1000)
            }

            if (isEngineRunning && !isHoldMode) {
                enterTimerMappingMode()
            }
        }
    }

    /**
     * Mengaktifkan Mode Mapping saat jeda waktu timer habis (Mode Jeda Timer).
     */
    @SuppressLint("SetTextI18n")
    private fun enterTimerMappingMode() {
        if (!isEngineRunning || isHoldMode) return
        countdownJob?.cancel()
        countdownJob = null

        setArrowOverlayTouchable(true)
        arrowOverlayView?.isInputMoveMode = true

        val view = panelView ?: return
        val tvEngineTitle = view.findViewById<TextView>(R.id.tvEngineTitle)
        val sideText = if (boardState.isWhiteToMove) "Putih" else "Hitam"
        tvEngineTitle.text = "🖐️ Giliran $sideText: Tap Petak"
    }

    /**
     * Mengatur apakah Fullscreen Arrow Overlay menangkap sentuhan (true) atau tembus 100% (false)
     */
    private fun setArrowOverlayTouchable(touchable: Boolean) {
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
     * Eksekusi sentuhan petak catur (2x tap: From -> To)
     */
    private fun handleSquareTapped(square: Square) {
        if (sourceSquare == null) {
            val piece = boardState.getPiece(square)
            if (piece != null) {
                sourceSquare = square
                arrowOverlayView?.selectedSquare = square
                setupBoardView?.selectedSquare = square
                arrowOverlayView?.invalidate()
                setupBoardView?.invalidate()
                vibrateDevice(15)
            }
        } else {
            val from = sourceSquare!!
            val to = square
            executeMove(from, to)
        }
    }

    /**
     * Mengeksekusi langkah catur legal.
     * Memperbarui minimap, panah, Stockfish, dan riwayat langkah.
     */
    private fun executeMove(from: Square, to: Square): Boolean {
        var moved = boardState.makeMove(from, to)
        if (!moved) {
            moved = boardState.forceMove(from, to)
        }

        if (moved) {
            sourceSquare = null
            arrowOverlayView?.selectedSquare = null
            setupBoardView?.selectedSquare = null
            setupBoardView?.candidates = emptyList()
            setupBoardView?.invalidate()
            arrowOverlayView?.invalidate()
            updateMoveHistoryDisplay()

            // Hitung rekomendasi langkah berikutnya
            calculateStockfishMoves()
            vibrateDevice(35)

            if (isEngineRunning) {
                if (isHoldMode) {
                    // MODE 1-TAP: Otomatis LEPAS seketika agar pemain bebas gerak di Chess.com!
                    disableMappingMode()
                } else {
                    // MODE JEDA TIMER: Mulai jeda waktu bebas gerak
                    startFreeMoveDelay(delayDurationSeconds, isResumeBuffer = false)
                }
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

    private fun calculateStockfishMoves() {
        val fen = boardState.toFen()
        val engine = if (::stockfishEngine.isInitialized) stockfishEngine else null
        if (engine == null) return

        serviceScope.launch(Dispatchers.Main) {
            val rawCandidates = withContext(Dispatchers.Default) {
                engine.analyzeFen(fen, moveTimeMs = 500)
            }

            val view = panelView ?: return@launch
            val tvLine1 = view.findViewById<TextView>(R.id.tvLine1)
            val tvLine2 = view.findViewById<TextView>(R.id.tvLine2)
            val tvLine3 = view.findViewById<TextView>(R.id.tvLine3)
            val tvLine4 = view.findViewById<TextView>(R.id.tvLine4)
            val tvLine5 = view.findViewById<TextView>(R.id.tvLine5)
            val tvLineHuman = view.findViewById<TextView>(R.id.tvLineHuman)
            val textViews = listOf(tvLine1, tvLine2, tvLine3, tvLine4, tvLine5)

            val candidates = if (rawCandidates.size >= 2) {
                val top5 = rawCandidates.take(5)
                val humanCandidate = rawCandidates.getOrNull(2)
                val humanMove = if (humanCandidate != null) {
                    listOf(humanCandidate.copy(rankOrder = 6, isHuman = true))
                } else emptyList()
                top5 + humanMove
            } else {
                rawCandidates
            }
            currentCandidates = candidates

            if (candidates.isNotEmpty()) {
                val engineLines = candidates.filter { !it.isHuman }
                for (i in 0 until 5) {
                    if (i < engineLines.size) {
                        val cand = engineLines[i]
                        val scoreText = if (cand.isMate) "M${cand.mateMoves}" else {
                            val sign = if (cand.scoreCp >= 0) "+" else ""
                            String.format("%s%.1f", sign, cand.scoreCp / 100.0)
                        }
                        val primarySan = boardState.moveToSan(cand.from, cand.to)
                        val continuation = cand.pvLine.drop(1).take(3).joinToString(" ")
                        val movesString = if (continuation.isNotEmpty()) "$primarySan ($continuation)" else primarySan
                        textViews[i]?.text = "#${i + 1} [$scoreText] $movesString"
                        textViews[i]?.visibility = View.VISIBLE
                    } else {
                        textViews[i]?.visibility = View.GONE
                    }
                }

                val humanCandidate = candidates.firstOrNull { it.isHuman }
                if (humanCandidate != null) {
                    val scoreText = if (humanCandidate.isMate) "M${humanCandidate.mateMoves}" else {
                        val sign = if (humanCandidate.scoreCp >= 0) "+" else ""
                        String.format("%s%.1f", sign, humanCandidate.scoreCp / 100.0)
                    }
                    val primarySan = boardState.moveToSan(humanCandidate.from, humanCandidate.to)
                    val continuation = humanCandidate.pvLine.drop(1).take(3).joinToString(" ")
                    val movesString = if (continuation.isNotEmpty()) "$primarySan ($continuation)" else primarySan
                    tvLineHuman?.text = "🎯 #H [Manusiawi/Tal] [$scoreText] $movesString"
                    tvLineHuman?.visibility = View.VISIBLE
                } else {
                    tvLineHuman?.visibility = View.GONE
                }
            } else {
                val inCheck = boardState.isKingInCheck(boardState.isWhiteToMove)
                val statusText = if (inCheck) "#1: [Skakmat] Posisi Selesai" else "#1: [Remis] Tidak Ada Langkah"
                tvLine1?.text = statusText
                for (i in 1 until 5) {
                    textViews[i]?.visibility = View.GONE
                }
                tvLineHuman?.visibility = View.GONE
            }

            setupBoardView?.candidates = candidates
            setupBoardView?.invalidate()

            currentBoardBounds?.let { bounds ->
                arrowOverlayView?.updateAnalysis(bounds, candidates, emptyList())
            }
        }
    }

    private fun updateMoveHistoryDisplay() {
        val view = panelView ?: return
        val tvMoveHistory = view.findViewById<TextView>(R.id.tvMoveHistory) ?: return
        val scrollMoveHistory = view.findViewById<HorizontalScrollView>(R.id.scrollMoveHistory)
        val historyStr = boardState.getFormattedMoveHistory()
        tvMoveHistory.text = if (historyStr.isBlank()) "📜 Riwayat: Belum ada langkah" else "📜 $historyStr"
        scrollMoveHistory?.post {
            scrollMoveHistory.fullScroll(View.FOCUS_RIGHT)
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
            .setContentText("Mode 1-Tap & Analisis Catur Siap")
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
            ).apply {
                description = "Notifikasi status overlay kalkulator catur"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        countdownJob?.cancel()
        try {
            if (holdButtonView != null) {
                windowManager?.removeView(holdButtonView)
                holdButtonView = null
            }
            if (panelView != null) {
                windowManager?.removeView(panelView)
                panelView = null
            }
            if (arrowOverlayView != null) {
                windowManager?.removeView(arrowOverlayView)
                arrowOverlayView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (::stockfishEngine.isInitialized) {
            stockfishEngine.stop()
        }
    }
}
