package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.*

/**
 * Klasifikasi bidak catur cerdas berbasis analisis kontur & background ratio.
 * Mencegah false-positive (petak kosong tidak akan dideteksi sebagai bidak).
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
     * Menggunakan analisis siluet morfologi akurat untuk tema bidak Chess.com (Neo/Classic).
     */
    fun detectPieceAtSquare(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        size: Int
    ): Piece? {
        val margin = (size * 0.12f).toInt().coerceAtLeast(4)
        val innerW = size - 2 * margin
        val innerH = size - 2 * margin
        if (innerW <= 4 || innerH <= 4) return null

        // Ambil warna latar petak yang aman dari teks koordinat (di posisi tengah-atas)
        val bgRefX = (startX + size * 0.5f).toInt().coerceIn(0, bitmap.width - 1)
        val bgRefY = (startY + size * 0.08f).toInt().coerceIn(0, bitmap.height - 1)
        val bgPixel = bitmap.getPixel(bgRefX, bgRefY)
        val bgR = (bgPixel shr 16) and 0xFF
        val bgG = (bgPixel shr 8) and 0xFF
        val bgB = bgPixel and 0xFF

        val grayGrid = Array(innerH) { IntArray(innerW) }
        var piecePixels = 0
        var brightPixels = 0
        var minX = innerW
        var maxX = 0
        var minY = innerH
        var maxY = 0
        val pieceCoords = ArrayList<Pair<Int, Int>>()

        for (y in 0 until innerH) {
            val py = (startY + margin + y).coerceIn(0, bitmap.height - 1)
            for (x in 0 until innerW) {
                val px = (startX + margin + x).coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val pr = (p shr 16) and 0xFF
                val pg = (p shr 8) and 0xFF
                val pb = p and 0xFF

                val lum = (pr * 299 + pg * 587 + pb * 114) / 1000
                grayGrid[y][x] = lum

                val diff = Math.abs(pr - bgR) + Math.abs(pg - bgG) + Math.abs(pb - bgB)
                if (diff > 40) {
                    piecePixels++
                    pieceCoords.add(Pair(x, y))
                    if (lum > 180) brightPixels++

                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val totalInner = innerW * innerH
        // Jika piksel bidak < 12% dari area petak, petak 100% KOSONG!
        if (piecePixels < (totalInner * 0.12f) || minX > maxX || minY > maxY) {
            return null
        }

        // 1. Deteksi Warna Bidak:
        // Bidak putih di Chess.com memiliki persentase piksel sangat terang (lum > 180) > 30%
        val isWhite = (brightPixels.toFloat() / piecePixels) > 0.30f

        // 2. Morfologi Siluet Bidak:
        val pw = (maxX - minX + 1).coerceAtLeast(1)
        val ph = (maxY - minY + 1).coerceAtLeast(1)
        val hRatio = ph.toFloat() / innerH
        val areaRatio = piecePixels.toFloat() / totalInner

        // Asimetri Kiri vs Kanan (Kuda/Knight menghadap ke kiri)
        val midX = (minX + maxX) / 2
        var leftCount = 0
        var rightCount = 0
        for ((cx, _) in pieceCoords) {
            if (cx < midX) leftCount++
            else if (cx > midX) rightCount++
        }
        val asym = Math.abs(leftCount - rightCount).toFloat() / piecePixels

        // Rasio Lebar Atas (Top 22%) terhadap Lebar Maksimal
        val topYThresh = minY + ph * 0.22f
        var topMinX = innerW
        var topMaxX = 0
        var hasTopPixels = false
        for ((cx, cy) in pieceCoords) {
            if (cy < topYThresh) {
                hasTopPixels = true
                if (cx < topMinX) topMinX = cx
                if (cx > topMaxX) topMaxX = cx
            }
        }
        val topW = if (hasTopPixels) (topMaxX - topMinX + 1) else 0
        val topWRatio = topW.toFloat() / pw

        val pieceType = when {
            // Pion: Bidak paling kecil & ramping
            areaRatio < 0.40f && hRatio < 0.88f -> PieceType.PAWN
            // Kuda: Asimetris
            asym > 0.065f && hRatio < 0.98f -> PieceType.KNIGHT
            // Benteng: Bagian atas lebar & datar
            topWRatio > 0.70f && hRatio < 0.95f -> PieceType.ROOK
            // Ratu: Mahkota melebar
            hRatio >= 0.92f && topWRatio > 0.80f -> PieceType.QUEEN
            // Raja: Paling tinggi dengan salib di puncak (lebar puncak kecil)
            hRatio >= 0.95f && topWRatio < 0.50f -> PieceType.KING
            // Gajah: Bahu lonjong dengan leher mengerucut
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
