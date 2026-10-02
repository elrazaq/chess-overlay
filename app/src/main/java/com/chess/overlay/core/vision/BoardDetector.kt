package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.BoardBounds

/**
 * Pendeteksi area papan catur yang presisi.
 * Menggunakan analisis transisi 8-kolom horizontal dengan sliding window
 * untuk menemukan koordinat bujur sangkar 1:1 papan catur secara akurat.
 */
class BoardDetector {

    /**
     * Menemukan batas papan catur pada tangkapan layar.
     */
    fun findBoard(bitmap: Bitmap, isWhiteBottom: Boolean = true): BoardBounds {
        val width = bitmap.width
        val height = bitmap.height
        val boardSize = Math.min(width, height)

        val rowScores = IntArray(height)
        for (y in 0 until height step 2) {
            var transitions = 0
            for (i in 1..7) {
                val xl = ((i - 0.25f) * boardSize / 8f).toInt().coerceIn(0, width - 1)
                val xr = ((i + 0.25f) * boardSize / 8f).toInt().coerceIn(0, width - 1)
                val p1 = bitmap.getPixel(xl, y)
                val p2 = bitmap.getPixel(xr, y)
                if (getColorDifference(p1, p2) > 38) {
                    transitions++
                }
            }
            rowScores[y] = transitions
            if (y + 1 < height) rowScores[y + 1] = transitions
        }

        var bestTop = (height - boardSize) / 2
        var bestSum = -1
        val minY = (height * 0.05f).toInt()
        val maxY = (height - boardSize).coerceAtLeast(minY)

        for (y in minY until maxY step 2) {
            var sum = 0
            for (dy in 0 until boardSize step 4) {
                sum += rowScores[y + dy]
            }
            if (sum > bestSum) {
                bestSum = sum
                bestTop = y
            }
        }

        return BoardBounds(
            left = (width - boardSize) / 2f,
            top = bestTop.toFloat(),
            size = boardSize.toFloat(),
            isWhiteBottom = isWhiteBottom
        )
    }

    private fun getColorDifference(c1: Int, c2: Int): Int {
        val r1 = (c1 shr 16) and 0xFF
        val g1 = (c1 shr 8) and 0xFF
        val b1 = c1 and 0xFF

        val r2 = (c2 shr 16) and 0xFF
        val g2 = (c2 shr 8) and 0xFF
        val b2 = c2 and 0xFF

        return Math.abs(r1 - r2) + Math.abs(g1 - g2) + Math.abs(b1 - b2)
    }
}
