package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.Square

/**
 * Detektor petak kuning penanda langkah (Move Highlight Tracker) untuk catur online (Chess.com, dll).
 * Dioptimalkan untuk performa ultra-cepat (< 15ms) pada match Bullet:
 * - Mengambil sampel corner pixels dari 64 petak (menghindari glyph bidak di tengah)
 * - Mengenali warna kuning petak terang (#F5F682) maupun petak gelap (#BACA44)
 * - Menghasilkan daftar Square catur yang sedang berstatus kuning/highlight
 */
class YellowHighlightDetector {

    /**
     * Memeriksa seluruh 64 petak pada tangkapan layar bitmap berdasarkan boardBounds.
     * Mengembalikan daftar Square catur yang sedang berwarna kuning.
     */
    fun detectYellowSquares(bitmap: Bitmap, bounds: BoardBounds): List<Square> {
        val yellowSquares = mutableListOf<Square>()
        val sqSize = bounds.squareSize
        val bmpWidth = bitmap.width
        val bmpHeight = bitmap.height

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val sqX = bounds.left + col * sqSize
                val sqY = bounds.top + row * sqSize

                // Titik sampling pojok kiri atas dan pojok kanan atas (12%-22% dari tepi petak)
                // Posisi pojok aman dari gambar bidak catur yang berada di tengah
                val probePoints = arrayOf(
                    Pair(sqX + sqSize * 0.14f, sqY + sqSize * 0.14f),
                    Pair(sqX + sqSize * 0.20f, sqY + sqSize * 0.14f),
                    Pair(sqX + sqSize * 0.14f, sqY + sqSize * 0.20f),
                    Pair(sqX + sqSize * 0.82f, sqY + sqSize * 0.14f)
                )

                var yellowProbesCount = 0
                for ((px, py) in probePoints) {
                    val ix = px.toInt().coerceIn(0, bmpWidth - 1)
                    val iy = py.toInt().coerceIn(0, bmpHeight - 1)
                    val color = bitmap.getPixel(ix, iy)
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF

                    if (isYellowColor(r, g, b)) {
                        yellowProbesCount++
                    }
                }

                // Jika mayoritas titik probe mendeteksi warna kuning
                if (yellowProbesCount >= 2) {
                    val file = if (bounds.isWhiteBottom) col else (7 - col)
                    val rank = if (bounds.isWhiteBottom) (7 - row) else row
                    yellowSquares.add(Square(file, rank))
                }
            }
        }

        return yellowSquares
    }

    /**
     * Formula warna kuning akurat untuk Chess.com:
     * - Highlight petak terang: #F5F682 (R: 245, G: 246, B: 130)
     * - Highlight petak gelap: #BACA44 (R: 186, G: 202, B: 68)
     */
    private fun isYellowColor(r: Int, g: Int, b: Int): Boolean {
        return r >= 155 &&
                g >= 165 &&
                b <= 155 &&
                (r + g - 2 * b) >= 90 &&
                (r > b + 35) &&
                (g > b + 35)
    }
}
