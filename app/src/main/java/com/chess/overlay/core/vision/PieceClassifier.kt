package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.*

/**
 * Klasifikasi bidak catur cerdas berbasis analisis kontur & morfologi piksel.
 * Memetakan seluruh 64 petak secara otomatis ke BoardState & SetupBoardView.
 */
class PieceClassifier {

    /**
     * Memindai seluruh 64 petak dari bitmap layar dan memasukkan bidak ke dalam BoardState.
     */
    fun scanBoardToBoardState(
        bitmap: Bitmap,
        bounds: BoardBounds,
        boardState: BoardState
    ) {
        boardState.clearBoard()
        val sq = bounds.squareSize

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val startX = (bounds.left + col * sq).toInt().coerceIn(0, bitmap.width - 1)
                val startY = (bounds.top + row * sq).toInt().coerceIn(0, bitmap.height - 1)
                val s = sq.toInt().coerceAtMost(bitmap.width - startX).coerceAtMost(bitmap.height - startY)
                if (s <= 10) continue

                val detectedPiece = detectPieceAtSquare(bitmap, startX, startY, s)

                if (detectedPiece != null) {
                    val file = if (bounds.isWhiteBottom) col else (7 - col)
                    val rank = if (bounds.isWhiteBottom) (7 - row) else row
                    boardState.setPiece(Square(file, rank), detectedPiece)
                }
            }
        }

        // Sanitasi dasar: Pastikan minimal ada 1 Raja Putih & 1 Raja Hitam
        val (wk, bk) = boardState.countKings()
        if (wk == 0) {
            val file = if (bounds.isWhiteBottom) 4 else 3
            val rank = 0
            if (boardState.getPiece(Square(file, rank)) == null) {
                boardState.setPiece(Square(file, rank), Piece(PieceType.KING, true))
            } else {
                for (f in 0..7) {
                    if (boardState.getPiece(Square(f, 0)) == null) {
                        boardState.setPiece(Square(f, 0), Piece(PieceType.KING, true))
                        break
                    }
                }
            }
        }
        if (bk == 0) {
            val file = if (bounds.isWhiteBottom) 4 else 3
            val rank = 7
            if (boardState.getPiece(Square(file, rank)) == null) {
                boardState.setPiece(Square(file, rank), Piece(PieceType.KING, false))
            } else {
                for (f in 0..7) {
                    if (boardState.getPiece(Square(f, 7)) == null) {
                        boardState.setPiece(Square(f, 7), Piece(PieceType.KING, false))
                        break
                    }
                }
            }
        }
    }

    /**
     * Mendeteksi ada/tidaknya bidak pada suatu petak beserta jenis & warnanya.
     */
    fun detectPieceAtSquare(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        size: Int
    ): Piece? {
        val cornerSize = (size * 0.12f).toInt().coerceAtLeast(2)
        var cornerLumSum = 0
        var cornerCount = 0

        // 4 Pojok petak (representasi background petak)
        val cornerOffsets = listOf(
            Pair(0, 0),
            Pair(size - cornerSize, 0),
            Pair(0, size - cornerSize),
            Pair(size - cornerSize, size - cornerSize)
        )

        for ((ox, oy) in cornerOffsets) {
            for (cy in oy until (oy + cornerSize)) {
                val py = startY + cy
                if (py >= bitmap.height) continue
                for (cx in ox until (ox + cornerSize)) {
                    val px = startX + cx
                    if (px >= bitmap.width) continue
                    val p = bitmap.getPixel(px, py)
                    val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    cornerLumSum += lum
                    cornerCount++
                }
            }
        }

        if (cornerCount == 0) return null
        val bgLum = cornerLumSum / cornerCount

        // Pindai area tengah (margin 14% dari pinggir)
        val margin = (size * 0.14f).toInt()
        var piecePixels = 0
        var pieceLumSum = 0
        var minX = size
        var maxX = 0
        var minY = size
        var maxY = 0
        val pieceCoords = ArrayList<Pair<Int, Int>>()

        for (y in margin until (size - margin)) {
            val py = startY + y
            if (py >= bitmap.height) continue
            for (x in margin until (size - margin)) {
                val px = startX + x
                if (px >= bitmap.width) continue
                val p = bitmap.getPixel(px, py)
                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                val diff = Math.abs(lum - bgLum)

                // Jika kontras dengan petak cukup kuat, ini bagian dari bidak
                if (diff > 25) {
                    piecePixels++
                    pieceLumSum += lum
                    pieceCoords.add(Pair(x, y))
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val minPieceThreshold = (size * size * 0.07f).toInt()
        if (piecePixels < minPieceThreshold || minX > maxX || minY > maxY) {
            return null
        }

        // Tentukan warna bidak: Putih (terang) atau Hitam (gelap)
        val avgPieceLum = pieceLumSum / piecePixels
        val isWhite = avgPieceLum > (bgLum + 10) || avgPieceLum > 155

        // Analisis Morfologi
        val h = maxY - minY + 1
        val hRatio = h.toFloat() / size

        // Asimetri horizontal (Kuda sangat asimetris)
        val midX = (minX + maxX) / 2
        var leftCount = 0
        var rightCount = 0
        for ((cx, _) in pieceCoords) {
            if (cx < midX) leftCount++
            else if (cx > midX) rightCount++
        }
        val asym = Math.abs(leftCount - rightCount).toFloat() / piecePixels

        // Bobot bagian atas (Benteng berkepala datar & lebar)
        val topYThresh = minY + h * 0.25f
        var topArea = 0
        for ((_, cy) in pieceCoords) {
            if (cy < topYThresh) topArea++
        }
        val topRatio = topArea.toFloat() / piecePixels

        val pieceType = when {
            asym > 0.27f -> PieceType.KNIGHT
            hRatio < 0.65f -> PieceType.PAWN
            hRatio > 0.84f -> PieceType.KING
            topRatio > 0.22f -> PieceType.ROOK
            hRatio > 0.77f -> PieceType.QUEEN
            else -> PieceType.BISHOP
        }

        return Piece(pieceType, isWhite)
    }

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
                val piece = detectPieceAtSquare(bitmap, startX, startY, sq.toInt())

                val pieceChar = piece?.symbol ?: '1'
                val actualRank = if (bounds.isWhiteBottom) row else (7 - row)
                val actualFile = if (bounds.isWhiteBottom) col else (7 - col)

                boardGrid[actualRank][actualFile] = pieceChar
            }
        }

        sanitizeBoardGrid(boardGrid)
        return generateFen(boardGrid, isWhiteToMove)
    }

    private fun sanitizeBoardGrid(grid: Array<CharArray>) {
        var hasWhiteKing = false
        var hasBlackKing = false

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (grid[r][c] == 'K') hasWhiteKing = true
                if (grid[r][c] == 'k') hasBlackKing = true
                if ((r == 0 || r == 7) && (grid[r][c] == 'P' || grid[r][c] == 'p')) {
                    grid[r][c] = if (grid[r][c] == 'P') 'R' else 'r'
                }
            }
        }

        if (!hasWhiteKing) grid[7][4] = 'K'
        if (!hasBlackKing) grid[0][4] = 'k'
    }

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
