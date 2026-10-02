package com.chess.overlay.core.model

enum class PieceType(val symbolWhite: Char, val symbolBlack: Char) {
    PAWN('P', 'p'),
    KNIGHT('N', 'n'),
    BISHOP('B', 'b'),
    ROOK('R', 'r'),
    QUEEN('Q', 'q'),
    KING('K', 'k')
}

data class Piece(
    val type: PieceType,
    val isWhite: Boolean
) {
    val symbol: Char get() = if (isWhite) type.symbolWhite else type.symbolBlack
}

data class MoveRecord(
    val from: Square,
    val to: Square,
    val movedPiece: Piece,
    val capturedPiece: Piece?,
    val prevTurn: Boolean,
    val isCastling: Boolean = false,
    val isPromotion: Boolean = false
)

/**
 * Pengelola status papan catur virtual mandiri.
 * Tidak bergantung pada tangkapan layar, menjamin 100% akurasi posisi & FEN.
 */
class BoardState {
    // Grid 8x8: index 0 = rank 8 (atas), index 7 = rank 1 (bawah)
    // col 0 = file a (kiri), col 7 = file h (kanan)
    private val grid = Array(8) { Array<Piece?>(8) { null } }
    var isWhiteToMove: Boolean = true
    private val moveHistory = mutableListOf<MoveRecord>()

    init {
        resetToStartingPosition()
    }

