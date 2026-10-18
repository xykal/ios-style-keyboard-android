# Kamus / wordlist: sumber dan batasan

Aset di `app/src/main/assets/` dibuat sebagai wordlist runtime kecil—tidak berisi definisi, akun, clipboard pengguna, atau koneksi jaringan.

- **Frekuensi kata Indonesia & English:** [FrequencyWords](https://github.com/hermitdave/FrequencyWords), file dari folder `content/2018/id/id_50k.txt` dan `content/2018/en/en_50k.txt`. Lisensi repository menyebut MIT; atribusi hak cipta tetap berlaku.
- **Wordlist Indonesia tambahan:** [mychaelgo/indonesia-wordlist](https://github.com/mychaelgo/indonesia-wordlist), file `indonesian-wordlist.lst`, sekitar 79.901 baris sebelum penyaringan. Repository menerangkan daftar ini sebagai hasil crawling; tidak ditemukan file lisensi terpisah saat proyek disusun. Karena itu wordlist ini disertakan untuk pengembangan/pengujian pribadi dan **status redistribusi/komersial perlu diperiksa** oleh pemilik project.
- Kamus Indonesia ini bukan produk resmi KBBI dan tidak menyertakan definisi. Ada juga varian kata informal dari korpus frekuensi; koreksi tidak selalu cocok untuk nama, slang, atau bahasa campuran.

Pembentukan aset: kata dibatasi huruf Latin, dideduplikasi, lalu dibuat file ranking untuk prediksi/swipe dan file set untuk koreksi offline. Bahasa Indonesia memakai 14.000 kata berfrekuensi teratas, English 12.000; spell set Indonesia menambah wordlist Indonesia hasil crawling. Jumlah final: 14.000 + 12.000 ranked entries; sekitar 83.110 spell words Indonesia dan 12.000 English.

## Terjemahan

Fitur terjemahan memakai endpoint publik MyMemory (`api.mymemory.translated.net`) tanpa API key. Pemanggilan hanya terjadi atas tindakan pengguna. Jangan gunakan fitur ini untuk mengirim data rahasia atau clipboard sensitif; layanan eksternal dapat memproses permintaan sesuai kebijakan mereka.
