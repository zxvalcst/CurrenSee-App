# Integrasi model CurrenSee

Model **proposal_v2** dipakai sementara, menunggu konfirmasi versi final anggota training.
File model dan label di `app/src/main/assets/models/` tidak diubah oleh integrasi ini.

## Runtime dan kontrak

Runtime tunggal: `com.google.ai.edge.litert:litert:1.4.2`, Interpreter API CPU,
dua thread CPU dengan kernel bawaan (`setUseXNNPACK(false)`). Tidak memakai GPU, NNAPI, Play Services, atau dependency
TensorFlow Lite lama. Versi ini dipilih untuk mempertahankan toolchain proyek dan memakai
Interpreter yang sederhana untuk satu capture. Acuan: [LiteRT Android](https://ai.google.dev/edge/litert/android)
dan [panduan migrasi](https://developers.google.com/edge/litert/migration).
Persiapan alokasi XNNPACK gagal saat pengujian asset di emulator; kernel CPU bawaan
dipakai untuk kompatibilitas. Ini tidak menjelaskan penyebab selisih akurasi evaluasi training.

| Model | Input | Output | Label |
| --- | --- | --- | --- |
| `model_kertas_v2_int8.tflite` | FLOAT32 `[1,224,224,3]` | FLOAT32 `[1,8]` | `labels_kertas.txt` |
| `model_logam_v2_int8.tflite` | FLOAT32 `[1,224,224,3]` | FLOAT32 `[1,5]` | `labels_logam.txt` |

Nama `int8` tidak menentukan dtype antarmuka. Saat pemuatan, runtime memeriksa satu input,
satu output, dtype, shape, serta jumlah dan urutan label. Label dibaca dari asset tanpa
diurutkan ulang. `ModelLabels` menyatakan kontrak urutan yang diharapkan; `Denomination`
memetakan setiap label ke nominal dan ucapan Indonesia. Kontrak yang berbeda menghasilkan
`Failure`. Diagnostik debug memakai tag `CurrenSeeDiag`; pesan exception tidak dicatat.

Interpreter diinisialisasi secara lazy di worker kamera dan digunakan ulang selama layar
hidup. Akses run/close terserialisasi. Kedua interpreter menerima ByteBuffer input yang sama,
dengan output terpisah. Asset dibaca melalui stream ke direct buffer sehingga tidak perlu
perubahan konfigurasi kompresi APK.

## Preprocessing

1. Periksa format ImageProxy. JPEG/JPEG_R didekode melalui BitmapFactory ke base image SDR
   sRGB ARGB_8888. YUV_420_888/RGBA_8888 memakai konversi CameraX yang menangani stride.
   Format lain atau decode gagal menghasilkan kegagalan teknis.
2. Terapkan `cropRect` pada koordinat piksel mentah, lalu rotasi searah jarum jam sesuai
   `imageInfo.rotationDegrees` (0/90/180/270), tepat sekali. BitmapFactory tidak menerapkan
   rotasi EXIF; tidak ada rotasi EXIF tambahan.
3. Center crop 1:1, mempertahankan proporsi, lalu resize bilinear ke 224×224.
4. Tulis R, G, B berurutan NHWC ke direct ByteBuffer native-endian FLOAT32, **0–255**.
   Tidak dibagi 255 dan tidak dinormalisasi ke −1..1, karena MobileNetV3Small dilatih dengan
   `include_preprocessing=True`. Tidak ada augmentasi inferensi.

Bitmap sementara dilepas setelah buffer terbentuk. Tidak menyimpan foto atau mengunggahnya.
ImageProxy hanya berlaku selama callback sinkron; **CameraController** menutupnya di
`finally`, tepat sekali. Classifier/preprocessor tidak menutup frame tersebut.

## Aturan keputusan sementara

Konstanta berada di `RecognitionDecision`:

- `MIN_SCORE = 0.75`: kelas tertinggi kandidat harus kelas uang dengan skor minimal ini.
- `MIN_MARGIN = 0.15`: selisih skor tertinggi dan kedua pada model kandidat minimal ini.
- `MIN_OTHER_NOT_MONEY = 0.50`: model lain harus memilih `bukan_uang` dengan skor minimal ini.
- Tolak tie pada kelas tertinggi model lain, konflik dua model uang, atau kedua model bukan uang.
- Terima hanya bila tepat satu kandidat memenuhi seluruh syarat. Tidak membandingkan skor
  lintas model untuk memilih pemenang.

Output harus sesuai jumlah label, finite, berada di 0..1, dan jumlah softmax mendekati 1
(toleransi numerik 0.01). Output tidak valid/meragukan menghasilkan `Unrecognized`, bukan
nominal. Error runtime/kontrak/decode menghasilkan `Failure` yang berbeda.

Threshold **belum divalidasi**. Tidak diubah berdasarkan gambar emulator atau test set.
Dengan output softmax ternormalisasi dan skor kandidat ≥0.75, syarat margin 0.15 saat ini
redundan, tetapi tetap diperiksa sesuai spesifikasi. Kalibrasi berikutnya memerlukan data
validasi terpisah. Skor softmax bukan akurasi model atau kepastian bahwa nominal benar.

## Alur UI, TTS, Room, dan lifecycle

Tombol memeriksa izin/kesiapan kamera, membersihkan hasil lama yang boleh diulang, menghentikan
ucapan lama, lalu menampilkan Capturing. Setelah frame tiba, status berubah menjadi Processing.
Worker menjalankan kedua model dan aturan keputusan. Completion kembali ke main thread:

- `Recognized`: tampilkan nominal, ucapkan teks Indonesia, simpan nominal dan **skor output
  yang diterima** melalui DAO Room yang sudah ada. Schema tetap versi 1.
- `Unrecognized`: tampilkan “Uang belum dikenali”; tidak ada TTS nominal atau insert Room.
- `Failure`: tampilkan kegagalan pengenalan; tidak ada TTS nominal atau insert Room.
- Error capture tetap memiliki pesan tersendiri. Semua jalur melepaskan in-flight capture.
- Error penyimpanan dilaporkan terpisah; hasil Recognized dan tombol ulangi tetap tersedia.

`CaptureStatus.Captured` hanya berarti frame telah dikonsumsi, bukan jaminan uang dikenali.
Tidak ada nominal/confidence placeholder pada alur runtime. Contoh nominal hanya dipakai
oleh Preview, pemetaan label, dan fixture pengujian.

Tombol pengenalan nonaktif selama capture/inferensi. Tombol ulangi hanya aktif untuk hasil
terakhir Recognized saat TTS tersedia. UI dan efek samping hanya menerima completion dari
request dan lifecycle yang masih sama/resumed. Pause atau retry kamera membatalkan penerimaan
hasil tertunda dan membersihkan hasil ulangi. Hasil yang selesai setelah pause/resume atau disposal tidak
menampilkan nominal, berbicara, atau memulai insert Room. Insert yang telah diterima ketika
layar aktif berjalan terpisah dari inferensi; error dari insert lama tidak mengubah request baru.

Dispose menunggu worker/frame yang masih berjalan sebelum menutup interpreter pada worker.
Ukuran teks/tombol, tema putih–biru, Satoshi, scroll, semantics, dan live region dipertahankan.

## Keterbatasan model v2

Ringkasan evaluasi training yang diberikan anggota kelompok:

| Model | Akurasi Keras | Akurasi TFLite |
| --- | --- | --- |
| Kertas | 88.9% | 66.7% |
| Logam | 94.7% | 80.1% |

Angka ini **bukan hasil pengujian Android**. Classification report dalam ZIP berasal dari
evaluasi Keras. Penyebab selisih akurasi belum diketahui. Pengujian runtime hanya membuktikan
bahwa model bisa dimuat/dijalankan, bukan membuktikan akurasi atau threshold cocok untuk pengguna.

## Pengujian dan penggantian model di masa depan

Jalankan dari root dengan JDK Android Studio dan Android SDK 36:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Unit test memeriksa file label asli, pemetaan seluruh nominal/ucapan, threshold, kandidat,
konflik, margin, dan output invalid. Instrumentasi memeriksa kedua asset asli dan inferensi
berulang dengan input yang sama; rotasi/crop/RGB/JPEG; kamera nyata; TTS tiruan dan Room
in-memory; kegagalan, request lama, cleanup, serta UI 320×568dp/font 2×.

Tes model menulis `model-smoke-input.f32` (NHWC float32 native-endian) dan
`model-smoke-output.json` ke additional test output Gradle. Pada emulator x86_64 input
little-endian. Gunakan file tensor **yang sama** untuk perbandingan Python/Android agar
perbedaan decoder/resize tidak mengaburkan hasil. Pola RGB sintetis ini bukan foto uang.

Untuk mengganti model nanti: konfirmasikan versi final dengan anggota training, simpan
model dan label sebagai satu pasangan dengan urutan kelas yang benar, lalu sesuaikan kontrak
shape/dtype/preprocessing dan pemetaan hanya bila training berubah. Jalankan ulang tes model,
paritas Python/Android, serta evaluasi data validasi/test terpisah. Jangan menamai ulang atau
mengganti asset secara diam-diam ketika kontrak gagal.

Pengujian manual yang masih diperlukan: uang kertas/logam dan bukan uang dengan foto nyata
yang sah; variasi orientasi, cahaya, jarak dan latar; perangkat fisik/versi Android berbeda;
JPEG_R dari kamera yang mendukung HDR; TalkBack; kualitas audio TTS; serta paritas Python.
Uji gagal lalu capture ulang, pause/rotasi saat inferensi, izin dicabut, dan kegagalan Room.

## File yang berubah

Path di bawah relatif terhadap root repository. Keempat asset model/label adalah file awal
pengguna yang tetap identik, bukan model yang dibuat atau diganti oleh integrasi.

| Status | File |
| --- | --- |
| Baru | `app/src/main/java/com/currensee/app/recognition/RecognitionResult.kt`, `RecognitionDecision.kt`, `ImagePreprocessor.kt`, `MoneyRecognizer.kt` |
| Baru | `app/src/test/java/com/currensee/app/recognition/RecognitionDecisionTest.kt` |
| Baru | `app/src/androidTest/java/com/currensee/app/recognition/MoneyModelsTest.kt` |
| Baru | `docs/MODEL_INTEGRATION.md` |
| Diubah | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| Diubah | `app/src/main/java/com/currensee/app/camera/CameraController.kt`, `CapturedImageHandler.kt` |
| Diubah | `app/src/main/java/com/currensee/app/speech/SpeechController.kt` |
| Diubah | `app/src/main/java/com/currensee/app/ui/home/CurrenSeeApp.kt`, `HomeUiState.kt`, `HomeScreen.kt` |
| Diubah | `app/src/main/res/values/strings.xml` |
| Diubah | `app/src/androidTest/java/com/currensee/app/CameraIntegrationTest.kt`, `HomeScreenTest.kt` |
| Diubah | `README.md` |
