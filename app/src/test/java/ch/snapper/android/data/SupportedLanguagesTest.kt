package ch.snapper.android.data

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SupportedLanguagesTest {
    @Test fun exactCatalogTagsAreUniqueCanonicalAndHaveNativeNames() {
        val options = SupportedLanguages.options
        assertEquals(45, options.size)
        assertEquals(45, options.map { it.tag }.toSet().size)
        options.forEach {
            assertEquals(it.tag, Locale.Builder().setLanguageTag(it.tag).build().toLanguageTag())
            assertTrue(it.nativeName.isNotBlank())
        }
        assertEquals(setOf("ar", "he", "fa"), options.filter { it.isRtl }.map { it.tag }.toSet())
        assertEquals("zh-Hans", SupportedLanguages.find("zh-Hans")?.tag)
        assertEquals("zh-Hant", SupportedLanguages.find("zh-Hant")?.tag)
        assertEquals("sr-Latn", SupportedLanguages.find("sr-Latn")?.tag)
        assertNull(SupportedLanguages.find("zh"))
        assertNull(SupportedLanguages.find("unsupported"))
    }
}
