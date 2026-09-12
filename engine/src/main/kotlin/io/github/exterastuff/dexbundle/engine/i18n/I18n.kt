package io.github.exterastuff.dexbundle.engine.i18n

import de.comahe.i18n4k.config.I18n4kConfigDefault
import de.comahe.i18n4k.createLocale
import de.comahe.i18n4k.i18n4k
import de.comahe.i18n4k.messages.formatter.MessageFormatterDefault
import java.util.Locale
import org.telegram.messenger.LocaleController

private val VALID_ISO_LANGUAGES = Locale.getISOLanguages().toHashSet()

private fun LocaleController.resolveLanguageCode(): String {
    val locale =
        this.currentLocaleInfo?.let {
            if (it.hasBaseLang()) it.baseLangCode else (it.langCode ?: it.shortName)
        } ?: this.currentLocale?.language ?: "en"

    val code = locale.trim().lowercase().replace('-', '_').substringBefore('_').ifEmpty { "en" }

    return if (code in VALID_ISO_LANGUAGES) code else "en"
}

fun setupI18n() {
    i18n4k =
        I18n4kConfigDefault().apply {
            locale = createLocale(LocaleController.getInstance().resolveLanguageCode())
        }

    MessageFormatterDefault.registerMessageValueFormatters(MessagePluralFormatter)
}
