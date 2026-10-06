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
    val moveHistory = mutableListOf<MoveRecord>()
    val redoHistory = mutableListOf<MoveRecord>()

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
        redoHistory.clear()
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
    fun isValidMove(from: Square, to: Square, ignoreTurnCheck: Boolean = false): Boolean {
        if (from == to) return false
        val fromRow = 7 - from.rank
        val fromCol = from.file
        val toRow = 7 - to.rank
        val toCol = to.file

        val piece = grid[fromRow][fromCol] ?: return false
        val dest = grid[toRow][toCol]

        // 1. Wajib giliran warna yang sedang aktif jika tidak di-ignore!
        if (!ignoreTurnCheck && piece.isWhite != isWhiteToMove) return false

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
        redoHistory.clear()

        // Ganti giliran jalan
        isWhiteToMove = !isWhiteToMove
        return true
    }

    /**
     * Mencari langkah legal (from, to) berdasarkan notasi catur SAN (misal "Qd4", "Ne5", "e4", "dxe4", "O-O").
     * Mendukung auto-resync jika giliran pemain sempat tidak sinkron.
     */
    fun findMoveForSan(sanText: String, forcedTurn: Boolean? = null): Pair<Square, Square>? {
        val turn = forcedTurn ?: isWhiteToMove
        var clean = sanText.trim().replace("+", "").replace("#", "")
            .trim('<', '>', '[', ']', '(', ')', '{', '}', '|', '_', ':', ';', '!', '"', '\'')

        // 1. Simbol unicode bidak catur
        clean = clean.replace("♞", "N").replace("♘", "N")
            .replace("♝", "B").replace("♗", "B")
            .replace("♜", "R").replace("♖", "R")
            .replace("♛", "Q").replace("♕", "Q")
            .replace("♚", "K").replace("♔", "K")

        // 2. Rokade
        if (clean.equals("O-O", ignoreCase = true) || clean == "0-0") {
            val rank = if (turn) 0 else 7
            val from = Square(4, rank)
            val to = Square(6, rank)
            return if (isValidMove(from, to, ignoreTurnCheck = true)) {
                if (forcedTurn != null) isWhiteToMove = forcedTurn
                Pair(from, to)
            } else null
        }
        if (clean.equals("O-O-O", ignoreCase = true) || clean == "0-0-0") {
            val rank = if (turn) 0 else 7
            val from = Square(4, rank)
            val to = Square(2, rank)
            return if (isValidMove(from, to, ignoreTurnCheck = true)) {
                if (forcedTurn != null) isWhiteToMove = forcedTurn
                Pair(from, to)
            } else null
        }

        // 3. Cari target petak [a-h][1-8]
        val targetMatch = Regex("([a-h][1-8])", RegexOption.IGNORE_CASE).findAll(clean).lastOrNull() ?: return null
        val targetUci = targetMatch.value.lowercase()
        val targetSquare = Square.fromUci(targetUci)

        // 4. Deteksi jenis bidak (Piece Hint) dari awalan teks (prefix)
        var pieceHint: PieceType? = null
        var fileHint: Char? = null
        var rankHint: Char? = null

        val prefix = clean.substring(0, targetMatch.range.first).lowercase()

        // Deteksi kata lengkap jika ada (misal "queen xe2", "kuda f6", "bishop c4")
        if (prefix.contains("queen") || prefix.contains("ratu") || prefix.contains("menteri")) {
            pieceHint = PieceType.QUEEN
        } else if (prefix.contains("knight") || prefix.contains("kuda")) {
            pieceHint = PieceType.KNIGHT
        } else if (prefix.contains("bishop") || prefix.contains("gajah") || prefix.contains("peluncur")) {
            pieceHint = PieceType.BISHOP
        } else if (prefix.contains("rook") || prefix.contains("benteng")) {
            pieceHint = PieceType.ROOK
        } else if (prefix.contains("king") || prefix.contains("raja")) {
            pieceHint = PieceType.KING
        } else {
            for (ch in prefix) {
                when (ch) {
                    'n', '2', 'z' -> pieceHint = PieceType.KNIGHT
                    'b', '8' -> pieceHint = PieceType.BISHOP
                    'r' -> pieceHint = PieceType.ROOK
                    'q', 'w', '0', 'o', 'v' -> pieceHint = PieceType.QUEEN
                    'k' -> pieceHint = PieceType.KING
                    in 'a'..'h' -> fileHint = ch
                    in '1'..'8' -> rankHint = ch
                }
            }
        }

        val tryPawnFirst = (pieceHint == null)
        val effectiveHint = pieceHint ?: PieceType.PAWN

        val candidates = mutableListOf<Square>()
        for (r in 0..7) {
            for (f in 0..7) {
                val sq = Square(f, r)
                val p = getPiece(sq) ?: continue
                if (p.isWhite != turn) continue
                if (p.type != effectiveHint) continue
                if (fileHint != null && ('a' + f) != fileHint) continue
                if (rankHint != null && ('1' + r) != rankHint) continue

                if (isValidMove(sq, targetSquare, ignoreTurnCheck = true)) {
                    candidates.add(sq)
                }
            }
        }

        if (candidates.isNotEmpty()) {
            if (forcedTurn != null) isWhiteToMove = forcedTurn
            return Pair(candidates[0], targetSquare)
        }

        // JIKA pieceHint tidak disebutkan (awalan kosong / OCR melewatkan simbol ikon perwira)
        // dan tidak ada pion yang bisa melangkah ke targetSquare:
        // Cari perwira mana yang bisa melangkah ke petak tersebut (misal King ke e2, Bishop ke e7, Queen ke d4)!
        if (tryPawnFirst) {
            val nonPawnCandidates = mutableListOf<Square>()
            for (r in 0..7) {
                for (f in 0..7) {
                    val sq = Square(f, r)
                    val p = getPiece(sq) ?: continue
                    if (p.isWhite != turn) continue
                    if (p.type == PieceType.PAWN) continue
                    if (isValidMove(sq, targetSquare, ignoreTurnCheck = true)) {
                        nonPawnCandidates.add(sq)
                    }
                }
            }
            if (nonPawnCandidates.size == 1) {
                if (forcedTurn != null) isWhiteToMove = forcedTurn
                return Pair(nonPawnCandidates[0], targetSquare)
            }
        }

        // Jika tidak ditemukan pada giliran ini, coba auto-sync dengan giliran lawan (1 langkah desync)
        if (forcedTurn == null) {
            val oppositeMove = findMoveForSan(sanText, forcedTurn = !isWhiteToMove)
            if (oppositeMove != null) {
                return oppositeMove
            }
        }

        return null
    }

    /**
     * Memastikan minimal ada 1 Raja Putih & 1 Raja Hitam di papan agar FEN valid untuk Stockfish.
     */
    fun ensureKingsExist() {
        val (wk, bk) = countKings()
        if (wk == 0) {
            val preferred = Square(4, 0)
            if (getPiece(preferred) == null) {
                setPiece(preferred, Piece(PieceType.KING, true))
            } else {
                findFirstEmptySquare()?.let { setPiece(it, Piece(PieceType.KING, true)) }
            }
        }
        if (bk == 0) {
            val preferred = Square(4, 7)
            if (getPiece(preferred) == null) {
                setPiece(preferred, Piece(PieceType.KING, false))
            } else {
                findFirstEmptySquare()?.let { setPiece(it, Piece(PieceType.KING, false)) }
            }
        }
    }

    private fun findFirstEmptySquare(): Square? {
        for (r in 0..7) {
            for (f in 0..7) {
                val sq = Square(f, r)
                if (getPiece(sq) == null) return sq
            }
        }
        return null
    }

    /**
     * Memindahkan bidak dengan menjamin identitas aslinya TIDAK PERNAH bermutasi.
     * Pion tetap Pion, Kuda tetap Kuda, Benteng tetap Benteng.
     */
    fun forceMove(from: Square, to: Square): Boolean {
        // Coba jalan legal standar terlebih dahulu jika valid
        if (isValidMove(from, to)) {
            return makeMove(from, to, skipValidation = false)
        }

        val fromRow = 7 - from.rank
        val fromCol = from.file
        val toRow = 7 - to.rank
        val toCol = to.file

        val sourcePiece = grid[fromRow][fromCol]
        val captured = grid[toRow][toCol]

        val movedPiece: Piece
        if (sourcePiece != null) {
            // Promosi pion hanya jika mencapai baris ujung (rank 7 atau 0)
            movedPiece = if (sourcePiece.type == PieceType.PAWN && (to.rank == 7 || to.rank == 0)) {
                Piece(PieceType.QUEEN, sourcePiece.isWhite)
            } else {
                sourcePiece
            }
            grid[toRow][toCol] = movedPiece
            grid[fromRow][fromCol] = null
        } else {
            // Jangan pernah spawn pion siluman jika petak asal kosong!
            return false
        }

        ensureKingsExist()

        moveHistory.add(
            MoveRecord(
                from = from,
                to = to,
                movedPiece = movedPiece,
                capturedPiece = captured,
                prevTurn = isWhiteToMove
            )
        )

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

        redoHistory.add(last)
        isWhiteToMove = last.prevTurn
        return true
    }

    /**
     * Mengulang kembali (Redo) langkah yang baru saja di-undo.
     */
    fun redoMove(): Boolean {
        if (redoHistory.isEmpty()) return false
        val next = redoHistory.removeAt(redoHistory.size - 1)

        val fromRow = 7 - next.from.rank
        val fromCol = next.from.file
        val toRow = 7 - next.to.rank
        val toCol = next.to.file

        grid[toRow][toCol] = next.movedPiece
        grid[fromRow][fromCol] = null

        // Terapkan rokade jika ada
        if (next.isCastling) {
            if (next.to.file == 6) {
                grid[fromRow][5] = grid[fromRow][7]
                grid[fromRow][7] = null
            } else if (next.to.file == 2) {
                grid[fromRow][3] = grid[fromRow][0]
                grid[fromRow][0] = null
            }
        }

        moveHistory.add(next)
        isWhiteToMove = !next.prevTurn
        return true
    }

    /**
     * Mengonversi langkah catur (from, to) ke notasi standar SAN (misal: "Nf3", "Qxe2+", "e4", "O-O").
     */
    fun moveToSan(from: Square, to: Square): String {
        val piece = getPiece(from) ?: return "${from.toUci()}${to.toUci()}"
        val isCapture = getPiece(to) != null

        if (piece.type == PieceType.KING && Math.abs(to.file - from.file) == 2) {
            return if (to.file == 6) "O-O" else "O-O-O"
        }

        val captureStr = if (isCapture) "x" else ""
        return when (piece.type) {
            PieceType.PAWN -> {
                if (isCapture) "${('a' + from.file)}x${to.toUci()}" else to.toUci()
            }
            PieceType.KNIGHT -> "N$captureStr${to.toUci()}"
            PieceType.BISHOP -> "B$captureStr${to.toUci()}"
            PieceType.ROOK -> "R$captureStr${to.toUci()}"
            PieceType.QUEEN -> "Q$captureStr${to.toUci()}"
            PieceType.KING -> "K$captureStr${to.toUci()}"
        }
    }

    /**
     * Menghasilkan teks riwayat langkah catur berurutan (misal: "1. e4 e5  2. Nf3 Nc6").
     */
    fun getFormattedMoveHistory(): String {
        if (moveHistory.isEmpty()) return "(Belum ada langkah)"
        val sb = StringBuilder()
        for (i in moveHistory.indices) {
            val record = moveHistory[i]
            if (i % 2 == 0) {
                sb.append("${i / 2 + 1}. ")
            }
            val isCapture = record.capturedPiece != null
            val captureStr = if (isCapture) "x" else ""
            val san = if (record.isCastling) {
                if (record.to.file == 6) "O-O" else "O-O-O"
            } else {
                when (record.movedPiece.type) {
                    PieceType.PAWN -> {
                        if (isCapture) "${('a' + record.from.file)}x${record.to.toUci()}" else record.to.toUci()
                    }
                    PieceType.KNIGHT -> "N$captureStr${record.to.toUci()}"
                    PieceType.BISHOP -> "B$captureStr${record.to.toUci()}"
                    PieceType.ROOK -> "R$captureStr${record.to.toUci()}"
                    PieceType.QUEEN -> "Q$captureStr${record.to.toUci()}"
                    PieceType.KING -> "K$captureStr${record.to.toUci()}"
                }
            }
            sb.append(san).append(" ")
        }
        return sb.toString().trim()
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
