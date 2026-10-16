package ch.snapper.android.data

import java.math.BigDecimal
import java.util.Locale

/** Display-only settings. Country does not change the translation language, backend, or account. */
data class DisplayPreferences(
    val countryCode: String = "",
    val financialColor: FinancialColorPreference = FinancialColorPreference.AUTO,
) {
    init { require(countryCode.isEmpty() || DisplayCountries.find(countryCode) != null) }
    fun effectiveCountry(deviceLocale: Locale = Locale.getDefault()): DisplayCountry? =
        DisplayCountries.find(countryCode.ifEmpty { deviceLocale.country.lowercase(Locale.ROOT) })

    fun formattingLocale(deviceLocale: Locale = Locale.getDefault()): Locale {
        val country = effectiveCountry(deviceLocale)
        val locale = country?.nativeLocale ?: deviceLocale
        return Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()
    }

    fun convention(deviceLocale: Locale = Locale.getDefault()): FinancialColorConvention = when (financialColor) {
        FinancialColorPreference.RISING_RED -> FinancialColorConvention.RISING_RED
        FinancialColorPreference.RISING_GREEN -> FinancialColorConvention.RISING_GREEN
        FinancialColorPreference.AUTO -> if (effectiveCountry(deviceLocale)?.code in setOf("cn", "hk", "jp", "kr"))
            FinancialColorConvention.RISING_RED else FinancialColorConvention.RISING_GREEN
    }
}

enum class FinancialColorPreference(val wireValue: String) {
    AUTO("auto"), RISING_GREEN("rising-green"), RISING_RED("rising-red");
    companion object {
        fun parse(value: String): FinancialColorPreference? = entries.firstOrNull { it.wireValue == value }
    }
}
enum class FinancialColorConvention { RISING_GREEN, RISING_RED }
enum class FinancialDirectionColor { GREEN, RED, NEUTRAL }

/** Direction only: never use this to recolor error, success, or health/status indicators. */
fun financialDirectionColor(value: BigDecimal?, convention: FinancialColorConvention): FinancialDirectionColor {
    val direction = value?.signum() ?: 0
    if (direction == 0) return FinancialDirectionColor.NEUTRAL
    val green = (direction > 0) == (convention == FinancialColorConvention.RISING_GREEN)
    return if (green) FinancialDirectionColor.GREEN else FinancialDirectionColor.RED
}

class DisplayCountry internal constructor(val code: String, val catalogLanguage: String) {
    /** Unlike a language tag alone, this always retains the chosen region, including zh-Hant-HK. */
    val nativeLocale: Locale get() = Locale.Builder().setLanguageTag(catalogLanguage).setRegion(code.uppercase(Locale.ROOT)).build()
    val nativeName: String get() = nativeLocale.getDisplayCountry(nativeLocale).ifBlank { code.uppercase(Locale.ROOT) }
    fun localizedName(displayLanguage: Locale): String = nativeLocale.getDisplayCountry(displayLanguage).ifBlank { code.uppercase(Locale.ROOT) }
}

/** Exact country order from iOS AppLocale.swift (three rows of15); not the language-code catalogue. */
object DisplayCountries {
    val all: List<DisplayCountry> = listOf(
        "ie:ga", "us:en", "pl:pl", "de:de", "fr:fr", "es:es", "it:it", "nl:nl", "br:pt-BR", "se:sv", "no:nb", "dk:da", "fi:fi", "is:is", "gr:el",
        "cn:zh-Hans", "hk:zh-Hant", "jp:ja", "kr:ko", "th:th", "vn:vi", "ph:fil", "my:ms", "id:id", "mm:my", "in:hi", "bd:bn", "ke:sw", "ae:ar", "il:he",
        "cz:cs", "sk:sk", "hu:hu", "ro:ro", "ua:uk", "ru:ru", "lt:lt", "lv:lv", "hr:hr", "rs:sr-Latn", "ba:bs", "al:sq", "tr:tr", "ir:fa", "am:hy",
    ).map { val parts = it.split(':'); DisplayCountry(parts[0], parts[1]) }
    fun find(code: String): DisplayCountry? = all.firstOrNull { it.code == code }
}
