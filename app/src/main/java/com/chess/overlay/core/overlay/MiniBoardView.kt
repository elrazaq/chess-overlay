package com.chess.overlay.core.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.PieceType
import com.chess.overlay.core.model.Square

/**
 * Papan Catur Mini Interaktif (Mirror Board Pad) di dalam Floating Panel.
 * Memungkinkan pemain mencatat langkah lawan dan langkah sendiri dengan 1 sentuhan cepat
 * TANPA MENGHALANGI sentuhan pada aplikasi Chess.com utama di layar!
 */
class MiniBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var boardState: BoardState? = null
    var isWhiteBottom: Boolean = false // Default Hitam di bawah
    var selectedSquare: Square? = null
    var onMoveExecuted: ((from: Square, to: Square) -> Unit)? = null

    private val lightSquarePaint = Paint().apply { color = Color.parseColor("#B8C69F") }
    private val darkSquarePaint = Paint().apply { color = Color.parseColor("#769656") }
    private val highlightPaint = Paint().apply { color = Color.argb(180, 250, 204, 21) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Papan mini persegi kompak ~200dp
        val size = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
        val actualSize = if (size > 0) size else 480
        setMeasuredDimension(actualSize, actualSize)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val state = boardState ?: return false
            val sqSize = width / 8f
            val col = (event.x / sqSize).toInt().coerceIn(0, 7)
            val row = (event.y / sqSize).toInt().coerceIn(0, 7)

            val file = if (isWhiteBottom) col else (7 - col)
            val rank = if (isWhiteBottom) (7 - row) else row
            val tappedSquare = Square(file, rank)

            if (selectedSquare == null) {
                // Pilih bidak asal
                val piece = state.getPiece(tappedSquare)
                if (piece != null) {
                    selectedSquare = tappedSquare
                    invalidate()
                }
            } else {
                // Jalankan langkah ke petak tujuan
                val from = selectedSquare!!
                if (from != tappedSquare) {
                    val moved = state.makeMove(from, tappedSquare)
                    if (moved) {
                        onMoveExecuted?.invoke(from, tappedSquare)
                    }
                }
                selectedSquare = null
                invalidate()
            }
            return true
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = boardState ?: return
        val sqSize = width / 8f

        // 1. Gambar petak 8x8
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val isLight = (r + c) % 2 == 0
                val p = if (isLight) lightSquarePaint else darkSquarePaint
                canvas.drawRect(c * sqSize, r * sqSize, (c + 1) * sqSize, (r + 1) * sqSize, p)

                val file = if (isWhiteBottom) c else (7 - c)
                val rank = if (isWhiteBottom) (7 - r) else r
                val sq = Square(file, rank)

                // Highlight petak yang dipilih
                if (selectedSquare == sq) {
                    canvas.drawRect(c * sqSize, r * sqSize, (c + 1) * sqSize, (r + 1) * sqSize, highlightPaint)
                }

                // Gambar simbol bidak Unicode
                val piece = state.getPiece(sq)
                if (piece != null) {
                    textPaint.color = if (piece.isWhite) Color.WHITE else Color.BLACK
                    if (piece.isWhite) {
                        textPaint.setShadowLayer(3f, 0f, 0f, Color.DKGRAY)
                    } else {
                        textPaint.clearShadowLayer()
                    }
                    val symbol = getUnicodePiece(piece.type, piece.isWhite)
                    canvas.drawText(
                        symbol,
                        (c + 0.5f) * sqSize,
                        (r + 0.72f) * sqSize,
                        textPaint
                    )
                }
            }
        }
    }

    private fun getUnicodePiece(type: PieceType, isWhite: Boolean): String {
        return when (type) {
            PieceType.PAWN -> if (isWhite) "♙" else "♟"
            PieceType.KNIGHT -> if (isWhite) "♘" else "♞"
            PieceType.BISHOP -> if (isWhite) "♗" else "♝"
            PieceType.ROOK -> if (isWhite) "♖" else "♜"
            PieceType.QUEEN -> if (isWhite) "♕" else "♛"
            PieceType.KING -> if (isWhite) "♔" else "♚"
        }
    }
}
