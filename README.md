# CurrenSee — skeleton UI dan kamera

Aplikasi Android Kotlin/Jetpack Compose, package `com.currensee.app`, minimum API 26.
Desain putih–biru dan font Satoshi lokal dipertahankan. CameraX 1.5.3 memakai versi
yang sama untuk core, camera2, lifecycle, dan view. Gradle serta versi dependency
lain mengikuti proyek yang sudah ada.

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

## Titik integrasi model

Edit **`app/src/main/java/com/currensee/app/camera/CapturedImageHandler.kt`**, fungsi
**`onCapturedImage(image: ImageProxy)`**. Fungsi ini diteruskan sebagai callback
`onImageCaptured` dari `CurrenSeeApp` ke `CameraController.capture`.

Kontrak callback:

- Berjalan di thread pekerja, bukan main thread; satu capture berlangsung pada satu waktu.
- `image` hanya valid sampai callback selesai. Controller selalu memanggil `close()`
  di `finally`, termasuk saat callback melempar exception. Konsumen tidak perlu menutupnya.
- Selesaikan konsumsi secara sinkron sebelum return. Untuk pekerjaan async, salin
  data yang dibutuhkan selama callback, lalu gunakan salinannya.
- `image.imageInfo.rotationDegrees`, `image.format`, dimensi, dan plane tersedia.
  Jangan menganggap buffer sudah diputar atau formatnya selalu sama dengan input model.
- Saat ini hanya validasi frame di memori. Konversi, preprocessing, model, dan
  pemetaan nominal akan ditambahkan anggota yang mengerjakan pengenalan uang.
- Setelah callback sukses, aplikasi hanya menampilkan:
  **“Gambar berhasil diambil. Pengenalan uang belum tersedia.”**

## Belum diintegrasikan

TFLite/model AI, preprocessing, nominal/confidence/deteksi nyata, TTS, dan database.
Nominal contoh hanya ada di Preview/pengujian. **Ulangi suara** selalu nonaktif pada
aplikasi skeleton. Lisensi Satoshi: `app/src/main/assets/licenses/Satoshi-FFL.txt`.

## Hasil verifikasi pengerjaan

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

Belum diperiksa langsung: HP fisik, TalkBack, Preview di editor Android Studio, rotasi/
kunci layar di HP, perangkat tanpa kamera belakang, kamera dipakai aplikasi lain,
dan kegagalan hardware asli. Kegagalan konsumen yang diuji bukan simulasi seluruh
kemungkinan error hardware CameraX.

## Pengujian manual sebelum penyerahan akhir

Lakukan di HP fisik (API 26 dan versi Android terbaru bila tersedia):

1. Instal bersih: tidak ada dialog izin sebelum **Aktifkan kamera** ditekan.
2. Tolak izin sekali, minta ulang lewat tombol; tolak permanen dan cek **Buka pengaturan**.
3. Berikan izin dari pengaturan lalu kembali. Cabut izin dan buka aplikasi lagi.
4. Pastikan kamera belakang tampil, orientasi benar saat rotasi, dan gambar tidak meregang.
5. Capture berulang: satu proses per waktu; sukses tanpa nominal/hasil AI palsu.
6. Home/background, kembali, kunci/buka layar, dan rotasi saat kamera/capture aktif.
7. Uji kamera sedang dipakai aplikasi lain, kamera dinonaktifkan melalui privasi sistem,
   perangkat tanpa kamera belakang, dan retry setelah kondisinya pulih.
8. Uji TalkBack langsung: urutan fokus, status capture, tombol izin/pengaturan, dan
   tombol suara nonaktif. Uji font 2× serta viewport kecil; semua kontrol harus terjangkau.

Tes otomatis memeriksa kontrak penutupan ImageProxy, keputusan izin, state UI,
scroll/nominal Preview, capture kamera nyata di emulator, kegagalan callback, dan
stop/resume lifecycle. Tes emulator tidak menggantikan pengujian HP fisik/TalkBack.
