# iOS Style Keyboard for Android

Keyboard Android independen bergaya iPhone—bukan aplikasi resmi Apple. Aplikasi menyediakan input method biasa dan panel mengambang opsional.

## Fitur

- **Dua layout:** iOS-style untuk ponsel dan QWERTY PC dengan Esc, F1–F12, Tab, Caps, Ctrl, Alt, Win, tombol navigasi, serta baris angka/simbol.
- **Tampilan:** keycaps adaptif, tema terang/gelap/otomatis/kustom, ukuran teks dan tinggi, mode satu tangan, serta ikon kontrol monokrom yang digambar sebagai ikon (bukan emoji glyph). Emoji tetap tersedia sebagai karakter dalam panel emoji.
- **Pengetikan:** prediksi awalan kata dan beberapa saran kata berikutnya, koreksi ringan, kapital otomatis, titik dengan spasi ganda, aksen tekan-lama, dan swipe typing. Kamus dan saran berjalan offline.
- **Clipboard lokal:** riwayat teks dan gambar, pencarian, kategori, pin, deduplikasi, penghapusan, dan retensi otomatis. Gambar dibatasi 6 MiB per item dan 40 MiB total untuk gambar yang tidak disematkan.
- **Tempel gambar:** IME mencoba `commitContent` untuk aplikasi tujuan yang menyatakan dukungan MIME gambar. Jika tidak didukung, gambar disalin ke clipboard sistem supaya pengguna dapat mencoba paste biasa. Tidak semua aplikasi Android menerima paste gambar.
- **Panel mengambang:** keyboard atau bubble clipboard dapat diseret sebagai overlay. Lebar dan tinggi keyboard mengambang dapat diatur terpisah dan diperbarui saat panel terbuka. Android meminta izin **Tampil di atas aplikasi lain** untuk panel overlay ini. Keyboard biasa sebagai IME Android tidak membutuhkan izin tersebut.
- **Terjemahan:** pilihan bahasa sumber dan tujuan yang dapat dicari, mencakup lebih dari 100 kode bahasa yang digunakan adapter MyMemory. Dukungan tiap pasangan bahasa dapat berbeda. Teks hanya dikirim setelah pengguna meminta terjemahan; potongan lebih dari 500 byte dipecah otomatis.

## Password dan privasi

Keyboard ini **tidak menyimpan password atau login dari ketikan** dan bukan password manager. Gunakan Android Autofill atau password manager pilihanmu untuk menyimpan dan mengisi login. Kolom password serta field dengan `IME_FLAG_NO_PERSONALIZED_LEARNING` tidak menerima prediksi atau tangkapan clipboard otomatis. Clipboard yang secara eksplisit ditandai sensitif oleh aplikasi sumber diabaikan. Database dan file gambar clipboard disimpan di ruang privat aplikasi; Android Backup dinonaktifkan. Hapus riwayat dari layar Clipboard untuk menghapus data tersimpan.

Keterbatasan akses clipboard ditentukan Android dan aplikasi sumber. Riwayat ditangkap ketika IME aktif dan izin sistem mengizinkan. Prediksi, koreksi, dan swipe tidak mengirim teks ke internet. Terjemahan mengirim teks terpilih ke MyMemory hanya atas permintaan pengguna; jangan terjemahkan data rahasia. MyMemory membatasi penggunaan anonim hingga 5.000 karakter per hari dan 500 byte per permintaan; teks panjang dipecah menjadi beberapa permintaan. Lihat [spesifikasi API](https://mymemory.translated.net/doc/spec.php) dan [batas penggunaan](https://mymemory.translated.net/doc/usagelimits.php).

## Cara pakai

1. Install APK.
2. Buka **iOS Keyboard** → **Aktifkan di pengaturan Android** → aktifkan metode input ini.
3. Pilih **iOS Keyboard** dari pemilih keyboard Android.
4. Atur layout **iOS** atau **QWERTY PC** di Pengaturan. Mode QWERTY PC memakai lebar layar penuh ketika menjadi overlay agar tombol fungsi tetap terbaca.
5. Untuk keyboard atau bubble yang benar-benar mengambang di atas aplikasi lain, aktifkan izin overlay saat Android memintanya.

## Build di GitHub Actions

Workflow `.github/workflows/android.yml` membangun APK debug, APK release, dan Android App Bundle (AAB) di GitHub Actions pada push, pull request, atau **Run workflow**. Hasil run tersedia di **Actions → workflow run → Artifacts** selama 14 hari. Push tag versi seperti `v1.1.0` wajib memakai keystore release yang sudah dikonfigurasi; Actions memverifikasi signature APK/AAB terhadap identitas signing yang ada lalu membuat **Draft Release** berisi APK release dan AAB. Draft tidak dipublikasikan otomatis, dan APK debug tidak disertakan.

Release memakai R8 (minify dan shrink resources aktif). Keystore release dibaca dari Actions secrets berikut—jangan commit keystore, password, atau GitHub token ke source:

- `ANDROID_KEYSTORE_BASE64` — JKS dalam Base64.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Workflow memakai keystore signing yang sudah ada; jangan menggantinya dengan keystore baru karena pembaruan aplikasi harus memakai identitas signing yang sama. Kunci tersebut bukan Play App Signing. **AAB bertanda tangan ini belum siap dikirim ke Google Play:** proyek masih menargetkan API 34, sementara mulai 31 Agustus 2026 Google Play mewajibkan API 36 untuk pengajuan aplikasi baru dan update. Jika tujuanmu Play Store, target API perlu dinaikkan dan diuji dulu, lalu AAB diunggah lewat Play Console. Simpan backup JKS dan kredensial secara terpisah dan aman.

Kebijakan target API: [Google Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en).

## Sumber kamus

Wordlist Indonesia dan English berasal dari FrequencyWords (MIT) dan dinormalisasi/dideduplikasi; tidak memakai wordlist dengan lisensi redistribusi yang tidak diketahui. Atribusi lengkap ada di [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), rincian aset di [`ASSET_SOURCES.md`](ASSET_SOURCES.md). Kamus Indonesia bukan produk resmi KBBI.
