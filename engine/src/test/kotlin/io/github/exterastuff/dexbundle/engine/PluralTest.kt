package io.github.exterastuff.dexbundle.engine

import de.comahe.i18n4k.createLocale
import de.comahe.i18n4k.messages.formatter.MessageFormatterDefault
import de.comahe.i18n4k.messages.formatter.MessageParameters
import io.github.exterastuff.dexbundle.engine.i18n.MessagePluralFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class PluralTest {
    private fun format(
        value: Any?,
        lang: String,
        style: String = "one{one}few{few}many{many}other{other}",
    ): String {
        MessageFormatterDefault.registerMessageValueFormatters(MessagePluralFormatter)

        return MessageFormatterDefault.format(
            "{n,plural,$style}",
            object : MessageParameters {
                override fun get(name: CharSequence): Any? = value
            },
            createLocale(lang),
        )
    }

    @Test
    fun russianCategoriesAndNegativeCounts() {
        for (n in listOf(1, 21, 101)) {
            assertEquals("one", format(n, "ru"))
            assertEquals("one", format(-n, "ru"))
        }

        for (n in listOf(2, 3, 4, 22, 104)) {
            assertEquals("few", format(n, "ru"))
            assertEquals("few", format(-n, "ru"))
        }

        for (n in listOf(0, 5, 11, 12, 14, 20, 111, Int.MIN_VALUE))
            assertEquals("many", format(n, "ru"))
    }

    @Test
    fun englishFallbackAndInputConversion() {
        for (lang in listOf("en", "de")) {
            for (n in listOf(1, -1, "1", 1.9))
                assertEquals("one", format(n, lang))

            for (n in listOf(null, "invalid", 0, 2))
                assertEquals("other", format(n, lang))
        }

        assertEquals("fallback", format(2, "ru", "one{one}other{fallback}"))
        assertEquals("constant", format(2, "ru", "{constant}"))
        assertEquals("", format(2, "ru", "one{one}"))
    }

    @Test
    fun otherIsFallbackRegardlessOfPosition() {
        assertEquals("one", format(1, "en", "other{fallback}one{one}"))
    }
}
