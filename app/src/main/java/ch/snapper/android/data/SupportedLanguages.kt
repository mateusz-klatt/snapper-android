package ch.snapper.android.data

import java.util.Locale

/** Tags mirror iOS CatalogLanguage.swift; RTL flags mirror frontend i18n/locales.ts. */
data class SupportedLanguage(val tag: String, val isRtl: Boolean) {
    /** Native ICU autonym, following iOS AppLocale's platform-resolved native-name approach. */
    val nativeName: String get() {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).ifBlank { tag }
    }
}

/** Partial reviewed translations; absent Android entries intentionally use the English default. */
object SupportedLanguages {
    val options: List<SupportedLanguage> = listOf(
        "en", "ga", "pl", "de", "fr", "es", "it", "nl", "pt-BR", "sv", "nb", "da", "fi", "cs", "sk",
        "hu", "ro", "hr", "uk", "ru", "lt", "lv", "sr-Latn", "bs", "sq", "is", "el", "tr", "fil", "ms",
        "id", "sw", "bn", "zh-Hans", "zh-Hant", "ja", "ko", "th", "vi", "my", "hi", "ar", "he", "fa", "hy",
    ).map { tag ->
        require(Locale.Builder().setLanguageTag(tag).build().toLanguageTag() == tag)
        SupportedLanguage(tag, tag in setOf("ar", "he", "fa"))
    }

    fun find(tag: String): SupportedLanguage? = options.firstOrNull { it.tag == tag }
}
