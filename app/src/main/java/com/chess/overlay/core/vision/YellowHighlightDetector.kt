package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.Square

/**
 * Detektor petak catur & warna kuning penanda langkah (Move Highlight Tracker) untuk Chess.com.
 * Dioptimalkan untuk performa ultra-cepat (< 10ms) pada match Bullet:
 * - Otomatis mendeteksi batas koordinat papan (Board Top Y & Size) dari tangkapan layar
 * - Otomatis mendeteksi orientasi papan (Putih / Hitam di bawah)
 * - Mengambil sampel corner pixels dari 64 petak untuk mendeteksi petak kuning highlight
 * - Membedakan petak asal (FROM / kosong) dan petak tujuan (TO / ada bidak) via variansi piksel tengah
 */
class YellowHighlightDetector {

    /**
     * Otomatis memindai letak tepi atas papan catur (Top Y) pada Chess.com dari tangkapan layar.
     * Papan catur membentang 100% selebar layar (width). Di atas papan terdapat bar profil pemain gelap.
     * Mengembalikan BoardBounds yang presisi pixel-perfect.
     */
    fun detectBoardBounds(bitmap: Bitmap, forcedWhiteBottom: Boolean? = null): BoardBounds? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return null

        val startY = (h * 0.12f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.60f).toInt().coerceIn(0, h - 1)
        val probes = intArrayOf(
            (w * 0.12f).toInt().coerceIn(0, w - 1),
            (w * 0.28f).toInt().coerceIn(0, w - 1),
            (w * 0.50f).toInt().coerceIn(0, w - 1),
            (w * 0.72f).toInt().coerceIn(0, w - 1),
            (w * 0.88f).toInt().coerceIn(0, w - 1)
        )

        var detectedTopY: Int? = null
        for (y in startY until endY) {
            if (isBoardRow(bitmap, probes, y) &&
                isBoardRow(bitmap, probes, (y + 3).coerceAtMost(h - 1)) &&
                isBoardRow(bitmap, probes, (y + 6).coerceAtMost(h - 1))
            ) {
                detectedTopY = y
                break
            }
        }

        val topY = (detectedTopY ?: return null).toFloat()
        val isWhiteBottom = forcedWhiteBottom ?: detectOrientation(bitmap, topY.toInt(), w.toFloat())

