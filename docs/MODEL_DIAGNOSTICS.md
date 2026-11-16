# Diagnostik capture Android

Diagnostik ini tidak mengubah preprocessing, label, model, training, threshold
0.75/0.15/0.50, ataupun aturan keputusan. Branch tetap `joey/model-integration`.

## Hasil pemeriksaan kode

- Input: direct ByteBuffer native-endian, FLOAT32 NHWC `[1,224,224,3]`, R lalu G lalu B,
  nilai kanal 0..255 tanpa normalisasi tambahan. Kedua model menerima buffer yang sama.
- JPEG/JPEG_R: BitmapFactory membaca piksel mentah, tanpa menerapkan orientasi EXIF.
  `cropRect` memakai koordinat mentah; kemudian rotasi clockwise dari CameraX tepat sekali,
  center crop persegi, lalu resize bilinear. YUV/RGBA memakai konversi CameraX untuk stride.
- Urutan output mengikuti file label tanpa sorting. Kertas: bukan_uang, kertas_1000,
  kertas_10000, kertas_100000, kertas_2000, kertas_20000, kertas_5000, kertas_50000.
  Logam: bukan_uang, logam_100, logam_1000, logam_200, logam_500.
- Pemetaan nominal berdasarkan label dan jenis uang. `kertas_1000` dan `logam_1000`
  sama-sama tampil Rp1.000; log membedakan keduanya.
- Tidak ditemukan bug preprocessing/pemetaan yang jelas dari pemeriksaan ini. Kesesuaian
  file label dengan urutan kelas saat ekspor training tetap perlu dikonfirmasi dari artefak
  training. Nama label dan kontrak tensor saja tidak membuktikan makna output model.

## Isi log

Hanya `BuildConfig.DEBUG == true` yang menghasilkan log diagnostik. BuildConfig diaktifkan
tanpa perubahan versi dependency. Semua baris memakai tag `CurrenSeeDiag` dan UUID capture
acak yang sama dari request kamera hingga penyelesaian. Konteks worker dilepas di finally.
Tidak ada foto, piksel/buffer input, EXIF, lokasi, identitas pengguna/perangkat, URI, pesan
exception, atau stack trace dalam log diagnostik. Aplikasi tidak menyimpan file log.

- `CAPTURE`: request kamera; penolakan sebelum capture juga mendapat ID dan alasan.
- `IMAGE`: format dan kode format, ukuran asli, rotasi clockwise, cropRect mentah,
  ukuran setelah rotasi, center crop dalam koordinat setelah rotasi, kontrak input model.
  `range=0..255` adalah kontrak preprocessing, bukan statistik piksel yang dicatat.
- `CONTRACT`: dtype/shape aktual saat interpreter pertama kali dibuat.
- `MODEL`: setiap pasangan indeks:label=skor dalam urutan output, top1, top2, jumlah skor,
  dan validitas. Kertas dan logam dicatat terpisah; skor tidak dibandingkan lintas model.
- `DECISION`: ACCEPTED beserta jenis, label, nominal, skor dan alasan, atau REJECTED beserta
  alasan per kandidat. Contoh: `score_below_0.75`, `margin_below_0.15`, `other_top_is_money`,
  `other_score_below_0.50`, `other_top_tied`, `top_is_bukan_uang`, atau output tidak valid.
- `FAILURE`: tahap gagal dan kelas exception/kode kamera saja. Bila gagal sebelum inferensi,
  skor yang belum tersedia tidak dibuat-buat. Bila model kedua gagal, skor model pertama
  yang sudah selesai tetap tercatat.
- `DELIVERY`: apakah callback masih untuk lifecycle/binding yang sama. ACCEPTED dari model
  yang diikuti delivery tidak current berarti hasil dibuang, bukan diteruskan ke UI/TTS/Room.

`RecognitionTrace` hanya menjelaskan output dan hasil yang sudah diputuskan oleh
`RecognitionDecision`. Ia tidak menentukan nominal atau mengubah nilai skor.

## Terminal PowerShell VS Code melalui Wi-Fi

