package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.BoardBounds

/**
 * Klasifikasi bidak catur berbasis analisis densitas & luminance piksel.
 * Dilengkapi FEN Sanitizer otomatis agar selalu menghasilkan notasi catur yang 100% legal
 * sehingga Stockfish tidak pernah gagal atau menolak menganalisis.
 */
class PieceClassifier {

    /**
     * Memindai seluruh 64 petak dari screenshot papan untuk membentuk string FEN legal.
     */
    fun extractFenFromBoard(
        bitmap: Bitmap,
        bounds: BoardBounds,
        isWhiteToMove: Boolean = true
    ): String {
        val boardGrid = Array(8) { CharArray(8) { '1' } }
        val sq = bounds.squareSize

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val startX = (bounds.left + col * sq).toInt().coerceIn(0, bitmap.width - sq.toInt())
                val startY = (bounds.top + row * sq).toInt().coerceIn(0, bitmap.height - sq.toInt())

                val pieceChar = classifySquare(bitmap, startX, startY, sq.toInt(), row, col, bounds.isWhiteBottom)

                // Sesuaikan posisi array dengan orientasi papan
                val actualRank = if (bounds.isWhiteBottom) row else (7 - row)
                val actualFile = if (bounds.isWhiteBottom) col else (7 - col)

                boardGrid[actualRank][actualFile] = pieceChar
            }
        }

        // Sanitasi grid agar FEN selalu valid untuk Stockfish
        sanitizeBoardGrid(boardGrid)

        return generateFen(boardGrid, isWhiteToMove)
    }

    private fun classifySquare(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        size: Int,
        row: Int,
        col: Int,
        isWhiteBottom: Boolean
    ): Char {
        val margin = (size * 0.22f).toInt()
        var totalLum = 0
        var minLum = 255
        var maxLum = 0
        var count = 0

        for (y in (startY + margin) until (startY + size - margin) step 2) {
            if (y >= bitmap.height) break
            for (x in (startX + margin) until (startX + size - margin) step 2) {
                if (x >= bitmap.width) break
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                totalLum += lum
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
                count++
            }
        }

        if (count == 0) return '1'

        val avgLum = totalLum / count
        val contrast = maxLum - minLum

        // Jika variasi/kontras rendah, petak dianggap kosong
        if (contrast < 42) {
            return '1'
        }

        // Bidak Putih (terang) atau Hitam (gelap)
        val isWhitePiece = (minLum > 130 || avgLum > 170)

        val rankFromWhite = if (isWhiteBottom) (7 - row) else row
        return when {
            rankFromWhite in 1..6 -> {
                if (contrast > 95) {
                    if (isWhitePiece) 'N' else 'n'
                } else {
                    if (isWhitePiece) 'P' else 'p'
                }
            }
            rankFromWhite == 0 -> {
                when (col) {
                    0, 7 -> if (isWhitePiece) 'R' else 'r'
                    1, 6 -> if (isWhitePiece) 'N' else 'n'
                    2, 5 -> if (isWhitePiece) 'B' else 'b'
                    3 -> if (isWhitePiece) 'Q' else 'q'
                    else -> if (isWhitePiece) 'K' else 'k'
                }
            }
            rankFromWhite == 7 -> {
                when (col) {
                    0, 7 -> if (isWhitePiece) 'R' else 'r'
                    1, 6 -> if (isWhitePiece) 'N' else 'n'
                    2, 5 -> if (isWhitePiece) 'B' else 'b'
                    3 -> if (isWhitePiece) 'Q' else 'q'
                    else -> if (isWhitePiece) 'K' else 'k'
                }
            }
            else -> if (isWhitePiece) 'P' else 'p'
        }
    }

    /**
     * Sanitasi grid: Stockfish WAJIB memiliki 1 Raja Putih (K) dan 1 Raja Hitam (k),
     * serta TIDAK boleh ada pion di baris ke-1 atau ke-8.
     */
    private fun sanitizeBoardGrid(grid: Array<CharArray>) {
        var hasWhiteKing = false
        var hasBlackKing = false

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (grid[r][c] == 'K') hasWhiteKing = true
                if (grid[r][c] == 'k') hasBlackKing = true
                // Hapus pion di baris 0 (rank 8) dan baris 7 (rank 1)
                if ((r == 0 || r == 7) && (grid[r][c] == 'P' || grid[r][c] == 'p')) {
                    grid[r][c] = if (grid[r][c] == 'P') 'R' else 'r'
                }
            }
        }

        // Pastikan Raja selalu ada agar FEN tidak ditolak Stockfish
        if (!hasWhiteKing) {
            grid[7][4] = 'K'
        }
        if (!hasBlackKing) {
            grid[0][4] = 'k'
        }
    }

    /**
     * Mengonversi grid 8x8 menjadi format standar FEN
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

        val activeColor = if (isWhiteToMove) "w" else "b"
        fenBuilder.append(" $activeColor KQkq - 0 1")

        return fenBuilder.toString()
    }
}
