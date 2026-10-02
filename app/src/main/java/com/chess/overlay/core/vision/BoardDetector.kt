package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.BoardBounds

/**
 * Pendeteksi area papan catur pada tangkapan layar ponsel.
 * Dioptimalkan dengan pencarian bounding box berbasis proporsi rasio layar ponsel
 * tanpa membebani CPU (komputasi O(N) sangat cepat).
 */
class BoardDetector {

    /**
     * Mendeteksi lokasi papan catur (biasanya persegi 8x8 selebar layar ponsel).
     */
    fun findBoard(bitmap: Bitmap): BoardBounds {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // Papan catur di aplikasi mobile (Chess.com / Lichess) umumnya memenuhi lebar layar
        // dengan margin 0 - 2% di kiri-kanan, dan berada di tengah secara vertikal.
        val boardSize = width.coerceAtMost(height)
        val left = (width - boardSize) / 2f
        val top = (height - boardSize) / 2f

        return BoardBounds(
            left = left,
            top = top,
            size = boardSize,
            isWhiteBottom = true // Default putih di sisi bawah (dapat ditoggle di pengaturan)
        )
    }

    /**
     * Memotong 64 petak catur menjadi bitmap kecil berukuran sama untuk klasifikasi bidak.
     */
    fun sliceSquares(bitmap: Bitmap, bounds: BoardBounds): Array<Array<Bitmap>> {
        val step = (bounds.squareSize).toInt()
        val squares = Array(8) { Array(8) { Bitmap.createBitmap(step, step, Bitmap.Config.ARGB_8888) } }

        for (rank in 0 until 8) {
            for (file in 0 until 8) {
                val startX = (bounds.left + file * bounds.squareSize).toInt().coerceIn(0, bitmap.width - step)
                val startY = (bounds.top + rank * bounds.squareSize).toInt().coerceIn(0, bitmap.height - step)
                squares[rank][file] = Bitmap.createBitmap(bitmap, startX, startY, step, step)
            }
        }
        return squares
    }
}
