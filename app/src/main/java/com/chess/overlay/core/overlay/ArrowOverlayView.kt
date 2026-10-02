package com.chess.overlay.core.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.chess.overlay.R
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.MoveCandidate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom View performa tinggi untuk menggambar panah langkah catur dan ancaman.
 * Menggunakan hardware-accelerated 2D Canvas standar Android (sangat hemat RAM).
 */
class ArrowOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var boardBounds: BoardBounds? = null
    private var candidates: List<MoveCandidate> = emptyList()
    private var threats: List<MoveCandidate> = emptyList()

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val arrowPath = Path()

    fun updateAnalysis(
        bounds: BoardBounds,
        candidateMoves: List<MoveCandidate>,
        threatMoves: List<MoveCandidate>
    ) {
        this.boardBounds = bounds
        this.candidates = candidateMoves
        this.threats = threatMoves
        invalidate() // Redraw canvas
    }

    fun clearOverlay() {
        this.candidates = emptyList()
        this.threats = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = boardBounds ?: return

        // 1. Gambar Ancaman Terberat Lawan (Merah Menyala)
        for (threat in threats) {
            val (startX, startY) = bounds.getSquareCenterPixel(threat.from)
            val (endX, endY) = bounds.getSquareCenterPixel(threat.to)
            drawArrow(
                canvas = canvas,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                color = ContextCompat.getColor(context, R.color.threat_arrow),
                strokeWidth = 14f,
                label = "⚠️ Ancaman"
            )
        }

        // 2. Gambar 5 Jalur Terbaik (Diurutkan dari 5 ke 1 agar #1 berada di layer teratas)
        val sortedCandidates = candidates.sortedByDescending { it.rankOrder }
        for (move in sortedCandidates) {
            val (startX, startY) = bounds.getSquareCenterPixel(move.from)
            val (endX, endY) = bounds.getSquareCenterPixel(move.to)

            val (colorRes, strokeWidth) = when (move.rankOrder) {
                1 -> Pair(R.color.best_move_arrow, 16f) // Langkah terbaik paling tebal
                2 -> Pair(R.color.candidate_2, 12f)
                3 -> Pair(R.color.candidate_3, 10f)
                4 -> Pair(R.color.candidate_4, 9f)
                else -> Pair(R.color.candidate_5, 8f)
            }

            val scoreText = if (move.isMate) {
                "#${move.rankOrder} (M${move.mateMoves})"
            } else {
                val sign = if (move.scoreCp >= 0) "+" else ""
                val score = String.format("%.1f", move.scoreCp / 100.0)
                "#${move.rankOrder} ($sign$score)"
            }

            drawArrow(
                canvas = canvas,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                color = ContextCompat.getColor(context, colorRes),
                strokeWidth = strokeWidth,
                label = scoreText
            )
        }
    }

    private fun drawArrow(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        color: Int,
        strokeWidth: Float,
        label: String
    ) {
        arrowPaint.color = color
        arrowPaint.strokeWidth = strokeWidth
        headPaint.color = color

        // Sudut arah panah
        val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        val headLength = strokeWidth * 2.8f
        val headAngle = Math.PI / 6.0 // 30 derajat

        // Potong sedikit ujung batang panah agar pas dengan segitiga kepala
        val adjustedEndX = (endX - cos(angle) * (headLength * 0.5f)).toFloat()
        val adjustedEndY = (endY - sin(angle) * (headLength * 0.5f)).toFloat()

        // Garis badan panah
        canvas.drawLine(startX, startY, adjustedEndX, adjustedEndY, arrowPaint)

        // Segitiga kepala panah
        arrowPath.reset()
        arrowPath.moveTo(endX, endY)
        val x1 = (endX - headLength * cos(angle - headAngle)).toFloat()
        val y1 = (endY - headLength * sin(angle - headAngle)).toFloat()
        val x2 = (endX - headLength * cos(angle + headAngle)).toFloat()
        val y2 = (endY - headLength * sin(angle + headAngle)).toFloat()

        arrowPath.lineTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.close()

        canvas.drawPath(arrowPath, headPaint)

        // Label skor/urutan langkah di tengah jalur panah
        val midX = (startX + endX) / 2f
        val midY = (startY + endY) / 2f
        canvas.drawText(label, midX + 12f, midY - 12f, textPaint)
    }
}
