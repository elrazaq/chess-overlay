package com.chess.overlay.core.vision

import android.graphics.Bitmap
import com.chess.overlay.core.model.BoardBounds
import com.chess.overlay.core.model.BoardState
import com.chess.overlay.core.model.Square
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
        boardState: BoardState
    ): Pair<Square, Square>? {
        val bmpW = bitmap.width
        val bmpH = bitmap.height

        // Strip Move History Bar berada persis di bawah papan
        val boardBottom = (bounds.top + bounds.size).toInt()
        val barTop = (boardBottom + bounds.squareSize * 0.4f).toInt().coerceIn(0, bmpH - 1)
        val barBottom = (boardBottom + bounds.squareSize * 2.2f).toInt().coerceIn(0, bmpH - 1)
        val barHeight = barBottom - barTop
        if (barHeight <= 15) return null

        val cropped = try {
            Bitmap.createBitmap(bitmap, 0, barTop, bmpW, barHeight)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        // Perbesar (Scale up 2.5x) agar teks notasi catur mencapai ukuran optimal OCR (tinggi huruf ~40px)
        val scaledW = (bmpW * 2.5f).toInt()
        val scaledH = (barHeight * 2.5f).toInt()
        val scaledBitmap = try {
            Bitmap.createScaledBitmap(cropped, scaledW, scaledH, true)
        } catch (e: Exception) {
            cropped
        }

        return try {
            val image = InputImage.fromBitmap(scaledBitmap, 0)
            val visionText = processImageAsync(image)
            parseVisionTextToMove(visionText, boardState)
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
     * Mengurai string yang dibaca OCR menjadi langkah catur sah.
     * Contoh teks OCR: "4. Ne5 Qd4" atau "< dxe4 4. e5 d4 >"
     */
    fun parseVisionTextToMove(text: String, boardState: BoardState): Pair<Square, Square>? {
        if (text.isBlank()) return null

        // Bersihkan token
        val tokens = text.split("\\s+".toRegex())
            .map { it.trim().trim('<', '>', '[', ']', '(', ')') }
            .filter { it.isNotEmpty() && !it.endsWith(".") } // Hapus angka babak "1.", "4."

        // Cari token dari yang paling kanan (langkah terbaru selalu di sebelah kanan)
        for (i in tokens.indices.reversed()) {
            val token = tokens[i]
            val move = boardState.findMoveForSan(token)
            if (move != null) {
                return move
            }
        }

        // Jika tidak ada token utuh yang cocok, cari regex petak [a-h][1-8] dari kanan
        val targetMatches = Regex("([a-h][1-8])", RegexOption.IGNORE_CASE).findAll(text).toList()
        for (match in targetMatches.reversed()) {
            val uci = match.value.lowercase()
            val move = boardState.findMoveForSan(uci)
            if (move != null) {
                return move
            }
        }

        return null
    }

    fun release() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
