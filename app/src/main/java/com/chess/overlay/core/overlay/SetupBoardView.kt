package com.chess.overlay.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.Piece
import com.chess.overlay.core.model.PieceType
import com.chess.overlay.core.model.Square

enum class SetupTool {
    MOVE,   // Tap bidak asal -> tap petak tujuan untuk geser bebas
    PLACE,  // Tap petak untuk langsung menaruh bidak terpilih
    DELETE  // Tap petak untuk menghapus bidak
}

class SetupBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var boardState: BoardState? = null
    var isWhiteBottom: Boolean = false // Default Hitam di bawah sesuai preferensi user

    var currentTool: SetupTool = SetupTool.MOVE
    var activePieceType: PieceType = PieceType.PAWN
    var activePieceIsWhite: Boolean = true

    var selectedSquare: Square? = null
    var onBoardChanged: (() -> Unit)? = null

    private val lightSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1") // Slate 300
    }
    private val darkSquarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569") // Slate 600
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 250, 204, 21) // Amber #FACC15
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#38BDF8")
        strokeWidth = 3f
    }
    private val whitePiecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 1f, 1f, Color.argb(200, 15, 23, 42))
    }
    private val blackPiecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A") // Deep Black
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.argb(180, 255, 255, 255))
    }

    private val unicodeMapWhite = mapOf(
        PieceType.PAWN to "♙",
        PieceType.KNIGHT to "♘",
        PieceType.BISHOP to "♗",
        PieceType.ROOK to "♖",
        PieceType.QUEEN to "♕",
        PieceType.KING to "♔"
    )

    private val unicodeMapBlack = mapOf(
        PieceType.PAWN to "♟",
        PieceType.KNIGHT to "♞",
        PieceType.BISHOP to "♝",
        PieceType.ROOK to "♜",
        PieceType.QUEEN to "♛",
        PieceType.KING to "♚"
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = boardState ?: return
        val size = Math.min(width, height).toFloat()
        if (size <= 0f) return
        val sq = size / 8f

        whitePiecePaint.textSize = sq * 0.76f
        blackPiecePaint.textSize = sq * 0.76f

        // 1. Gambar petak 8x8
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val isLight = (row + col) % 2 == 0
                val paint = if (isLight) lightSquarePaint else darkSquarePaint
                canvas.drawRect(col * sq, row * sq, (col + 1) * sq, (row + 1) * sq, paint)
            }
        }

        // 2. Gambar Highlight petak yang sedang dipilih untuk dipindah
        selectedSquare?.let { sel ->
            val displayCol = if (isWhiteBottom) sel.file else (7 - sel.file)
            val displayRow = if (isWhiteBottom) (7 - sel.rank) else sel.rank
            canvas.drawRect(displayCol * sq, displayRow * sq, (displayCol + 1) * sq, (displayRow + 1) * sq, selectedPaint)
        }

        // 3. Gambar Bidak
        val textOffset = (sq * 0.28f)
        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val file = if (isWhiteBottom) col else (7 - col)
                val rank = if (isWhiteBottom) (7 - row) else row
                val sqObj = Square(file, rank)
                val piece = state.getPiece(sqObj) ?: continue

                val glyph = if (piece.isWhite) {
                    unicodeMapWhite[piece.type] ?: "?"
                } else {
                    unicodeMapBlack[piece.type] ?: "?"
                }
                val pPaint = if (piece.isWhite) whitePiecePaint else blackPiecePaint

                val cx = (col + 0.5f) * sq
                val cy = (row + 0.5f) * sq + textOffset
                canvas.drawText(glyph, cx, cy, pPaint)
            }
        }

        // 4. Border Luar
        canvas.drawRect(0f, 0f, size, size, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val state = boardState ?: return true
        val size = Math.min(width, height).toFloat()
        if (size <= 0f) return true
        val sq = size / 8f

        val col = (event.x / sq).toInt().coerceIn(0, 7)
        val row = (event.y / sq).toInt().coerceIn(0, 7)
        val file = if (isWhiteBottom) col else (7 - col)
        val rank = if (isWhiteBottom) (7 - row) else row
        val tappedSquare = Square(file, rank)

        when (currentTool) {
            SetupTool.DELETE -> {
                state.setPiece(tappedSquare, null)
                selectedSquare = null
                invalidate()
                onBoardChanged?.invoke()
            }
            SetupTool.PLACE -> {
                state.setPiece(tappedSquare, Piece(activePieceType, activePieceIsWhite))
                selectedSquare = null
                invalidate()
                onBoardChanged?.invoke()
            }
            SetupTool.MOVE -> {
                if (selectedSquare == null) {
                    if (state.getPiece(tappedSquare) != null) {
                        selectedSquare = tappedSquare
                        invalidate()
                    }
                } else {
                    val from = selectedSquare!!
                    if (from == tappedSquare) {
                        selectedSquare = null
                    } else {
                        state.movePieceFree(from, tappedSquare)
                        selectedSquare = null
                        onBoardChanged?.invoke()
                    }
                    invalidate()
                }
            }
        }
        return true
    }
}
