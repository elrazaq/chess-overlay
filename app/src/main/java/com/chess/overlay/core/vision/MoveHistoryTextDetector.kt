package com.chess.overlay.core.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.Square
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class TextDetectionResult(
    val rawText: String,
    val moveTokens: List<String> = emptyList(),
    val latestSan: String? = null,
    val moveSignature: String? = null,
    val move: Pair<Square, Square>? = null
)

/**
 * Detektor notasi teks langkah catur langsung dari Move History Bar di bawah papan Chess.com.
 * Menggunakan Google ML Kit Text Recognition on-device (offline & ultra-cepat).
 */
class MoveHistoryTextDetector {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Memindai teks bar langkah di bawah papan, mendeteksi notasi langkah terkini,
     * dan mengonversinya ke langkah sah (from, to).
     */
    suspend fun detectLatestMove(
        bitmap: Bitmap,
        bounds: BoardBounds,
        boardState: BoardState,
        sensorBounds: RectF? = null
    ): TextDetectionResult {
        val bmpW = bitmap.width
        val bmpH = bitmap.height

        val cropTop: Int
        val cropHeight: Int

        if (sensorBounds != null) {
            cropTop = sensorBounds.top.toInt().coerceIn(0, bmpH - 1)
            cropHeight = sensorBounds.height().toInt().coerceIn(20, bmpH - cropTop)
        } else {
            val boardBottom = (bounds.top + bounds.size).toInt()
            cropTop = (boardBottom + bounds.squareSize * 0.7f).toInt().coerceIn(0, bmpH - 1)
            cropHeight = (bounds.squareSize * 1.0f).toInt().coerceIn(20, bmpH - cropTop)
        }

        if (cropHeight <= 15) return TextDetectionResult(rawText = "")

        val cropped = try {
            Bitmap.createBitmap(bitmap, 0, cropTop, bmpW, cropHeight)
        } catch (e: Exception) {
            e.printStackTrace()
            return TextDetectionResult(rawText = "")
        }

        // Perbesar (Scale up 2.5x) agar teks notasi catur mencapai ukuran optimal OCR (tinggi huruf ~40px)
        val scaledW = (bmpW * 2.5f).toInt()
        val scaledH = (cropHeight * 2.5f).toInt()
        val scaledBitmap = try {
            Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true)
        } catch (e: Exception) {
            cropped
        }