        return BoardBounds(
            left = 0f,
            top = topY,
            size = w.toFloat(),
            isWhiteBottom = isWhiteBottom
        )
    }

    private fun isBoardRow(bitmap: Bitmap, probes: IntArray, y: Int): Boolean {
        var matchCount = 0
        for (px in probes) {
            val color = bitmap.getPixel(px, y)
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            // Petak catur (terang #EBECD0, gelap #779556, kuning #F5F682 / #BACA44)
            // Memiliki luminance hijau atau kecerahan yang jauh lebih tinggi daripada background header gelap (#262421)
            if ((g >= 80 && (r >= 95 || g >= 115)) || (r + g + b >= 270)) {
                matchCount++
            }
        }
        return matchCount >= 4
    }

    /**
     * Mendeteksi orientasi papan dengan membandingkan kecerahan baris pion atas vs bawah.
     * Bidak putih memiliki luminance > 180, bidak hitam < 130.
     */
    fun detectOrientation(bitmap: Bitmap, topY: Int, boardSize: Float): Boolean {
        val sqSize = boardSize / 8f
        val w = bitmap.width
        val h = bitmap.height

        var topLum = 0.0
        var botLum = 0.0
        var sampleCount = 0

        for (col in 0 until 8) {
            val cx = ((col + 0.5f) * sqSize).toInt().coerceIn(0, w - 1)
            val cyTop = (topY + 1.5f * sqSize).toInt().coerceIn(0, h - 1)
            val cyBot = (topY + 6.5f * sqSize).toInt().coerceIn(0, h - 1)

            for (dy in -2..2) {
                for (dx in -2..2) {
                    val px = (cx + dx).coerceIn(0, w - 1)
                    val pyT = (cyTop + dy).coerceIn(0, h - 1)
                    val pyB = (cyBot + dy).coerceIn(0, h - 1)

                    val cT = bitmap.getPixel(px, pyT)
                    val cB = bitmap.getPixel(px, pyB)

                    val lumT = (((cT shr 16) and 0xFF) + ((cT shr 8) and 0xFF) + (cT and 0xFF)) / 3.0
                    val lumB = (((cB shr 16) and 0xFF) + ((cB shr 8) and 0xFF) + (cB and 0xFF)) / 3.0

                    topLum += lumT
                    botLum += lumB
                    sampleCount++
                }
            }
        }

        if (sampleCount == 0) return true
        return (botLum / sampleCount) > (topLum / sampleCount)
    }

    /**
     * Memeriksa seluruh 64 petak pada tangkapan layar bitmap berdasarkan boardBounds.
     * Mengembalikan daftar Square catur yang sedang berwarna kuning highlight.
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

                // Titik sampling pojok kiri atas dan kanan atas petak (14%-22% dari tepi petak)
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
     * Membedakan petak asal (FROM) dan tujuan (TO) dari 2 petak kuning yang terdeteksi.
     * Menggunakan validasi langkah catur legal & status virtual terlebih dahulu agar 100% akurat.
     * Jika ambigu, fallback ke variansi piksel tengah.
     */
    fun detectMoveFromYellowSquares(
        bitmap: Bitmap,
        bounds: BoardBounds,
        sq1: Square,
        sq2: Square,
        boardState: BoardState? = null
    ): Pair<Square, Square> {
        if (boardState != null) {
            val turn = boardState.isWhiteToMove
            val p1 = boardState.getPiece(sq1)
            val p2 = boardState.getPiece(sq2)

            // 1. Cek legalitas langkah catur murni: mana yang bisa melangkah ke yang lain secara sah
            val sq1Legal = p1 != null && p1.isWhite == turn && boardState.isValidMove(sq1, sq2)
            val sq2Legal = p2 != null && p2.isWhite == turn && boardState.isValidMove(sq2, sq1)

            if (sq1Legal && !sq2Legal) return Pair(sq1, sq2)
            if (sq2Legal && !sq1Legal) return Pair(sq2, sq1)

            // 2. Berdasarkan kepemilikan bidak pada giliran aktif
            if (p1 != null && p1.isWhite == turn && (p2 == null || p2.isWhite != turn)) return Pair(sq1, sq2)
            if (p2 != null && p2.isWhite == turn && (p1 == null || p1.isWhite != turn)) return Pair(sq2, sq1)

            // 3. Petak mana yang berisi bidak vs petak kosong
            if (p1 != null && p2 == null) return Pair(sq1, sq2)
            if (p2 != null && p1 == null) return Pair(sq2, sq1)
        }

        val v1 = getCenterVarianceFromSquareCorner(bitmap, bounds, sq1)
        val v2 = getCenterVarianceFromSquareCorner(bitmap, bounds, sq2)

        return if (v1 < v2) {
            Pair(sq1, sq2) // sq1 kosong (from), sq2 ada bidak (to)
        } else {
            Pair(sq2, sq1) // sq2 kosong (from), sq1 ada bidak (to)
        }
    }

    private fun getCenterVarianceFromSquareCorner(bitmap: Bitmap, bounds: BoardBounds, square: Square): Double {
        val col = if (bounds.isWhiteBottom) square.file else (7 - square.file)
        val row = if (bounds.isWhiteBottom) (7 - square.rank) else square.rank

        val sqX = bounds.left + col * bounds.squareSize
        val sqY = bounds.top + row * bounds.squareSize
        val sqSize = bounds.squareSize
        val bmpW = bitmap.width
        val bmpH = bitmap.height

        val refX = (sqX + sqSize * 0.14f).toInt().coerceIn(0, bmpW - 1)
        val refY = (sqY + sqSize * 0.14f).toInt().coerceIn(0, bmpH - 1)
        val refColor = bitmap.getPixel(refX, refY)
        val refR = (refColor shr 16) and 0xFF
        val refG = (refColor shr 8) and 0xFF
        val refB = refColor and 0xFF

        val startX = (sqX + sqSize * 0.35f).toInt().coerceIn(0, bmpW - 1)
        val endX = (sqX + sqSize * 0.65f).toInt().coerceIn(0, bmpW - 1)
        val startY = (sqY + sqSize * 0.35f).toInt().coerceIn(0, bmpH - 1)
        val endY = (sqY + sqSize * 0.65f).toInt().coerceIn(0, bmpH - 1)

        var varianceSum = 0.0
        var count = 0
        val step = ((endX - startX) / 6).coerceAtLeast(1)

        for (y in startY..endY step step) {
            for (x in startX..endX step step) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val dr = r - refR
                val dg = g - refG
                val db = b - refB
                varianceSum += (dr * dr + dg * dg + db * db)
                count++
            }
        }

        return if (count > 0) varianceSum / count else 0.0
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
