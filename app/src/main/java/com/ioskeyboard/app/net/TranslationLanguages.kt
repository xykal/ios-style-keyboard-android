package com.ioskeyboard.app.net

/**
 * Language inventory adapted from https://github.com/alettsy/mymemory_translate#languages.
 * MyMemory accepts ISO/RFC3066-style codes, but support varies by language pair and provider.
 */
object TranslationLanguages {
    data class Language(val name: String, val code: String)

    val all: List<Language> = """
        Deteksi otomatis|Autodetect
        Afrikaans|af-ZA
        Albania|sq-AL
        Amharik|am-ET
        Arab|ar-SA
        Armenia|hy-AM
        Azerbaijan|az-AZ
        Bajan|bjs-BB
        Balkan Gipsy|rm-RO
        Basque|eu-ES
        Bemba|bem-ZM
        Bengali|bn-IN
        Belarus|be-BY
        Bislama|bi-VU
        Bosnia|bs-BA
        Breton|br-FR
        Bulgaria|bg-BG
        Burma|my-MM
        Catalan|ca-ES
        Cebuano|ceb-PH
        Chamorro|ch-GU
        Tionghoa (Sederhana)|zh-CN
        Tionghoa (Tradisional)|zh-TW
        Comorian (Ngazidja)|zdj-KM
        Koptik|cop-EG
        Kreol Inggris (Antigua dan Barbuda)|aig-AG
        Kreol Inggris (Bahama)|bah-BS
        Kreol Inggris (Grenada)|gcl-GD
        Kreol Inggris (Guyana)|gyn-GY
        Kreol Inggris (Jamaika)|jam-JM
        Kreol Inggris (Saint Vincent)|svc-VC
        Kreol Inggris (Kepulauan Virgin)|vic-US
        Kreol Prancis (Haiti)|ht-HT
        Kreol Prancis (Saint Lucia)|acf-LC
        Kreol Prancis (Seselwa)|crs-SC
        Kreol Portugis (Guinea Hulu)|pov-GW
        Kroasia|hr-HR
        Ceko|cs-CZ
        Denmark|da-DK
        Belanda|nl-NL
        Dzongkha|dz-BT
        Inggris (Amerika Serikat)|en-US
        Inggris (Britania Raya)|en-GB
        Esperanto|eo-EU
        Estonia|et-EE
        Fanagalo|fn-FNG
        Faroe|fo-FO
        Finlandia|fi-FI
        Prancis|fr-FR
        Galisia|gl-ES
        Georgia|ka-GE
        Jerman|de-DE
        Yunani|el-GR
        Yunani Klasik|grc-GR
        Gujarati|gu-IN
        Hausa|ha-NE
        Hawaii|haw-US
        Ibrani|he-IL
        Hindi|hi-IN
        Hungaria|hu-HU
        Islandia|is-IS
        Indonesia|id-ID
        Inuktitut (Greenland)|kl-GL
        Irlandia|ga-IE
        Italia|it-IT
        Jepang|ja-JP
        Jawa|jv-ID
        Kabuverdianu|kea-CV
        Kabylian|kab-DZ
        Kannada|kn-IN
        Kazakh|kk-KZ
        Khmer|km-KM
        Kinyarwanda|rw-RW
        Kirundi|rn-BI
        Korea|ko-KR
        Kurdi|ku-TR
        Kurdi Sorani|ckb-IQ
        Kirgiz|ky-KG
        Lao|lo-LA
        Latin|la-VA
        Latvia|lv-LV
        Lituania|lt-LT
        Luksemburg|lb-LU
        Makedonia|mk-MK
        Malagasi|mg-MG
        Melayu|ms-MY
        Maladewa|dv-MV
        Malta|mt-MT
        Manx Gaelic|gv-IM
        Maori|mi-NZ
        Marshall|mh-MH
        Mende|men-SL
        Mongolia|mn-MN
        Morisyen|mfe-MU
        Nepali|ne-NP
        Niuean|niu-NU
        Norwegia|no-NO
        Nyanja|ny-MW
        Urdu (Pakistan)|ur-PK
        Palauan|pau-PW
        Punjabi|pa-IN
        Papiamentu|pap-CW
        Pashto|ps-PK
        Persia|fa-IR
        Pijin|pis-SB
        Polandia|pl-PL
        Portugis|pt-PT
        Potawatomi|pot-US
        Quechua|qu-PE
        Rumania|ro-RO
        Rusia|ru-RU
        Samoa|sm-WS
        Sango|sg-CF
        Gaelik Skotlandia|gd-GB
        Serbia|sr-RS
        Shona|sn-ZW
        Sinhala|si-LK
        Slovakia|sk-SK
        Slovenia|sl-SI
        Somali|so-SO
        Sesotho Selatan|st-ST
        Spanyol|es-ES
        Sranan Tongo|srn-SR
        Swahili|sw-SZ
        Swedia|sv-SE
        Swiss German|de-CH
        Syriac (Aramaic)|syc-TR
        Tagalog|tl-PH
        Tajik|tg-TJ
        Tamashek (Tuareg)|tmh-DZ
        Tamil|ta-LK
        Telugu|te-IN
        Tetum|tet-TL
        Thai|th-TH
        Tibet|bo-CN
        Tigrinya|ti-TI
        Tok Pisin|tpi-PG
        Tokelauan|tkl-TK
        Tonga|to-TO
        Tswana|tn-BW
        Turki|tr-TR
        Turkmen|tk-TM
        Tuvaluan|tvl-TV
        Ukraina|uk-UA
        Uma|ppk-ID
        Uzbek|uz-UZ
        Vietnam|vi-VN
        Wallisian|wls-WF
        Welsh|cy-GB
        Wolof|wo-SN
        Xhosa|xh-ZA
        Yiddish|yi-YD
        Zulu|zu-ZA
    """.trimIndent().lineSequence().mapNotNull { line ->
        val parts = line.split('|', limit = 2)
        if (parts.size == 2) Language(parts[0].trim(), parts[1].trim()) else null
    }.toList()

    val targets: List<Language> get() = all.filterNot { it.code.equals("Autodetect", ignoreCase = true) }

    fun find(code: String): Language? {
        val normalized = code.trim()
        if (normalized.equals("auto", ignoreCase = true)) return all.firstOrNull { it.code.equals("Autodetect", ignoreCase = true) }
        return all.firstOrNull { it.code.equals(normalized, ignoreCase = true) }
            ?: all.firstOrNull { it.code.substringBefore('-').equals(normalized, ignoreCase = true) }
    }

}
