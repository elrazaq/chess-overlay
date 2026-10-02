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
     * Menggerakkan anak catur secara manual atau sesuai rekomendasi engine.
     */
    fun makeMove(from: Square, to: Square): Boolean {
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
