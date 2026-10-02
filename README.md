# Chess Vision Overlay (Android)

Aplikasi Android native yang dirancang khusus untuk menganalisis papan catur langsung dari layar perangkat secara **ringan dan hemat daya**.

## 🚀 Strategi Desain "Ultra-Lightweight"

Untuk memastikan aplikasi **tidak memberatkan kinerja ponsel Android** (tidak lag, hemat baterai, dan tidak panas):

1. **On-Demand Snapshot vs Continuous Streaming**:
   * *Streaming video 60 FPS* konvensional sangat membebani GPU/CPU dan menghabiskan baterai dalam hitungan menit.
   * Aplikasi ini menggunakan pendekatan **On-Demand Snapshot**: Pengambilan layar hanya dieksekusi selama **1 frame** saat tombol floating bubble (`⚡ SCAN`) diklik oleh pemain.
2. **Stockfish Resource Capping**:
   * Jumlah thread dibatasi: **1–2 Thread** (mencegah CPU throttling).
   * Hash memory dibatasi: **16 MB – 32 MB** (menghemat RAM Android).
   * Parameter waktu analisis: **500–800 ms per evaluasi** (cepat, responsif, dan dingin).
3. **Hardware-Accelerated Canvas Overlay**:
   * Menggunakan Canvas 2D Android standar (`WindowManager` dengan flag `FLAG_NOT_TOUCHABLE`), bukan Jetpack Compose berat, sehingga overhead memori mendekati **0 MB**.
   * Sentuhan jari pemain menembus overlay transparan langsung ke aplikasi catur di bawahnya (Chess.com / Lichess).

---

## 🎨 Visualisasi Panah & Ancaman

* **Panah Hijau Tebal (#1)**: Langkah terbaik (*Best Move*).
* **Panah Biru (#2)**: Alternatif langkah terbaik kedua.
* **Panah Ungu (#3)**: Alternatif langkah ketiga.
* **Panah Kuning (#4)**: Alternatif langkah keempat.
* **Panah Pink (#5)**: Alternatif langkah kelima.
* **Panah Merah Menyala + Label `⚠️ Ancaman`**: Ancaman terberat yang sedang disiapkan oleh lawan jika kita lengah (*Threat analysis*).

---

## 📁 Struktur Direktori Project

```
d:/chess/
├── app/
│   ├── build.gradle.kts           # Konfigurasi modul & ABI ARM64
│   ├── proguard-rules.pro         # Optimasi ukuran APK
│   └── src/main/
│       ├── AndroidManifest.xml    # Izin Overlay & MediaProjection
│       ├── jniLibs/
│       │   └── arm64-v8a/
│       │       └── libstockfish.so# Official Stockfish ARM64 Universal Binary
│       ├── java/com/chess/overlay/
│       │   ├── core/
│       │   │   ├── engine/        # StockfishBridge (UCI protocol, MultiPV 5)
│       │   │   ├── model/         # ChessModels (Square, Move, BoardBounds)
│       │   │   ├── overlay/       # ArrowOverlayView (Canvas rendering)
│       │   │   └── vision/        # BoardDetector & PieceClassifier
│       │   ├── service/           # ChessOverlayService & ScreenCaptureHelper
│       │   └── ui/                # MainActivity (Permission onboarding)
│       └── res/                   # Layout, Drawable, Theme, Color palette
├── build.gradle.kts               # Root gradle script
├── settings.gradle.kts            # Project settings
└── gradle.properties              # JVM args tuning
```


---

## 🛠️ Cara Membuka & Build di Android Studio

1. Buka folder `d:\chess` menggunakan **Android Studio** (Hedgehog / Iguana atau yang lebih baru).
2. Biarkan Gradle melakukan sinkronisasi otomatis (*Sync Project with Gradle Files*).
3. Sambungkan ponsel Android Anda via USB debugging (atau gunakan Emulator).
4. Klik **Run (`Shift + F10`)**.
5. Buka aplikasi di ponsel:
   * Berikan izin **Draw over other apps** (Overlay).
   * Berikan izin **Perekaman Layar** (Media Projection).
   * Ketuk tombol **Aktifkan Overlay**. Bubble kecil `⚡ SCAN` akan muncul di layar.
6. Buka game catur Anda (misal Chess.com / Lichess), lalu ketuk bubble untuk melihat panah kalkulasi!
