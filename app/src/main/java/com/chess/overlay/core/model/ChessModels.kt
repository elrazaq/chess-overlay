package com.chess.overlay.core.model

/**
 * Representasi petak catur (0-7 file 'a'-'h', 0-7 rank '1'-'8')
 */
data class Square(
    val file: Int, // 0 = a, 7 = h
    val rank: Int  // 0 = 1, 7 = 8
) {
    companion object {
        fun fromUci(uciSquare: String): Square {
            if (uciSquare.length < 2) return Square(0, 0)
            val file = uciSquare[0].lowercaseChar() - 'a'
            val rank = (uciSquare[1] - '1')
            return Square(file.coerceIn(0, 7), rank.coerceIn(0, 7))
        }
    }

    fun toUci(): String {
        val f = ('a' + file)
        val r = ('1' + rank)
        return "$f$r"
    }
}

/**
 * Langkah catur dengan skor evaluasi dan statusnya
 */
data class MoveCandidate(
    val rankOrder: Int, // 1 = Best move, 2 = 2nd best, dst
    val from: Square,
    val to: Square,
    val scoreCp: Int = 0, // Centipawn score
    val isMate: Boolean = false,
    val mateMoves: Int = 0,
    val pvLine: List<String> = emptyList(), // Notasi kelanjutan langkah
    val isThreat: Boolean = false,
    val isHuman: Boolean = false
)

/**
 * Hasil kalkulasi engine lengkap
 */
data class BoardAnalysis(
    val fen: String,
    val candidates: List<MoveCandidate>, // 5 langkah terbaik
    val threats: List<MoveCandidate>,    // Ancaman lawan terberat
    val isWhiteToMove: Boolean = true
)

/**
 * Koordinat & ukuran papan catur di layar ponsel
 */
data class BoardBounds(
    val left: Float,
    val top: Float,
    val size: Float,
    val isWhiteBottom: Boolean = true
) {
    val squareSize: Float get() = size / 8f

    /**
     * Mengonversi koordinat petak catur ke koordinat pixel layar
     */
    fun getSquareCenterPixel(square: Square): Pair<Float, Float> {
        val displayFile = if (isWhiteBottom) square.file else (7 - square.file)
        val displayRank = if (isWhiteBottom) (7 - square.rank) else square.rank

        val x = left + (displayFile + 0.5f) * squareSize
        val y = top + (displayRank + 0.5f) * squareSize
        return Pair(x, y)
    }

    /**
     * Mengonversi koordinat sentuhan pixel layar ke petak catur (Square)
     */
    fun getSquareFromPixel(x: Float, y: Float): Square? {
        if (x < left || x > (left + size) || y < top || y > (top + size)) return null
        val col = ((x - left) / squareSize).toInt().coerceIn(0, 7)
        val row = ((y - top) / squareSize).toInt().coerceIn(0, 7)

        val file = if (isWhiteBottom) col else (7 - col)
        val rank = if (isWhiteBottom) (7 - row) else row
        return Square(file, rank)
    }
}