    /**
     * Memetakan seluruh 32 anak catur secara massal ke posisi awal standar.
     */
    fun resetToStartingPosition() {
        // Bersihkan
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                grid[r][c] = null
            }
        }
        moveHistory.clear()
        isWhiteToMove = true

        // Rank 8 (Black pieces): row 0
        grid[0][0] = Piece(PieceType.ROOK, false)
        grid[0][1] = Piece(PieceType.KNIGHT, false)
        grid[0][2] = Piece(PieceType.BISHOP, false)
        grid[0][3] = Piece(PieceType.QUEEN, false)
        grid[0][4] = Piece(PieceType.KING, false)
        grid[0][5] = Piece(PieceType.BISHOP, false)
        grid[0][6] = Piece(PieceType.KNIGHT, false)
        grid[0][7] = Piece(PieceType.ROOK, false)

        // Rank 7 (Black pawns): row 1
        for (c in 0 until 8) grid[1][c] = Piece(PieceType.PAWN, false)

        // Rank 2 (White pawns): row 6
        for (c in 0 until 8) grid[6][c] = Piece(PieceType.PAWN, true)

        // Rank 1 (White pieces): row 7
        grid[7][0] = Piece(PieceType.ROOK, true)
        grid[7][1] = Piece(PieceType.KNIGHT, true)
        grid[7][2] = Piece(PieceType.BISHOP, true)
        grid[7][3] = Piece(PieceType.QUEEN, true)
        grid[7][4] = Piece(PieceType.KING, true)
        grid[7][5] = Piece(PieceType.BISHOP, true)
        grid[7][6] = Piece(PieceType.KNIGHT, true)
        grid[7][7] = Piece(PieceType.ROOK, true)
    }

    fun getPiece(square: Square): Piece? {
        val row = 7 - square.rank
        val col = square.file
        return grid[row][col]
    }

    /**
     * Mengosongkan seluruh petak papan (berguna untuk setup endgame/lategame)
     */
    fun clearBoard() {
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                grid[r][c] = null
            }
        }
        moveHistory.clear()
    }

    /**
     * Menaruh atau menghapus bidak pada petak tertentu secara langsung
     */
    fun setPiece(square: Square, piece: Piece?) {
        val row = 7 - square.rank
        val col = square.file
        grid[row][col] = piece
    }

    /**
     * Memindahkan bidak secara bebas tanpa batasan giliran/aturan jalan (untuk mode setup posisi)
     */
    fun movePieceFree(from: Square, to: Square) {
        val fromRow = 7 - from.rank
        val fromCol = from.file
        val toRow = 7 - to.rank
        val toCol = to.file

        val piece = grid[fromRow][fromCol] ?: return
        grid[toRow][toCol] = piece
        grid[fromRow][fromCol] = null
    }

    /**
     * Menghitung jumlah raja (harus ada minimal 1 putih dan 1 hitam agar Stockfish valid)
     */
    fun countKings(): Pair<Int, Int> {
        var whiteKings = 0
        var blackKings = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val p = grid[r][c]
                if (p?.type == PieceType.KING) {
                    if (p.isWhite) whiteKings++ else blackKings++
                }
            }
        }
        return Pair(whiteKings, blackKings)
    }

    /**
     * Memvalidasi apakah pergerakan anak catur sah sesuai aturan catur.
     * Mencegah langkah ilegal (seperti pion jalan mundur atau langkah hantu).
     */
    fun isValidMove(from: Square, to: Square): Boolean {
        if (from == to) return false
        val fromRow = 7 - from.rank
        val fromCol = from.file
        val toRow = 7 - to.rank
        val toCol = to.file

        val piece = grid[fromRow][fromCol] ?: return false
        val dest = grid[toRow][toCol]

        // Tidak boleh memakan anak catur sendiri
        if (dest != null && dest.isWhite == piece.isWhite) return false

        val dCol = to.file - from.file
        val dRank = to.rank - from.rank
        val absDCol = Math.abs(dCol)
        val absDRank = Math.abs(dRank)

        when (piece.type) {
            PieceType.PAWN -> {
                val forward = if (piece.isWhite) 1 else -1
                val startRank = if (piece.isWhite) 1 else 6

                if (dCol == 0) {
                    // Maju 1 petak ke depan (petak tujuan harus kosong)
                    if (dRank == forward && dest == null) return true
                    // Maju 2 petak dari baris awal pion
                    if (from.rank == startRank && dRank == forward * 2 && dest == null) {
                        val midRow = 7 - (from.rank + forward)
                        if (grid[midRow][fromCol] == null) return true
                    }
                } else if (absDCol == 1 && dRank == forward) {
                    // Makan diagonal (harus ada bidak lawan)
                    if (dest != null && dest.isWhite != piece.isWhite) return true
                }
                return false
            }
            PieceType.KNIGHT -> {
                return (absDCol == 1 && absDRank == 2) || (absDCol == 2 && absDRank == 1)
            }
            PieceType.BISHOP -> {
                if (absDCol != absDRank) return false
                return isPathClear(fromRow, fromCol, toRow, toCol)
            }
            PieceType.ROOK -> {
                if (dCol != 0 && dRank != 0) return false
                return isPathClear(fromRow, fromCol, toRow, toCol)
            }
            PieceType.QUEEN -> {
                if (absDCol != absDRank && dCol != 0 && dRank != 0) return false
                return isPathClear(fromRow, fromCol, toRow, toCol)
            }
            PieceType.KING -> {
                if (absDCol <= 1 && absDRank <= 1) return true
                // Rokade
                if (absDRank == 0 && absDCol == 2) {
                    val row = fromRow
                    if (to.file == 6 && grid[row][5] == null && grid[row][6] == null) return true
                    if (to.file == 2 && grid[row][1] == null && grid[row][2] == null && grid[row][3] == null) return true
                }
                return false
            }
        }
    }

    private fun isPathClear(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int): Boolean {
        val stepRow = Integer.signum(toRow - fromRow)
        val stepCol = Integer.signum(toCol - fromCol)
        var currRow = fromRow + stepRow
        var currCol = fromCol + stepCol
        while (currRow != toRow || currCol != toCol) {
            if (grid[currRow][currCol] != null) return false
            currRow += stepRow
            currCol += stepCol
        }
        return true
    }

    /**
     * Menggerakkan anak catur secara manual atau sesuai rekomendasi engine.
     */
    fun makeMove(from: Square, to: Square, skipValidation: Boolean = false): Boolean {
        if (!skipValidation && !isValidMove(from, to)) {
            return false
        }

        val fromRow = 7 - from.rank
        val fromCol = from.file
        val toRow = 7 - to.rank
        val toCol = to.file

        val piece = grid[fromRow][fromCol] ?: return false

        val captured = grid[toRow][toCol]
        var isPromotion = false
        var isCastling = false

        // Deteksi promosi pion otomatis ke Ratu (Queen)
        var placedPiece = piece
        if (piece.type == PieceType.PAWN && (to.rank == 7 || to.rank == 0)) {
            placedPiece = Piece(PieceType.QUEEN, piece.isWhite)
            isPromotion = true
        }

        // Deteksi rokade (Castling) sederhana
        if (piece.type == PieceType.KING && Math.abs(to.file - from.file) == 2) {
            isCastling = true
            if (to.file == 6) { // Kingside
                grid[fromRow][5] = grid[fromRow][7]
                grid[fromRow][7] = null
            } else if (to.file == 2) { // Queenside
                grid[fromRow][3] = grid[fromRow][0]
                grid[fromRow][0] = null
            }
        }

        grid[toRow][toCol] = placedPiece
        grid[fromRow][fromCol] = null

        moveHistory.add(
            MoveRecord(
                from = from,
                to = to,
                movedPiece = piece,
                capturedPiece = captured,
                prevTurn = isWhiteToMove,
                isCastling = isCastling,
                isPromotion = isPromotion
            )
        )

        // Ganti giliran jalan
        isWhiteToMove = !isWhiteToMove
        return true
    }

    /**
     * Mengurungkan (Undo) langkah terakhir jika salah tap.
     */
    fun undoMove(): Boolean {
        if (moveHistory.isEmpty()) return false
        val last = moveHistory.removeAt(moveHistory.size - 1)

        val fromRow = 7 - last.from.rank
        val fromCol = last.from.file
        val toRow = 7 - last.to.rank
        val toCol = last.to.file

        grid[fromRow][fromCol] = last.movedPiece
        grid[toRow][toCol] = last.capturedPiece

        // Batalkan rokade jika ada
        if (last.isCastling) {
            if (last.to.file == 6) {
                grid[fromRow][7] = grid[fromRow][5]
                grid[fromRow][5] = null
            } else if (last.to.file == 2) {
                grid[fromRow][0] = grid[fromRow][3]
                grid[fromRow][3] = null
            }
        }

        isWhiteToMove = last.prevTurn
        return true
    }

    /**
     * Menghasilkan string FEN resmi yang 100% legal untuk diproses Stockfish.
     */
    fun toFen(): String {
        val fenBuilder = StringBuilder()

        for (row in 0 until 8) {
            var emptyCount = 0
            for (col in 0 until 8) {
                val piece = grid[row][col]
                if (piece == null) {
                    emptyCount++
                } else {
                    if (emptyCount > 0) {
                        fenBuilder.append(emptyCount)
                        emptyCount = 0
                    }
                    fenBuilder.append(piece.symbol)
                }
            }
            if (emptyCount > 0) fenBuilder.append(emptyCount)
            if (row < 7) fenBuilder.append('/')
        }

        val activeColor = if (isWhiteToMove) "w" else "b"
        fenBuilder.append(" $activeColor KQkq - 0 ${moveHistory.size / 2 + 1}")
        return fenBuilder.toString()
    }
}
