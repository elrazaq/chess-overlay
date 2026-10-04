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
 * 1. Menggambar hingga 5 panah rekomendasi Stockfish (Multi-PV = 5).
 *    Setiap peringkat memiliki warna elegan yang berbeda dan badge nomor urut #1 - #5.
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
    var onMoveDragged: ((from: Square, to: Square) -> Unit)? = null

    // Warna untuk 5 variasi jalur terbaik Stockfish:
    // Rank 1: Cyan / Emerald terang (#00E5FF)
    // Rank 2: Amber / Emas (#F59E0B)
    // Rank 3: Ungu / Violet (#A855F7)
    // Rank 4: Biru / Sky (#3B82F6)
    // Rank 5: Merah Muda / Rose (#EC4899)
    private val rankColors = intArrayOf(
        Color.parseColor("#00E5FF"), // Rank 1
        Color.parseColor("#F59E0B"), // Rank 2
        Color.parseColor("#A855F7"), // Rank 3
        Color.parseColor("#3B82F6"), // Rank 4
        Color.parseColor("#EC4899")  // Rank 5
    )

    private val rankAlphas = intArrayOf(240, 215, 195, 180, 165)

    private val dynamicStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dynamicHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
    }

    private val badgeCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
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
        color = Color.argb(130, 250, 204, 21) // Amber #FACC15
    }

    private val arrowPath = Path()
    private var downSquare: Square? = null

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
        val bounds = boardBounds ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downSquare = bounds.getSquareFromPixel(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val upSquare = bounds.getSquareFromPixel(event.x, event.y)
                val start = downSquare
                downSquare = null

                if (start != null && upSquare != null && start != upSquare) {
                    onMoveDragged?.invoke(start, upSquare)
                    invalidate()
                    return true
                }

                if (upSquare != null) {
                    onSquareTapped?.invoke(upSquare)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                downSquare = null
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

        // 3. Gambar hingga 6 panah rekomendasi langkah Stockfish (5 Top + 1 Langkah Kreatif/Manusiawi)
        // Gambar dari rank terendah ke rank 1 agar panah utama #1 berada di lapisan paling atas
        val displayCount = candidates.size.coerceAtMost(6)
        for (idx in (displayCount - 1) downTo 0) {
            val cand = candidates[idx]
            val (startX, startY) = bounds.getSquareCenterPixel(cand.from)
            val (endX, endY) = bounds.getSquareCenterPixel(cand.to)

            val baseColor = if (cand.isHuman) {
                Color.parseColor("#FF6D00") // Electric Fire Orange untuk Langkah Manusiawi / Mikhail Tal style
            } else {
                rankColors.getOrElse(idx) { Color.CYAN }
            }

            val alpha = if (cand.isHuman) 225 else rankAlphas.getOrElse(idx) { 160 }
            val colorWithAlpha = (alpha shl 24) or (baseColor and 0x00FFFFFF)

            dynamicStrokePaint.color = colorWithAlpha
            dynamicHeadPaint.color = colorWithAlpha

            val shaftFactor = if (cand.isHuman) {
                0.13f
            } else when (idx) {
                0 -> 0.20f
                1 -> 0.16f
                2 -> 0.14f
                3 -> 0.12f
                else -> 0.10f
            }

            drawSleekArrow(
                canvas = canvas,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                strokePaint = dynamicStrokePaint,
                headPaint = dynamicHeadPaint,
                shaftWidth = bounds.squareSize * shaftFactor,
                headSize = bounds.squareSize * (shaftFactor * 2.1f)
            )

            // Tampilkan nomor badge #1 - #5 atau badge "H" untuk langkah kreatif/manusiawi
            if (displayCount > 1) {
                val badgeRadius = bounds.squareSize * 0.15f
                badgeCirclePaint.color = baseColor
                badgeTextPaint.textSize = badgeRadius * 1.3f
                canvas.drawCircle(endX, endY, badgeRadius, badgeCirclePaint)
                val badgeText = if (cand.isHuman) "H" else "${idx + 1}"
                canvas.drawText(badgeText, endX, endY + badgeRadius * 0.38f, badgeTextPaint)
            }
        }
    }

    private fun drawMappingGrid(canvas: Canvas, bounds: BoardBounds) {
        val sq = bounds.squareSize
        canvas.drawRect(bounds.left, bounds.top, bounds.left + bounds.size, bounds.top + bounds.size, gridBorderPaint)

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
