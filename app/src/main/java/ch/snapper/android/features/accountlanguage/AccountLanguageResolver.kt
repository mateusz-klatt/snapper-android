package ch.snapper.android.features.accountlanguage

import ch.snapper.android.data.SupportedLanguages
import java.util.Locale

object AccountLanguageResolver {
    /** Explicit choices must already be canonical; system choice resolves independently of display country. */
    fun resolve(choice: String, deviceLocales: List<Locale>): String? {
        if (choice.isNotEmpty()) return SupportedLanguages.find(choice)?.tag
        return deviceLocales.firstNotNullOfOrNull(::catalog) ?: "en"
    }

    private fun catalog(locale: Locale): String? {
        val exact = locale.stripExtensions().toLanguageTag()
        SupportedLanguages.find(exact)?.let { return it.tag }
        val tag = when (locale.language) {
            "zh" -> if (locale.script != "Hans" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO"))) "zh-Hant" else "zh-Hans"
            "sr" -> "sr-Latn"
            "pt" -> "pt-BR"
            "no", "nb" -> "nb"
            "tl", "fil" -> "fil"
            else -> locale.language
        }
        return SupportedLanguages.find(tag)?.tag
    }
}
