# CurrenSee — pengenalan uang dari kamera

Aplikasi Android Kotlin/Jetpack Compose, package `com.currensee.app`, minimum API 26.
Desain putih–biru dan font Satoshi lokal dipertahankan. CameraX 1.5.3 memakai versi
yang sama untuk core, camera2, lifecycle, dan view. Gradle serta versi dependency
lain mengikuti proyek yang sudah ada.
Inferensi lokal memakai LiteRT 1.4.2 CPU dengan model kertas/logam proposal_v2 sementara.

## Build dan menjalankan

1. Buka folder proyek di Android Studio, tunggu Gradle Sync, dan pastikan SDK 36 tersedia.
2. Gunakan Gradle JDK bawaan Android Studio atau JDK 17+ yang cocok dengan AGP proyek.
3. Pilih emulator/perangkat API 26+ dengan kamera belakang, lalu Run konfigurasi `app`.
4. Tekan **Aktifkan kamera** dan berikan izin kamera. Izin tidak diminta otomatis.

Perintah dari root proyek di Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Tes `CameraIntegrationTest` memerlukan kamera belakang yang berfungsi dan API 28+.
Pada emulator, aktifkan emulasi kamera belakang melalui konfigurasi AVD.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Yang sudah diimplementasikan

- Satu layar utama, safe insets, scroll, font sistem besar, dan semantics status.
- Izin CAMERA melalui Activity Result API setelah pengguna menekan tombol.
- Penolakan izin, arahan pengaturan saat tidak dapat diminta ulang, dan pemeriksaan
  ulang izin saat resume. Status penolakan hanya petunjuk UI; grant OS selalu diperiksa.
- Preview kamera belakang CameraX yang terikat lifecycle, dengan crop proporsional.
- Satu capture di memori setiap saat, status sukses/gagal, retry kamera, serta cleanup.
- Tidak meminta mikrofon, lokasi, atau penyimpanan. Tidak mengunggah gambar atau
  menyimpannya ke galeri maupun file foto.
- Preview Compose memakai placeholder tanpa membuka kamera atau meminta izin.
  Buka `HomeScreen.kt` → Split/Design → Build & Refresh; tersedia state awal,
  memproses, belum dikenali, nominal contoh, layar kecil, dan font 2×.

## Integrasi model, TTS, dan riwayat

`recognition/MoneyRecognizer.kt` memuat kedua model dari `assets/models/` secara lazy
pada worker kamera. `ImagePreprocessor` menangani format frame, cropRect, rotasi,
center crop dan RGB FLOAT32 0–255 berukuran 224×224. Tidak ada normalisasi /255 tambahan.
`RecognitionDecision` mengevaluasi kedua output dengan threshold sementara 0.75/0.15/0.50.
Threshold belum divalidasi untuk akurasi penggunaan nyata.

Hanya hasil `Recognized` yang ditampilkan sebagai nominal, dibacakan TTS Indonesia,
dan disimpan ke Room dengan skor model yang diterima. `Unrecognized`, kegagalan capture,
kegagalan model, dan kegagalan penyimpanan memiliki penanganan terpisah. Tidak ada
placeholder prediksi pada alur runtime. Model v2 menunggu konfirmasi versi final training.

CameraController tetap memiliki ImageProxy dan menutupnya tepat sekali di `finally`.
Inferensi sinkron berlangsung pada worker, satu capture per tombol. Hasil tertunda setelah
pause/dispose diabaikan; interpreter ditutup pada worker setelah frame selesai dipakai.
UI aksesibel putih–biru/Satoshi, scroll, dan font sistem 2× dipertahankan.

Lihat [kontrak, preprocessing, aturan keputusan, keterbatasan dan pengujian model](docs/MODEL_INTEGRATION.md).
Lisensi Satoshi: `app/src/main/assets/licenses/Satoshi-FFL.txt`.

## Laporan pengujian otomatis — 31 Oktober 2026

Hasil berikut berasal dari dua sesi pengujian yang berbeda:

| Sesi | Unit test lulus | Tes instrumentasi lulus |
| --- | --- | --- |
| Integrasi sebelum diagnostik | 9 | 18 |
| Setelah penambahan diagnostik | 12 | 11 |

Angka dicatat terpisah sesuai sesi masing-masing dan tidak dijumlahkan. Laporan ini tidak
menyatakan bahwa seluruh pengujian sesi pertama telah diulang setelah diagnostik.
Pembaruan dokumentasi pada 1 November 2026 tidak menjalankan build atau tes ulang.

- Pada sesi integrasi, `assembleDebug`, `lintDebug`, `testDebugUnitTest`, dan
  `connectedDebugAndroidTest` dilaporkan berhasil pada emulator Pixel 9 / Android 16.
- Pada sesi diagnostik, build debug/release, lint, dan unit test dilaporkan berhasil,
  beserta 11 tes instrumentasi. Rinciannya ada di [laporan diagnostik](docs/MODEL_DIAGNOSTICS.md).
- Lint pada laporan kedua sesi: 0 error, 23 warning tentang versi dependency/AGP/Kotlin
  dan resource/template lama.
- Kedua asset asli berhasil divalidasi dan menjalankan inferensi CPU dengan input FLOAT32
  yang sama; output 8/5 kelas finite, termasuk pengujian ulang interpreter yang sama.
  Frame kamera emulator juga berhasil diproses. Ini bukan pengujian akurasi nominal.
