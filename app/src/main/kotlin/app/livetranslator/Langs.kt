package app.livetranslator

import com.google.mlkit.nl.translate.TranslateLanguage

/** لغة الصوت المصدر: اسم نموذج Vosk + رمز لغة ML Kit. الهدف دائماً العربية. */
data class SrcLang(val label: String, val vosk: String, val ml: String)

val LANGS = listOf(
    SrcLang("English - الإنجليزية", "vosk-model-small-en-us-0.15", TranslateLanguage.ENGLISH),
    SrcLang("Español - الإسبانية", "vosk-model-small-es-0.42", TranslateLanguage.SPANISH),
    SrcLang("Français - الفرنسية", "vosk-model-small-fr-0.22", TranslateLanguage.FRENCH),
    SrcLang("Deutsch - الألمانية", "vosk-model-small-de-0.15", TranslateLanguage.GERMAN),
    SrcLang("Русский - الروسية", "vosk-model-small-ru-0.22", TranslateLanguage.RUSSIAN),
    SrcLang("Türkçe - التركية", "vosk-model-small-tr-0.3", TranslateLanguage.TURKISH),
    SrcLang("日本語 - اليابانية", "vosk-model-small-ja-0.22", TranslateLanguage.JAPANESE),
    SrcLang("中文 - الصينية", "vosk-model-small-cn-0.22", TranslateLanguage.CHINESE),
)