        return try {
            val image = InputImage.fromBitmap(scaledBitmap, 0)
            val visionText = processImageAsync(image)
            parseVisionTextToResult(visionText, boardState)
        } finally {
            if (scaledBitmap != cropped) scaledBitmap.recycle()
            cropped.recycle()
        }
    }

    private suspend fun processImageAsync(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text)
                }
                .addOnFailureListener {
                    cont.resume("")
                }
        }

    /**
     * Normalisasi karakter khusus dan salah baca OCR catur yang sering terjadi di Chess.com.
     * Mengubah '2f6' -> 'Nf6', 'Wd4'/'wd4' -> 'Qd4', '8c4' -> 'Bc4', 'lxe4' -> 'xe4', dsb.
     */
    fun normalizeToken(token: String): String {
        var s = token.trim().trim('<', '>', '[', ']', '(', ')', '{', '}', '|', '_', ':', ';', '!', '"', '\'')

        // 1. Ganti simbol unicode bidak catur jika ada
        s = s.replace("♞", "N").replace("♘", "N")
            .replace("♝", "B").replace("♗", "B")
            .replace("♜", "R").replace("♖", "R")
            .replace("♛", "Q").replace("♕", "Q")
            .replace("♚", "K").replace("♔", "K")

        // 2. Rokade
        if (s.equals("O-O", ignoreCase = true) || s == "0-0") return "O-O"
        if (s.equals("O-O-O", ignoreCase = true) || s == "0-0-0") return "O-O-O"

        // 3. Normalisasi Ratu (Queen) yang salah dibaca sebagai 'W', 'w', '0', 'O', atau 'q'
        // Contoh: "Wd4" -> "Qd4", "wd4" -> "Qd4", "Wxf7" -> "Qxf7"
        s = s.replace(Regex("^[WwO0Qq]([a-h][1-8])$"), "Q$1")
        s = s.replace(Regex("^[WwO0Qq]x([a-h][1-8])$"), "Qx$1")

        // 4. Normalisasi Kuda (Knight) yang salah dibaca sebagai '2', 'z', 'Z', atau 'n'
        // Contoh: "2f6" -> "Nf6", "2e5" -> "Ne5", "2xf6" -> "Nxf6", "zf6" -> "Nf6"
        s = s.replace(Regex("^[2zZNn]([a-h][1-8])$"), "N$1")
        s = s.replace(Regex("^[2zZNn]x([a-h][1-8])$"), "Nx$1")

        // 5. Normalisasi Gajah (Bishop) yang salah dibaca sebagai '8' atau 'b'
        // Contoh: "8c4" -> "Bc4", "8e7" -> "Be7", "8xd4" -> "Bxd4"
        s = s.replace(Regex("^[8Bb]([a-h][1-8])$"), "B$1")
        s = s.replace(Regex("^[8Bb]x([a-h][1-8])$"), "Bx$1")

        // 6. Normalisasi Benteng (Rook)
        s = s.replace(Regex("^[Rr]([a-h][1-8])$"), "R$1")
        s = s.replace(Regex("^[Rr]x([a-h][1-8])$"), "Rx$1")

        // 7. Normalisasi Raja (King)
        s = s.replace(Regex("^[Kk]([a-h][1-8])$"), "K$1")
        s = s.replace(Regex("^[Kk]x([a-h][1-8])$"), "Kx$1")

        // 8. Normalisasi Pion makan pion/bidak (misal 'lxe4' -> 'xe4', '1xe4' -> 'xe4')
        s = s.replace(Regex("^[1lI!|]x([a-h][1-8])$"), "x$1")

        // 9. Langkah pion biasa (misal 'e4', 'd5')
        if (s.matches(Regex("^[a-h][1-8]$", RegexOption.IGNORE_CASE))) {
            s = s.lowercase()
        }

        return s
    }

    /**
     * Memeriksa apakah token adalah kandidat notasi catur valid (SAN).
     */
    private fun isPotentialChessMove(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token == "O-O" || token == "O-O-O") return true
        // Langkah pion (e4, c5, dxe4, exd5, xe4)
        if (token.matches(Regex("^[a-h][1-8]$"))) return true
        if (token.matches(Regex("^[a-h]?x[a-h][1-8]$"))) return true
        // Langkah bidak perwira (Nf6, Qd4, Bc4, Rd1, Ke2, Qxf7, Nxd4, dsb)
        if (token.matches(Regex("^[NBRQK][a-h]?[1-8]?x?[a-h][1-8]$"))) return true
        return false
    }

    /**
     * Mengurai teks OCR dari Move History Bar menjadi TextDetectionResult yang aman dan deterministik.
     */
    fun parseVisionTextToResult(text: String, boardState: BoardState): TextDetectionResult {
        val cleanRawText = text.replace("\n", " ").trim()
        if (cleanRawText.isBlank()) return TextDetectionResult(rawText = "")

        // 1. Ekstrak kata-kata dan abaikan angka babak catur ("1.", "2.", "4.", dsb)
        val rawWords = cleanRawText.split("\\s+".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.matches(Regex("^\\d+\\.*$")) }

        // 2. Normalisasi setiap kata
        val moveTokens = mutableListOf<String>()
        for (w in rawWords) {
            val norm = normalizeToken(w)
            if (isPotentialChessMove(norm)) {
                moveTokens.add(norm)
            } else {
                // Cari substring yang mengandung langkah catur
                val match = Regex("([NBRQK]?[a-h]?[1-8]?x?[a-h][1-8]|O-O-O|O-O)", RegexOption.IGNORE_CASE).find(norm)
                if (match != null) {
                    val sub = normalizeToken(match.value)
                    if (isPotentialChessMove(sub)) {
                        moveTokens.add(sub)
                    }
                }
            }
        }

        if (moveTokens.isEmpty()) {
            return TextDetectionResult(rawText = cleanRawText)
        }

        // 3. Langkah TERBARU pada move history bar selalu token yang paling kanan!
        val latestSan = moveTokens.last()
        val signature = "${moveTokens.size}:$latestSan"

        // 4. Cari langkah sah untuk token terbaru
        var legalMove = boardState.findMoveForSan(latestSan)

        // Jika tidak valid untuk giliran saat ini, coba cek dengan auto-turn sync
        if (legalMove == null) {
            legalMove = boardState.findMoveForSan(latestSan, forcedTurn = !boardState.isWhiteToMove)
        }

        // Jika masih null dan terdapat token sebelumnya (misal White e4 dan Black Nf6 terdeteksi berurutan)
        if (legalMove == null && moveTokens.size >= 2) {
            val secondLastSan = moveTokens[moveTokens.size - 2]
            legalMove = boardState.findMoveForSan(secondLastSan)
            if (legalMove != null) {
                return TextDetectionResult(
                    rawText = cleanRawText,
                    moveTokens = moveTokens,
                    latestSan = secondLastSan,
                    moveSignature = "${moveTokens.size - 1}:$secondLastSan",
                    move = legalMove
                )
            }
        }

        return TextDetectionResult(
            rawText = cleanRawText,
            moveTokens = moveTokens,
            latestSan = latestSan,
            moveSignature = signature,
            move = legalMove
        )
    }

    fun release() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
