# iOS Keyboard for Android

Keyboard Android bergaya iPhone, berfokus pada respons sentuh dan riwayat clipboard lokal. Nama/tampilan ini hanya proyek independen—bukan aplikasi resmi Apple.

## Fitur

- Layout QWERTY, baris angka, simbol, emoji, tombol shift/hapus/enter, serta pilihan bahasa Indonesia/English.
- Bar saran kata, koreksi typo ringan, kapital otomatis, titik dengan spasi ganda, tekan-lama untuk aksen, dan swipe typing. Kamus dimuat dari aset lokal; prediksi dan swipe tidak membutuhkan internet.
- Getar, suara tombol, pratinjau tombol, pilihan tema terang/gelap/otomatis/kustom, ukuran tombol, tinggi keyboard, dan mode satu tangan.
- Riwayat clipboard persisten memakai SQLite: deduplikasi, kategori otomatis (teks/link/email/catatan/kode), pencarian, pin, penghapusan, panel dalam keyboard, serta bubble/panel mengambang.
- Keyboard mengambang berbentuk overlay yang bisa digeser. Android akan meminta izin **Tampil di atas aplikasi lain**.
- Terjemahan Indonesia ↔ English melalui MyMemory. Permintaan hanya dibuat setelah pengguna menekan **Terjemahkan**; hanya teks terpilih/kata di dekat kursor yang dikirim.

## Batasan penting Android

Android membatasi clipboard demi privasi. Riwayat menangkap perubahan clipboard saat IME sedang aktif/terlihat (atau saat mode IME mengambang tetap berjalan); aplikasi tertentu dan Android versi tertentu dapat membatasi akses. Kolom sandi dan kolom dengan `IME_FLAG_NO_PERSONALIZED_LEARNING` dikecualikan. Clipboard **tidak** disinkronkan ke server. Item tersimpan lokal sampai dihapus; item yang dipin bisa dipertahankan saat membersihkan.

## Cara pakai

1. Build dan install APK (atau jalankan `./gradlew assembleDebug`).
2. Buka **iOS Keyboard** → **Aktifkan di pengaturan Android** → aktifkan layanan keyboard ini.
3. Kembali, tekan **Pilih sebagai keyboard**, lalu pilih **iOS Keyboard**.
4. Ketuk kolom uji coba. Clipboard otomatis mulai mencatat saat keyboard aktif.
5. Izin overlay hanya diperlukan untuk floating keyboard/bubble clipboard.

## Build lokal

Persyaratan: JDK 17, Android SDK platform 34 dan build-tools 34.0.0. Wrapper Gradle 8.9 sudah disertakan.

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`  
APK release: `app/build/outputs/apk/release/app-release.apk`

Tanpa keystore pribadi, varian release ditandatangani dengan debug key agar mudah diuji/sideload. **Jangan gunakan APK tersebut untuk Play Store atau pembaruan produksi.** Siapkan signing key milikmu sendiri untuk rilis publik.

## Build di GitHub Actions

Workflow `.github/workflows/android.yml` otomatis build APK debug dan release pada push, pull request, atau tombol **Run workflow**. Hasilnya tersedia di **Actions → workflow run → Artifacts** selama 14 hari. Push tag versi seperti `v1.0.0` menjalankan build yang sama dan membuat **Draft Release** dengan kedua APK sebagai lampiran; draft bisa ditinjau sebelum dipublikasikan.

Untuk menandatangani release dengan kunci sendiri, buat GitHub Actions secrets berikut:

- `ANDROID_KEYSTORE_BASE64` — file keystore/JKS dalam Base64.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Buat secret lewat **Settings → Secrets and variables → Actions**. Jangan taruh token GitHub, password, atau file keystore di source code. Workflow ini tidak memerlukan GitHub personal access token untuk sekadar build dan mengunduh artifact.

## Struktur project

- `app/src/main/java/.../ime` — InputMethodService, gambar/touch keyboard Canvas, aksi mengetik.
- `app/src/main/java/.../data` — SQLite clipboard dan kamus offline.
- `app/src/main/java/.../floatwin` — overlay bubble, panel clipboard, dan keyboard terapung.
- `app/src/main/java/.../ui` — layar setup, pengaturan, dan pengelola clipboard.
- `app/src/main/assets` — wordlist lokal.

## Lisensi data kamus

Lihat [`ASSET_SOURCES.md`](ASSET_SOURCES.md). Kode aplikasi dapat diberi lisensi terpisah dari wordlist pihak ketiga; periksa lisensi sumber sebelum distribusi komersial.
