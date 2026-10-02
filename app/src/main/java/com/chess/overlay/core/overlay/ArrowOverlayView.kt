package com.chess.overlay.core.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.MoveCandidate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom View performa tinggi untuk menggambar panah langkah catur persis seperti
 * engine analysis Chess.com / Lichess:
 * - Panah warna Cyan semi-transparan yang bersih & elegan.
 * - Tanpa teks menumpuk di atas bidak agar papan tetap terlihat jelas.
 * - Ujung kepala panah presisi tepat di tengah petak tujuan.
 */
class ArrowOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var boardBounds: BoardBounds? = null
    private var candidates: List<MoveCandidate> = emptyList()
    private var threats: List<MoveCandidate> = emptyList()
    var showOnlyBestMove: Boolean = true

    // Paint untuk badan panah utama (Cyan semi-transparan seperti Lichess/Chess.com)
    private val bestMovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(215, 56, 189, 248) // #38BDF8 dengan 85% opacity
    }

    private val bestMoveHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        color = Color.argb(215, 56, 189, 248)
    }

    // Paint untuk alternatif #2 - #5 (lebih tipis dan sedikit transparan)
    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val candidateHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
    }

    // Paint untuk ancaman lawan (Merah semi-transparan)
    private val threatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(200, 239, 68, 68)
    }

    private val threatHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        color = Color.argb(200, 239, 68, 68)
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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = boardBounds ?: return

        // 1. Gambar Ancaman Lawan jika ada (Merah Halus)
        for (threat in threats) {
            val (startX, startY) = bounds.getSquareCenterPixel(threat.from)
            val (endX, endY) = bounds.getSquareCenterPixel(threat.to)
            drawSleekArrow(
                canvas = canvas,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                strokePaint = threatPaint,
                headPaint = threatHeadPaint,
                shaftWidth = bounds.squareSize * 0.16f,
                headSize = bounds.squareSize * 0.36f
            )
        }

        if (candidates.isEmpty()) return

        // 2. Jika mode hanya Best Move (Persis seperti Image 2):
        if (showOnlyBestMove) {
            val best = candidates.firstOrNull() ?: return
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
                shaftWidth = bounds.squareSize * 0.22f, // Tebal & mantap seperti di Chess.com
                headSize = bounds.squareSize * 0.44f
            )
            return
        }

        // 3. Jika mode Top Lines (Diurutkan dari belakang agar #1 di posisi teratas)
        val sorted = candidates.sortedByDescending { it.rankOrder }
        for (cand in sorted) {
            val (startX, startY) = bounds.getSquareCenterPixel(cand.from)
            val (endX, endY) = bounds.getSquareCenterPixel(cand.to)

            if (cand.rankOrder == 1) {
                drawSleekArrow(
                    canvas, startX, startY, endX, endY,
                    bestMovePaint, bestMoveHeadPaint,
                    bounds.squareSize * 0.22f, bounds.squareSize * 0.44f
                )
            } else {
                val color = when (cand.rankOrder) {
                    2 -> Color.argb(170, 96, 165, 250)  // Biru muda
                    3 -> Color.argb(160, 168, 85, 247)  // Ungu
                    4 -> Color.argb(150, 251, 191, 36)  // Kuning
                    else -> Color.argb(140, 244, 114, 182) // Pink
                }
                candidatePaint.color = color
                candidateHeadPaint.color = color
                drawSleekArrow(
                    canvas, startX, startY, endX, endY,
                    candidatePaint, candidateHeadPaint,
                    bounds.squareSize * 0.14f, bounds.squareSize * 0.30f
                )
            }
        }
    }

    /**
     * Menggambar panah vektor ramping dengan sudut proporsional & presisi
     */
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
        val headAngle = Math.PI / 5.2 // ~35 derajat

        // Potong sedikit ujung batang agar pas di sambungan kepala
        val adjustedEndX = (endX - cos(angle) * (headSize * 0.45f)).toFloat()
        val adjustedEndY = (endY - sin(angle) * (headSize * 0.45f)).toFloat()

        // Badan panah
        canvas.drawLine(startX, startY, adjustedEndX, adjustedEndY, strokePaint)

        // Kepala panah berbentuk segitiga halus
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
