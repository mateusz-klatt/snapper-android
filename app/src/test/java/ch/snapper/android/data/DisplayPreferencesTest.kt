package ch.snapper.android.data

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DisplayPreferencesTest {
    @Test fun countriesAreStrictDistinctFromTranslationLanguageCodes() {
        assertEquals(45, DisplayCountries.all.size)
        assertEquals(45, DisplayCountries.all.map { it.code }.toSet().size)
        listOf("en", "ga", "zh", "pt-BR", "PL", "pl-PL", "tw", "xx").forEach { invalid ->
            assertNull(DisplayCountries.find(invalid))
            assertThrows(IllegalArgumentException::class.java) { DisplayPreferences(invalid) }
        }
        assertEquals("my", DisplayCountries.find("mm")?.catalogLanguage)
        assertEquals("ms", DisplayCountries.find("my")?.catalogLanguage)
    }

    @Test fun countryFormattingIsIndependentOfDeviceTranslationLanguageAndRetainsScriptRegion() {
        val poland = DisplayPreferences("pl").formattingLocale(Locale.US)
        assertEquals("PL", poland.country)
        assertEquals("pl", poland.language)
        assertEquals("latn", poland.getUnicodeLocaleType("nu"))
        val hongKong = DisplayPreferences("hk").formattingLocale(Locale.ENGLISH)
        assertEquals("Hant", hongKong.script)
        assertEquals("HK", hongKong.country)
        assertEquals("BR", DisplayPreferences("br").formattingLocale().country)
        assertEquals("pt", DisplayPreferences("br").formattingLocale().language)
        assertEquals("Latn", DisplayPreferences("rs").formattingLocale().script)
    }

    @Test fun emptyMeansDeviceCountryAndUnsupportedDeviceRegionKeepsDeviceFormatting() {
        assertEquals("pl", DisplayPreferences().effectiveCountry(Locale.forLanguageTag("en-PL"))?.code)
        assertEquals("PL", DisplayPreferences().formattingLocale(Locale.forLanguageTag("en-PL")).country)
        assertNull(DisplayPreferences().effectiveCountry(Locale.CANADA))
        assertEquals("CA", DisplayPreferences().formattingLocale(Locale.CANADA).country)
        assertEquals(FinancialColorConvention.RISING_GREEN, DisplayPreferences().convention(Locale.ROOT))
    }

    @Test fun nativeAndLocalizedNamesUsePlatformCountryData() {
        val poland = requireNotNull(DisplayCountries.find("pl"))
        assertEquals("Polska", poland.nativeName)
        assertEquals("Poland", poland.localizedName(Locale.ENGLISH))
        DisplayCountries.all.forEach { assertTrue(it.nativeName.isNotBlank()) }
    }

    @Test fun colorAutoCountriesAndExplicitOverridesMatchIosWithoutChangingSeverity() {
        for (code in listOf("cn", "hk", "jp", "kr")) {
            assertEquals(FinancialColorConvention.RISING_RED, DisplayPreferences(code).convention())
            assertEquals(FinancialColorConvention.RISING_GREEN, DisplayPreferences(code, FinancialColorPreference.RISING_GREEN).convention())
        }
        assertEquals(FinancialColorConvention.RISING_GREEN, DisplayPreferences("pl").convention())
        assertEquals(FinancialColorConvention.RISING_RED, DisplayPreferences("pl", FinancialColorPreference.RISING_RED).convention())
        assertEquals(FinancialColorConvention.RISING_RED, DisplayPreferences().convention(Locale.JAPAN))
        assertNull(FinancialColorPreference.parse("risingRed"))
        assertEquals(FinancialColorPreference.RISING_RED, FinancialColorPreference.parse("rising-red"))
    }

    @Test fun exactPositiveNegativeZeroAndUnavailableColors() {
        for (convention in FinancialColorConvention.entries) {
            assertEquals(FinancialDirectionColor.NEUTRAL, financialDirectionColor(null, convention))
            assertEquals(FinancialDirectionColor.NEUTRAL, financialDirectionColor(BigDecimal.ZERO, convention))
        }
        assertEquals(FinancialDirectionColor.GREEN, financialDirectionColor(BigDecimal("0.0000000000000000001"), FinancialColorConvention.RISING_GREEN))
        assertEquals(FinancialDirectionColor.RED, financialDirectionColor(BigDecimal("-1"), FinancialColorConvention.RISING_GREEN))
        assertEquals(FinancialDirectionColor.RED, financialDirectionColor(BigDecimal.ONE, FinancialColorConvention.RISING_RED))
        assertEquals(FinancialDirectionColor.GREEN, financialDirectionColor(BigDecimal("-1"), FinancialColorConvention.RISING_RED))
    }
}
