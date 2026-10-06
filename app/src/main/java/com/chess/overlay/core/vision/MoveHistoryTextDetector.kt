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
    val detectedSan: String? = null,
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
            val (san, move) = parseVisionTextToMove(visionText, boardState)
            TextDetectionResult(rawText = visionText.replace("\n", " ").trim(), detectedSan = san, move = move)
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
     * Normalisasi karakter khusus dan salah baca OCR catur yang sering terjadi.
     */
    private fun normalizeToken(token: String): String {
        var s = token.trim().trim('<', '>', '[', ']', '(', ')', '{', '}', '|', '_', ':')
        // Ganti simbol unicode bidak catur
        s = s.replace("♞", "N").replace("♘", "N")
            .replace("♝", "B").replace("♗", "B")
            .replace("♜", "R").replace("♖", "R")
            .replace("♛", "Q").replace("♕", "Q")
            .replace("♚", "K").replace("♔", "K")

        // Salah baca OCR umum:
        // '2' atau 'z' di depan koordinat petak (misal '2e5' -> 'Ne5')
        if (s.matches(Regex("^[2zZ][a-h][1-8]$"))) {
            s = "N" + s.substring(1)
        }
        // 'O' atau '0' di depan koordinat (misal 'Od4' -> 'Qd4')
        if (s.matches(Regex("^[0O][a-h][1-8]$"))) {
            s = "Q" + s.substring(1)
        }
        // '1', 'l', atau 'I' di depan 'x' (misal 'lxe4' -> 'xe4')
        if (s.matches(Regex("^[1lI]x[a-h][1-8]$"))) {
            s = s.substring(1)
        }
        return s
    }

    /**
     * Mengurai string yang dibaca OCR menjadi notasi SAN dan langkah sah catur.
     * Contoh teks OCR: "4. Ne5 Qd4" atau "< dxe4 4. 2e5 [d4] >"
     */
    fun parseVisionTextToMove(text: String, boardState: BoardState): Pair<String?, Pair<Square, Square>?> {
        if (text.isBlank()) return Pair(null, null)

        // Pisahkan token dan bersihkan
        val rawTokens = text.split("\\s+".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith(".") && !it.matches(Regex("^\\d+\\.$")) }

        val tokens = rawTokens.map { normalizeToken(it) }.filter { it.isNotEmpty() }

        // 1. Cari token dari yang paling kanan (langkah terbaru selalu di sebelah kanan)
        for (i in tokens.indices.reversed()) {
            val token = tokens[i]
            val move = boardState.findMoveForSan(token)
            if (move != null) {
                return Pair(token, move)
            }
        }

        // 2. Jika tidak ada token utuh yang cocok, cari regex petak [a-h][1-8] dari kanan
        val targetMatches = Regex("([a-h][1-8])", RegexOption.IGNORE_CASE).findAll(text).toList()
        for (match in targetMatches.reversed()) {
            val uci = match.value.lowercase()
            val move = boardState.findMoveForSan(uci)
            if (move != null) {
                return Pair(uci, move)
            }
        }

        return Pair(null, null)
    }

    fun release() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
