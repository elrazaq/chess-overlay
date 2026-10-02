package com.chess.overlay.core.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.MoveCandidate
import com.chess.overlay.core.model.Square
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom View untuk:
 * 1. Menggambar panah rekomendasi Stockfish (Cyan elegan seperti di Chess.com/Lichess).
 * 2. Menampilkan grid kalibrasi transparan saat memetakan posisi papan.
 * 3. Menghighlight petak yang dipilih saat menggerakkan anak catur secara manual.
 */
class ArrowOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var boardBounds: BoardBounds? = null
    var boardState: BoardState? = null
    private var candidates: List<MoveCandidate> = emptyList()
    private var threats: List<MoveCandidate> = emptyList()

    // Mode flags
    var isMappingMode: Boolean = false
    var isInputMoveMode: Boolean = false
    var selectedSquare: Square? = null

    // Callback saat petak catur disentuh dalam mode manual input
    var onSquareTapped: ((Square) -> Unit)? = null

    // Paints
    private val bestMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(225, 56, 189, 248) // Cyan #38BDF8
    }

    private val bestMoveHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        color = Color.argb(225, 56, 189, 248)
    }

    private val gridBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 34, 197, 94) // Green
        strokeWidth = 4f
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 34, 197, 94)
        strokeWidth = 2f
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(120, 250, 204, 21) // Amber #FACC15
    }

    private val pieceIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arrowPath = Path()

    fun updateAnalysis(
        bounds: BoardBounds,
        candidateMoves: List<MoveCandidate>,
        threatMoves: List<MoveCandidate> = emptyList()
    ) {
        this.boardBounds = bounds
        this.candidates = candidateMoves
        this.threats = threatMoves
        invalidate()
    }

    fun clearOverlay() {
        this.candidates = emptyList()
        this.threats = emptyList()
        this.selectedSquare = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInputMoveMode) return false

        if (event.action == MotionEvent.ACTION_UP) {
            val bounds = boardBounds ?: return false
            val tappedSquare = bounds.getSquareFromPixel(event.x, event.y)
            if (tappedSquare != null) {
                onSquareTapped?.invoke(tappedSquare)
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = boardBounds ?: return

        // 1. Gambar Grid Kalibrasi jika sedang dalam Mode Mapping
        if (isMappingMode) {
            drawMappingGrid(canvas, bounds)
        }

        // 2. Highlight Petak Terpilih (Source square saat menggerakkan anak catur)
        selectedSquare?.let { sq ->
            val (cx, cy) = bounds.getSquareCenterPixel(sq)
            val half = bounds.squareSize / 2f
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, highlightPaint)
        }

        // 3. Gambar panah rekomendasi langkah terbaik
        if (candidates.isNotEmpty()) {
            val best = candidates.first()
            val (startX, startY) = bounds.getSquareCenterPixel(best.from)
            val (endX, endY) = bounds.getSquareCenterPixel(best.to)

            drawSleekArrow(
                canvas = canvas,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                strokePaint = bestMovePaint,
                headPaint = bestMoveHeadPaint,
                shaftWidth = bounds.squareSize * 0.22f,
                headSize = bounds.squareSize * 0.44f
            )
        }
    }

    private fun drawMappingGrid(canvas: Canvas, bounds: BoardBounds) {
        val sq = bounds.squareSize
        // Border luar papan
        canvas.drawRect(bounds.left, bounds.top, bounds.left + bounds.size, bounds.top + bounds.size, gridBorderPaint)

        // Garis-garis petak 8x8
        for (i in 1 until 8) {
            val x = bounds.left + i * sq
            canvas.drawLine(x, bounds.top, x, bounds.top + bounds.size, gridLinePaint)
            val y = bounds.top + i * sq
            canvas.drawLine(bounds.left, y, bounds.left + bounds.size, y, gridLinePaint)
        }
    }

    private fun drawSleekArrow(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        strokePaint: Paint,
        headPaint: Paint,
        shaftWidth: Float,
        headSize: Float
    ) {
        strokePaint.strokeWidth = shaftWidth

        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val headAngle = Math.PI / 5.2

        val adjustedEndX = (endX - cos(angle) * (headSize * 0.45f)).toFloat()
        val adjustedEndY = (endY - sin(angle) * (headSize * 0.45f)).toFloat()

        canvas.drawLine(startX, startY, adjustedEndX, adjustedEndY, strokePaint)

        arrowPath.reset()
        arrowPath.moveTo(endX, endY)
        val x1 = (endX - headSize * cos(angle - headAngle)).toFloat()
        val y1 = (endY - headSize * sin(angle - headAngle)).toFloat()
        val x2 = (endX - headSize * cos(angle + headAngle)).toFloat()
        val y2 = (endY - headSize * sin(angle + headAngle)).toFloat()

        arrowPath.lineTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.close()

        canvas.drawPath(arrowPath, headPaint)
    }
}
