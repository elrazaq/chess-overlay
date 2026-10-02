package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.BoardBounds

/**
 * Pendeteksi area papan catur yang presisi.
 * Mendeteksi batas atas papan (top) dengan memindai pola petak catur (checkerboard)
 * sehingga posisi panah pas 100% di tengah petak, terlepas dari adanya banner iklan,
 * header aplikasi, atau kotak dialog bot.
 */
class BoardDetector {

    /**
     * Menemukan batas papan catur pada tangkapan layar.
     */
    fun findBoard(bitmap: Bitmap, isWhiteBottom: Boolean = true): BoardBounds {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // Papan catur di Android selalu selebar layar (1:1 aspect ratio)
        val boardSize = width.coerceAtMost(height)

        // Pindai posisi vertikal di mana pola petak catur 8x8 dimulai
        val detectedTop = detectBoardTop(bitmap, boardSize.toInt())

        return BoardBounds(
            left = (width - boardSize) / 2f,
            top = detectedTop.toFloat(),
            size = boardSize,
            isWhiteBottom = isWhiteBottom
        )
    }

    /**
     * Memindai layar dari atas ke bawah untuk mendeteksi baris pertama petak catur.
     */
    private fun detectBoardTop(bitmap: Bitmap, boardWidth: Int): Int {
        val w = bitmap.width
        val h = bitmap.height
        val sq = (boardWidth / 8).coerceAtLeast(1)

        val minScanY = (h * 0.08).toInt()
        val maxScanY = (h - boardWidth).coerceAtLeast(minScanY)

        var bestTop = (h - boardWidth) / 2 // Default jika tidak terdeteksi
        var maxBoardScore = 0

        // Pindai setiap lompatan 4 pixel
        for (y in minScanY until maxScanY step 4) {
            val sampleY1 = y + sq / 2
            val sampleY2 = y + sq + sq / 2
            if (sampleY2 >= h) break

            var alternations = 0
            // Periksa kontras warna petak yang berselang-seling pada 4 petak horizontal
            for (col in 0 until 4) {
                val x1 = (col + 0.5f) * sq
                val x2 = (col + 1.5f) * sq
                if (x2 >= w) break

                val p1 = bitmap.getPixel(x1.toInt(), sampleY1)
                val p2 = bitmap.getPixel(x2.toInt(), sampleY1)

                val diff = getColorDifference(p1, p2)
                if (diff > 30) {
                    alternations++
                }
            }

            if (alternations >= 3) {
                // Konfirmasi bahwa ini adalah awal dari papan catur
                bestTop = y
                break
            }
        }

        return bestTop
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
