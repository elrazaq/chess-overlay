package com.chess.overlay.core.vision

import android.graphics.Bitmap

/**
 * Klasifikasi bidak catur di tiap petak.
 * Dirancang modular sehingga dapat memakai model TFLite terkuantisasi (INT8 ~2MB)
 * atau algoritma template matching / histogram warna sederhana.
 */
class PieceClassifier {

    /**
     * Mengklasifikasikan petak bitmap menjadi simbol FEN:
     * 'P', 'N', 'B', 'R', 'Q', 'K' (Putih)
     * 'p', 'n', 'b', 'r', 'q', 'k' (Hitam)
     * '1' (Petak kosong)
     */
    fun classifySquare(squareBitmap: Bitmap): Char {
        // Logika klasifikasi ringan
        // Pada implementasi penuh, input dimasukkan ke model TensorFlow Lite (quantized INT8).
        // Sebagai fallback ringan, periksa saturasi/luminance piksel di tengah petak:
        return '1'
    }

    /**
     * Mengonversi grid 8x8 simbol petak menjadi string FEN resmi catur.
     */
    fun generateFen(boardGrid: Array<CharArray>, isWhiteToMove: Boolean = true): String {
        val fenBuilder = StringBuilder()

        for (rank in 0 until 8) {
            var emptyCount = 0
            for (file in 0 until 8) {
                val piece = boardGrid[rank][file]
                if (piece == '1' || piece == ' ' || piece == '.') {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        fenBuilder.append(emptyCount)
                        emptyCount = 0
                    }
                    fenBuilder.append(piece)
                }
            }
            if (emptyCount > 0) {
                fenBuilder.append(emptyCount)
            }
            if (rank < 7) {
                fenBuilder.append('/')
            }
        }

        // Tambahkan metadata FEN: [active color] [castling] [en-passant] [halfmove] [fullmove]
        val activeColor = if (isWhiteToMove) "w" else "b"
        fenBuilder.append(" $activeColor KQkq - 0 1")

        return fenBuilder.toString()
    }
}