Gunakan APK debug terbaru (`app/build/outputs/apk/debug/app-debug.apk`). Jika HP sudah
terhubung, langsung jalankan `devices` dan Logcat. Pilih serial HP, bukan emulator.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices -l
$device = "IP_HP:PORT_DEBUG" # salin persis kolom pertama dari devices
& $adb -s $device logcat -v threadtime 'CurrenSeeDiag:D' '*:S'
```

Tekan Kenali Uang beberapa kali dengan nominal berbeda. Semua baris satu percobaan memiliki
`capture=` yang sama. Ctrl+C menghentikan pemantauan. Untuk menyimpan hanya log terfilter:

```powershell
& $adb -s $device logcat -v threadtime 'CurrenSeeDiag:D' '*:S' |
    Tee-Object -FilePath .\currensee-diagnostics.log
```

Untuk memasang APK debug terbaru secara manual pada HP yang dipilih:

```powershell
& $adb -s $device install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Jika belum tersambung (Android 11+), sambungkan komputer dan HP ke Wi-Fi yang sama,
aktifkan Developer options > Wireless debugging, pilih Pair device with pairing code:

```powershell
& $adb pair IP_HP:PORT_PAIRING
# Masukkan kode pairing ketika diminta; jangan simpan kode di skrip/log.
& $adb connect IP_HP:PORT_DEBUG
& $adb devices -l
```

