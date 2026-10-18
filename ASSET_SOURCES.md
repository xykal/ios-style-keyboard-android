# Kamus / wordlist: sumber dan batasan

Aset di `app/src/main/assets/` adalah wordlist runtime tanpa definisi. Tidak memuat akun, data clipboard pengguna, atau koneksi jaringan.

- **Frekuensi kata Indonesia & English:** [FrequencyWords](https://github.com/hermitdave/FrequencyWords), file `content/2018/id/id_50k.txt` dan `content/2018/en/en_50k.txt`. Lisensi upstream adalah MIT; atribusi dan pemberitahuan lisensi dipertahankan di `THIRD_PARTY_NOTICES.md`.
- **Normalisasi aset:** daftar spell `spell_id.txt` dan `spell_en.txt` dibuat dari file 50k di atas, disaring menjadi kata Latin a–z sepanjang 2–20 huruf, dideduplikasi, dan diurutkan alfabetis. Tidak ada lagi wordlist Indonesia dari sumber tanpa lisensi yang jelas.
- Daftar prediksi berperingkat tetap menggunakan 14.000 kata Indonesia dan 12.000 kata English teratas dari FrequencyWords; daftar ini dipakai untuk saran dan swipe. Spell set hasil normalisasi berisi 46.917 kata Indonesia dan 46.691 kata English.
- Kamus Indonesia ini bukan produk resmi KBBI dan tidak menyertakan definisi. Korpus frekuensi dapat mengandung nama, istilah informal, atau kata yang jarang; koreksi otomatis tidak selalu tepat. Pengguna dapat mematikan koreksi dan prediksi.

## Terjemahan

Fitur terjemahan memakai endpoint publik MyMemory (`api.mymemory.translated.net`) tanpa API key. Pemanggilan hanya terjadi atas tindakan pengguna. Jangan gunakan fitur ini untuk mengirim data rahasia atau clipboard sensitif; layanan eksternal dapat memproses permintaan sesuai kebijakan mereka.
