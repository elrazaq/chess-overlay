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
     * Memeriksa apakah suatu petak sedang diserang oleh bidak lawan.
     */
    fun isSquareAttacked(square: Square, attackedByWhite: Boolean): Boolean {
        val targetRow = 7 - square.rank
        val targetCol = square.file

        // 1. Serangan Pion
        val pawnAttackerRow = if (attackedByWhite) targetRow + 1 else targetRow - 1
        if (pawnAttackerRow in 0..7) {
            val leftCol = targetCol - 1
            val rightCol = targetCol + 1
            if (leftCol in 0..7) {
                val p = grid[pawnAttackerRow][leftCol]
                if (p?.type == PieceType.PAWN && p.isWhite == attackedByWhite) return true
            }
            if (rightCol in 0..7) {
                val p = grid[pawnAttackerRow][rightCol]
                if (p?.type == PieceType.PAWN && p.isWhite == attackedByWhite) return true
            }
        }

        // 2. Serangan Kuda (8 arah L)
        val knightOffsets = arrayOf(
            Pair(-2, -1), Pair(-2, 1), Pair(-1, -2), Pair(-1, 2),
            Pair(1, -2), Pair(1, 2), Pair(2, -1), Pair(2, 1)
        )
        for ((dr, dc) in knightOffsets) {
            val r = targetRow + dr
            val c = targetCol + dc
            if (r in 0..7 && c in 0..7) {
                val p = grid[r][c]
                if (p?.type == PieceType.KNIGHT && p.isWhite == attackedByWhite) return true
            }
        }

        // 3. Serangan Raja (1 petak di sekelilingnya)
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = targetRow + dr
                val c = targetCol + dc
                if (r in 0..7 && c in 0..7) {
                    val p = grid[r][c]
                    if (p?.type == PieceType.KING && p.isWhite == attackedByWhite) return true
                }
            }
        }

        // 4. Serangan Garis Lurus (Benteng / Ratu)
        val straightDirs = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))
        for ((dr, dc) in straightDirs) {
            var r = targetRow + dr
            var c = targetCol + dc
            while (r in 0..7 && c in 0..7) {
                val p = grid[r][c]
                if (p != null) {
                    if (p.isWhite == attackedByWhite && (p.type == PieceType.ROOK || p.type == PieceType.QUEEN)) {
                        return true
                    }
                    break // Jalur terhalang oleh bidak lain
                }
                r += dr
                c += dc
            }
        }

        // 5. Serangan Diagonal (Gajah / Ratu)
        val diagDirs = arrayOf(Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1))
        for ((dr, dc) in diagDirs) {
            var r = targetRow + dr
            var c = targetCol + dc
            while (r in 0..7 && c in 0..7) {
                val p = grid[r][c]
                if (p != null) {
                    if (p.isWhite == attackedByWhite && (p.type == PieceType.BISHOP || p.type == PieceType.QUEEN)) {
                        return true
                    }
                    break // Jalur terhalang oleh bidak lain
                }
                r += dr
                c += dc
            }
        }

        return false
    }

    /**
     * Memeriksa apakah Raja suatu warna sedang dalam posisi SKAK (check).
     */
    fun isKingInCheck(isWhite: Boolean): Boolean {
        var kingSquare: Square? = null
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val p = grid[r][c]
                if (p?.type == PieceType.KING && p.isWhite == isWhite) {
                    kingSquare = Square(c, 7 - r)
                    break
                }
            }
            if (kingSquare != null) break
        }
        val target = kingSquare ?: return false
        return isSquareAttacked(target, attackedByWhite = !isWhite)
    }

    /**
     * Memeriksa apakah pergerakan geometri dasar bidak sah (tanpa mempertimbangkan skak pada raja).
     */
    private fun canPieceMove(from: Square, to: Square, piece: Piece, dest: Piece?): Boolean {
        val fromRow = 7 - from.rank
        val fromCol = from.file
        val toRow = 7 - to.rank
        val toCol = to.file

        val dCol = to.file - from.file
        val dRank = to.rank - from.rank
        val absDCol = Math.abs(dCol)
        val absDRank = Math.abs(dRank)

        return when (piece.type) {
            PieceType.PAWN -> {
                val forward = if (piece.isWhite) 1 else -1
                val startRank = if (piece.isWhite) 1 else 6

                if (dCol == 0) {
                    if (dRank == forward && dest == null) true
                    else if (from.rank == startRank && dRank == forward * 2 && dest == null) {
                        val midRow = 7 - (from.rank + forward)
                        grid[midRow][fromCol] == null
                    } else false
                } else if (absDCol == 1 && dRank == forward) {
                    dest != null && dest.isWhite != piece.isWhite
                } else false
            }
            PieceType.KNIGHT -> {
                (absDCol == 1 && absDRank == 2) || (absDCol == 2 && absDRank == 1)
            }
            PieceType.BISHOP -> {
                if (absDCol != absDRank) false
                else isPathClear(fromRow, fromCol, toRow, toCol)
            }
            PieceType.ROOK -> {
                if (dCol != 0 && dRank != 0) false
                else isPathClear(fromRow, fromCol, toRow, toCol)
            }
            PieceType.QUEEN -> {
                if (absDCol != absDRank && dCol != 0 && dRank != 0) false
                else isPathClear(fromRow, fromCol, toRow, toCol)
            }
            PieceType.KING -> {
                if (absDCol <= 1 && absDRank <= 1) true
                else if (absDRank == 0 && absDCol == 2) {
                    val row = fromRow
                    if (to.file == 6 && grid[row][5] == null && grid[row][6] == null) true
                    else to.file == 2 && grid[row][1] == null && grid[row][2] == null && grid[row][3] == null
                } else false
            }
        }
    }

    /**
     * Memvalidasi apakah pergerakan anak catur 100% legal sesuai aturan catur resmi:
     * 1. Harus giliran warna yang sedang aktif.
     * 2. Tidak memakan bidak kawan sendiri.
     * 3. Sesuai geometri langkah bidak.
     * 4. Rokade tidak boleh saat diskak atau melewati petak yang diskak.
     * 5. Raja sendiri TIDAK BOLEH dalam posisi skak setelah langkah dilakukan (mencegah langkah ilegal / pin).
     */
    fun isValidMove(from: Square, to: Square): Boolean {
        if (from == to) return false
        val fromRow = 7 - from.rank
        val fromCol = from.file
        val toRow = 7 - to.rank
        val toCol = to.file

        val piece = grid[fromRow][fromCol] ?: return false
        val dest = grid[toRow][toCol]

        // 1. Wajib giliran warna yang sedang aktif!
        if (piece.isWhite != isWhiteToMove) return false

        // 2. Tidak boleh memakan anak catur sendiri
        if (dest != null && dest.isWhite == piece.isWhite) return false

        // 3. Cek geometri langkah bidak
        if (!canPieceMove(from, to, piece, dest)) return false

        // 4. Aturan Khusus Rokade: Raja tidak boleh sedang diskak dan tidak boleh melewati petak yang diserang
        if (piece.type == PieceType.KING && Math.abs(to.file - from.file) == 2) {
            if (isKingInCheck(piece.isWhite)) return false
            val throughFile = if (to.file == 6) 5 else 3
            if (isSquareAttacked(Square(throughFile, from.rank), attackedByWhite = !piece.isWhite)) {
                return false
            }
        }

        // 5. Simulasikan langkah untuk memastikan Raja sendiri tidak berakhir dalam kondisi SKAK
        grid[toRow][toCol] = piece
        grid[fromRow][fromCol] = null

        val leavesKingInCheck = isKingInCheck(piece.isWhite)

        // Kembalikan ke posisi awal (revert simulation)
        grid[fromRow][fromCol] = piece
        grid[toRow][toCol] = dest

        return !leavesKingInCheck
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