Port pairing berasal dari dialog pairing; port debugging berasal dari halaman utama
Wireless debugging dan dapat berbeda/berubah. Tidak perlu pairing ulang bila masih paired.
Acuan: [ADB Wi-Fi](https://developer.android.com/tools/adb#wireless)
dan [filter Logcat](https://developer.android.com/tools/logcat#filteringOutput).

## Membaca hasil

Jika output top1 sendiri selalu kelas 1000 untuk berbagai nominal, masalah terjadi sebelum
pemetaan tampilan: periksa metadata/crop, lalu bandingkan inferensi Android dan runtime
referensi pada input identik melalui pengujian terkontrol terpisah. Log tanpa gambar ini
tidak dapat membuktikan bahwa uang tepat berada di center crop atau bahwa model berkualitas.

Jika top1 uang benar tetapi REJECTED, lihat kegagalan syarat kandidat dan model lainnya.
Jika ACCEPTED memiliki label/nominal benar tetapi layar berbeda, telusuri delivery/UI.
Jangan mengubah threshold berdasarkan beberapa percobaan ini. Smoke test emulator dan
fixture warna menguji integrasi, bukan akurasi uang asli atau kesetaraan model Keras/TFLite.

## File yang disentuh untuk diagnostik

- `recognition/CaptureDiagnostics.kt`: UUID, scope callback worker, guard debug, metadata,
  skor/top2, dan penjelasan keputusan yang hanya bersifat observasional.
- `recognition/MoneyRecognizer.kt`: log kontrak, output setiap model, tahap kegagalan,
  dan hasil keputusan. Preprocessing dan keputusan tetap dipanggil seperti sebelumnya.
- `camera/CameraController.kt`: korelasi ID request/callback, error capture, dan delivery.
- `app/build.gradle.kts`: mengaktifkan pembuatan BuildConfig untuk guard debug.
- `RecognitionTraceTest.kt`: semua label/skor, perbedaan kedua kelas 1000, alasan penolakan,
  skor tidak termutasi, dan payload yang dinonaktifkan tidak dievaluasi pada unit test debug.
- Dokumen ini serta pembaruan nama tag log di `MODEL_INTEGRATION.md`.

Perubahan lokal integrasi/UI yang sudah ada sebelum tugas diagnostik tetap dipertahankan.

## Laporan sesi pengujian otomatis — 31 Oktober 2026

Pisahkan hasil yang dilaporkan pada dua sesi berikut:

| Sesi | Unit test lulus | Tes instrumentasi lulus |
| --- | --- | --- |
| Integrasi sebelum diagnostik | 9 | 18 |
| Setelah penambahan diagnostik | 12 | 11 |

Angka berasal dari sesi berbeda dan tidak dijumlahkan. Laporan ini tidak menyatakan bahwa
semua pengujian sesi integrasi diulang setelah diagnostik.
Rincian di bawah adalah laporan sesi diagnostik sebelumnya. Pembaruan dokumentasi pada
1 November 2026 tidak menjalankan build atau tes ulang.

- `assembleDebug assembleDebugAndroidTest lintDebug testDebugUnitTest assembleRelease`:
  dilaporkan berhasil pada source yang diperiksa dalam sesi diagnostik. 12 unit test lulus;
  lint 0 error, 23 warning yang sama
  tentang versi dependency/resource/template, tanpa perubahan versi.
- 11 tes instrumentasi di emulator lulus: 8 CameraIntegrationTest dan 3 MoneyModelsTest.
  Mencakup lifecycle, penutupan frame, TTS/Room, kedua interpreter asli, RGB/crop/rotasi,
  dan decode JPEG. Setelah perapian pesan diagnostik bukan_uang, tes capture kamera dengan
  kedua model asli diulang pada APK final dan lulus lagi (1 tes).
- Capture final: JPEG 1280x960, rotasi 90, cropRect penuh, center crop setelah rotasi
  `[0,160,960,1120]`, input FLOAT32 `[1,224,224,3]`. Kedua model memilih bukan_uang pada
  gambar emulator. Seluruh metadata, 8/5 skor, top2, keputusan, dan delivery memiliki
  UUID yang sama. Ini bukan pengujian akurasi uang asli.
- Task `testReleaseUnitTest` tidak tersedia dalam konfigurasi proyek. Pemeriksaan bytecode
  hasil kompilasi release memastikan logger tidak memanggil Log/UUID, newId mengembalikan
  null, dan event tidak mengevaluasi payload. Tes runtime APK release tidak dijalankan.
- Hash SHA-256 keempat asset model/label tetap identik dengan sebelum diagnostik.
  ImagePreprocessor, RecognitionDecision, threshold, serta pemetaan nominal tidak diubah.
- Perbandingan Android dan runtime referensi dengan input identik belum dilaporkan dilakukan.
  Pengujian HP fisik dengan uang asli dicatat pada bagian berikut; penyebab ketidakstabilan
  prediksi belum dapat dipastikan.

Artefak lokal sesi otomatis (folder build diabaikan Git):
`build/model-diagnostics/instrumentation.txt`, `final-capture-test.txt`,
`capture-example.log`, dan `release-diagnostics-bytecode.txt`.

## Pengujian eksploratif pada HP fisik — dicatat 1 November 2026

Berdasarkan laporan pengguna, integrasi telah diuji pada HP Android fisik menggunakan
kamera dan uang asli. Dua model, kertas dan logam, dapat menjalankan inferensi di Android.
Hasil 10 capture fisik yang dilaporkan:

| Jumlah capture | Hasil | Uang yang diarahkan |
| --- | --- | --- |
| 2 | Prediksi Rp50.000 benar | Rp50.000 |
| 2 | Salah diprediksi sebagai Rp1.000 | Rp10.000 |
| 6 | Ditolak atau belum dikenali | Sebagian besar Rp50.000; sekitar 1–2 capture menggunakan Rp10.000 |

Komposisi nominal pada enam capture yang ditolak masih merupakan perkiraan. Ini hanya
pengujian eksploratif, **bukan akurasi keseluruhan model**. Sepuluh capture tersebut tidak
mewakili semua nominal atau kondisi penggunaan. Kualitas pengenalan belum stabil dan
penyebabnya belum dipastikan; hasil ini belum memisahkan masalah integrasi Android dari
masalah kualitas model TFLite.

Model **proposal_v2 masih sementara** dan memerlukan pemeriksaan bersama anggota training.
Threshold 0.75/0.15/0.50 dan aturan keputusan tidak diubah berdasarkan percobaan ini.
Evaluasi terstruktur serta perbandingan dengan runtime referensi pada input identik masih
diperlukan. Catatan ini tidak menyertakan informasi pribadi atau log diagnostik mentah.