- Tes pada sesi integrasi mencakup crop/rotasi/RGB/JPEG, recovery setelah gagal, hasil tertunda saat lifecycle
  berubah, cleanup, skor/nominal Room, TTS tiruan, kegagalan insert Room, serta UI 320×568dp/font 2×.
- Empat asset model/label tetap identik berdasarkan SHA-256 awal. Tidak ada perubahan schema Room.
- Input `model-smoke-input.f32` dan `model-smoke-output.json` tersedia di additional test output
  Gradle (`app/build/outputs/connected_android_test_additional_output/`).

## Pengujian eksploratif pada HP fisik — dicatat 1 November 2026

Integrasi telah diuji pada HP Android fisik menggunakan kamera dan uang asli, berdasarkan
laporan pengguna. Dua model, kertas dan logam, dapat menjalankan inferensi di Android.
Rincian 10 capture fisik yang dilaporkan:

| Jumlah capture | Hasil | Uang yang diarahkan |
| --- | --- | --- |
| 2 | Prediksi Rp50.000 benar | Rp50.000 |
| 2 | Salah diprediksi sebagai Rp1.000 | Rp10.000 |
| 6 | Ditolak atau belum dikenali | Sebagian besar Rp50.000; sekitar 1–2 capture menggunakan Rp10.000 |

Komposisi nominal pada enam capture yang ditolak masih merupakan perkiraan.
Angka ini hanya pengujian eksploratif, **bukan akurasi keseluruhan model**, dan tidak mewakili
semua nominal atau kondisi penggunaan. Kualitas pengenalan belum stabil; penyebabnya belum
dipastikan. Belum dapat disimpulkan apakah masalah berasal dari integrasi Android atau kualitas model.

Model **proposal_v2 masih sementara** dan memerlukan pemeriksaan bersama anggota training.
Threshold 0.75/0.15/0.50 tetap sementara dan belum divalidasi; tidak diubah berdasarkan
10 capture ini. Masih diperlukan evaluasi terstruktur pada uang kertas/logam dan bukan uang,
variasi kondisi kamera/perangkat, TalkBack, kualitas audio TTS, JPEG_R hardware, serta
perbandingan Android dan runtime referensi dengan input identik.

## Hasil verifikasi skeleton sebelumnya (historis)

Diperiksa pada 25 Oktober 2026, emulator Pixel 9 / Android API 36:

- `assembleDebug`, `lintDebug`, `testDebugUnitTest`, dan `connectedDebugAndroidTest` berhasil.
- 3 unit test dan 9 tes instrumentasi lulus. Lint: 0 error, 19 warning tentang versi
  dependency/AGP yang lebih baru, label template, dan resource template yang tidak dipakai.
- Alur sistem diperiksa langsung: tidak ada dialog izin saat startup, penolakan sekali,
  penolakan permanen, membuka pengaturan, grant dari pengaturan lalu kembali ke preview.
- Izin dicabut melalui ADB lalu aplikasi dibuka kembali: kembali ke layar izin tanpa crash.
  Grant ulang lewat dialog sistem memulihkan preview dan capture sukses.
- Preview langsung dan capture sukses dilihat pada screenshot emulator. Clipping diperiksa:
  preview tetap berada di bawah header dan di atas panel kontrol.
- Tes kamera memeriksa stop/resume lifecycle, capture di memori, kegagalan konsumen yang
  disengaja lalu capture ulang, serta tombol nonaktif selama konsumen masih memegang frame.
- Tes UI memeriksa viewport 320 × 568dp, font 2×, scroll kontrol, dan nominal khusus Preview.

Pada sesi skeleton 25 Oktober 2026, belum diperiksa langsung: HP fisik, TalkBack,
Preview di editor Android Studio, rotasi/
kunci layar di HP, perangkat tanpa kamera belakang, kamera dipakai aplikasi lain,
dan kegagalan hardware asli. Kegagalan konsumen yang diuji bukan simulasi seluruh
kemungkinan error hardware CameraX.

## Pengujian manual sebelum penyerahan akhir

Daftar berikut merupakan pemeriksaan lanjutan; laporan 10 capture tidak menyatakan bahwa
semua langkah ini sudah selesai. Lakukan di HP fisik (API 26 dan versi Android terbaru bila tersedia):

1. Instal bersih: tidak ada dialog izin sebelum **Aktifkan kamera** ditekan.
2. Tolak izin sekali, minta ulang lewat tombol; tolak permanen dan cek **Buka pengaturan**.
3. Berikan izin dari pengaturan lalu kembali. Cabut izin dan buka aplikasi lagi.
4. Pastikan kamera belakang tampil, orientasi benar saat rotasi, dan gambar tidak meregang.
5. Capture uang kertas/logam dan bukan uang: satu proses per waktu; periksa nominal,
   ucapan Indonesia, skor/nominal di Room, serta hasil tidak dikenali/gagal tanpa insert.
6. Home/background, kembali, kunci/buka layar, dan rotasi saat kamera/capture aktif.
7. Uji kamera sedang dipakai aplikasi lain, kamera dinonaktifkan melalui privasi sistem,
   perangkat tanpa kamera belakang, dan retry setelah kondisinya pulih.
8. Uji TalkBack langsung: urutan fokus, status capture, tombol izin/pengaturan, dan
   tombol suara nonaktif. Uji font 2× serta viewport kecil; semua kontrol harus terjangkau.

Tes otomatis memeriksa kontrak penutupan ImageProxy, keputusan izin, state UI,
scroll/nominal Preview, capture kamera nyata di emulator, kegagalan callback, dan
stop/resume lifecycle. Tes emulator tidak menggantikan pengujian HP fisik/TalkBack.
