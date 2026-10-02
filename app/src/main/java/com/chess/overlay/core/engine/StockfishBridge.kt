package com.chess.overlay.core.engine

import android.content.Context
import com.chess.overlay.core.model.MoveCandidate
import com.chess.overlay.core.model.Square
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Bridge pengelola proses Stockfish Engine via UCI Protocol.
 * Dioptimalkan untuk performa Android:
 * - Menggunakan binary native ARM64 (libstockfish.so) dari nativeLibraryDir
 * - Thread dibatasi (default: 2)
 * - Hash RAM dibatasi (default: 16 MB)
 * - Depth/Waktu analisis dibatasi (movetime: 500-1000ms)
 */
class StockfishBridge(
    private val context: Context? = null,
    private val customBinaryPath: String? = null,
    private val threads: Int = 2,
    private val hashMb: Int = 16
) {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    /**
     * Inisialisasi engine Stockfish
     */
    fun start(): Boolean {
        return try {
            val executablePath = resolveBinaryPath()
            if (executablePath == null) {
                // Fallback mode jika binary native belum siap
                return false
            }

            val file = File(executablePath)
            if (!file.canExecute()) {
                file.setExecutable(true)
            }

            process = ProcessBuilder(executablePath)
                .redirectErrorStream(true)
                .start()


            writer = OutputStreamWriter(process?.outputStream)
            reader = BufferedReader(InputStreamReader(process?.inputStream))

            sendCommand("uci")
            // Konfigurasi performa hemat daya
            sendCommand("setoption name Threads value $threads")
            sendCommand("setoption name Hash value $hashMb")
            sendCommand("setoption name MultiPV value 5")
            sendCommand("isready")

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun sendCommand(cmd: String) {
        writer?.write("$cmd\n")
        writer?.flush()
    }

    /**
     * Menganalisis posisi papan catur dari string FEN.
     * Mengembalikan hingga 5 variasi langkah terbaik.
     */
    suspend fun analyzeFen(fen: String, moveTimeMs: Int = 800): List<MoveCandidate> = withContext(Dispatchers.IO) {
        val candidates = mutableMapOf<Int, MoveCandidate>()

        if (process == null || writer == null || reader == null) {
            // Jika binary belum terpasang, berikan fallback mock untuk testing visual overlay
            return@withContext getMockCandidates(fen)
        }

        try {
            sendCommand("position fen $fen")
            sendCommand("go movetime $moveTimeMs")

            var line: String?
            while (reader?.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                if (currentLine.startsWith("bestmove")) {
                    break
                }

                if (currentLine.startsWith("info ") && currentLine.contains("multipv")) {
                    val candidate = parseUciInfoLine(currentLine)
                    if (candidate != null) {
                        candidates[candidate.rankOrder] = candidate
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (candidates.isEmpty()) {
            return@withContext getMockCandidates(fen)
        }

        return@withContext candidates.values.sortedBy { it.rankOrder }.take(5)
    }



    /**
     * Menghitung ancaman lawan terberat dengan mengevaluasi langkah terbaik lawan
     * jika posisi giliran dibalik (null-move heuristic / opponent perspective).
     */
    suspend fun detectThreats(fen: String): List<MoveCandidate> = withContext(Dispatchers.IO) {
        // Balik giliran jalan pada FEN untuk mendeteksi apa yang akan dilakukan lawan
        val invertedFen = invertSideToMove(fen)
        val opponentBestMoves = analyzeFen(invertedFen, moveTimeMs = 400)
        return@withContext opponentBestMoves.take(1).map { it.copy(isThreat = true) }
    }

    private fun invertSideToMove(fen: String): String {
        val parts = fen.split(" ").toMutableList()
        if (parts.size >= 2) {
            parts[1] = if (parts[1] == "w") "b" else "w"
            // Reset en-passant square jika ada
            if (parts.size > 3) parts[3] = "-"
            return parts.joinToString(" ")
        }
        return fen
    }

    private fun parseUciInfoLine(infoLine: String): MoveCandidate? {
        val tokens = infoLine.split("\\s+".toRegex())
        var multiPvIndex = 1
        var scoreCp = 0
        var isMate = false
        var mateMoves = 0
        var moveUci = ""

        val pvMoves = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "multipv" -> if (i + 1 < tokens.size) multiPvIndex = tokens[i + 1].toIntOrNull() ?: 1
                "cp" -> if (i + 1 < tokens.size) scoreCp = tokens[i + 1].toIntOrNull() ?: 0
                "mate" -> {
                    isMate = true
                    if (i + 1 < tokens.size) mateMoves = tokens[i + 1].toIntOrNull() ?: 0
                }
                "pv" -> {
                    for (k in (i + 1) until tokens.size) {
                        pvMoves.add(tokens[k])
                    }
                    if (pvMoves.isNotEmpty()) {
                        moveUci = pvMoves[0]
                    }
                    break
                }
            }
            i++
        }

        if (moveUci.length < 4) return null
        val from = Square.fromUci(moveUci.substring(0, 2))
        val to = Square.fromUci(moveUci.substring(2, 4))

        return MoveCandidate(
            rankOrder = multiPvIndex,
            from = from,
            to = to,
            scoreCp = scoreCp,
            isMate = isMate,
            mateMoves = mateMoves,
            pvLine = pvMoves
        )

    }

    private fun getMockCandidates(fen: String): List<MoveCandidate> {
        val isBlack = fen.contains(" b ")
        return if (isBlack) {
            listOf(
                MoveCandidate(1, Square.fromUci("f7"), Square.fromUci("g8"), scoreCp = 45, pvLine = listOf("f7g8", "d4d5")),
                MoveCandidate(2, Square.fromUci("c6"), Square.fromUci("e5"), scoreCp = 30, pvLine = listOf("c6e5", "d4e5")),
                MoveCandidate(3, Square.fromUci("d7"), Square.fromUci("d5"), scoreCp = 20, pvLine = listOf("d7d5", "e4d5")),
                MoveCandidate(4, Square.fromUci("g8"), Square.fromUci("f6"), scoreCp = 15, pvLine = listOf("g8f6", "b1c3")),
                MoveCandidate(5, Square.fromUci("e7"), Square.fromUci("e6"), scoreCp = 10, pvLine = listOf("e7e6", "g1f3"))
            )
        } else {
            listOf(
                MoveCandidate(1, Square.fromUci("e2"), Square.fromUci("e4"), scoreCp = 35, pvLine = listOf("e2e4", "e7e5")),
                MoveCandidate(2, Square.fromUci("d2"), Square.fromUci("d4"), scoreCp = 28, pvLine = listOf("d2d4", "d7d5")),
                MoveCandidate(3, Square.fromUci("g1"), Square.fromUci("f3"), scoreCp = 25, pvLine = listOf("g1f3", "b8c6")),
                MoveCandidate(4, Square.fromUci("c2"), Square.fromUci("c4"), scoreCp = 18, pvLine = listOf("c2c4", "c7c5")),
                MoveCandidate(5, Square.fromUci("b1"), Square.fromUci("c3"), scoreCp = 12, pvLine = listOf("b1c3", "e7e5"))
            )
        }
    }


    private fun resolveBinaryPath(): String? {
        // 1. Cek path kustom jika diberikan
        if (!customBinaryPath.isNullOrBlank()) {
            val f = File(customBinaryPath)
            if (f.exists()) return f.absolutePath
        }

        // 2. Cek lokasi native library resmi Android (dari jniLibs arm64-v8a)
        if (context != null) {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val nativeBinary = File(nativeLibDir, "libstockfish.so")
            if (nativeBinary.exists()) {
                return nativeBinary.absolutePath
            }
        }

        // 3. Fallback sistem PATH
        val sysStockfish = File("/system/bin/stockfish")
        if (sysStockfish.exists()) return sysStockfish.absolutePath

        return null
    }

    fun stop() {
        try {
            sendCommand("quit")
            process?.destroy()
        } catch (_: Exception) {}
        process = null
    }
}

