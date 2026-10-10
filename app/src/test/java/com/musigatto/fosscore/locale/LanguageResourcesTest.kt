package com.musigatto.fosscore.locale

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Keeps the language list, the default strings and the translation folders consistent. Runs on
 * the XML files themselves (unit tests run from the module directory), so a broken Crowdin sync
 * fails here rather than as a crash or a raw format string on a device.
 */
class LanguageResourcesTest {

    private val res = File("src/main/res")
    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    private fun strings(folder: String): Map<String, Element> {
        val file = File(res, "$folder/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("name") }
    }

    // values-es, values-b+pt+BR
    private fun folderOf(language: String): String =
        if ('-' in language) "values-b+" + language.replace('-', '+') else "values-$language"

    private val translationFolders: List<String>
        get() = res.listFiles()!!
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .map { it.name }

    @Test
    fun everySupportedLanguageHasTranslations() {
        LocaleHelper.SUPPORTED.filter { it != "en" }.forEach { language ->
            assertTrue("missing ${folderOf(language)}/strings.xml", File(res, "${folderOf(language)}/strings.xml").exists())
        }
    }

    @Test
    fun translationsOnlyUseKnownTranslatableKeys() {
        val default = strings("values")
        translationFolders.forEach { folder ->
            strings(folder).keys.forEach { key ->
                val source = default[key]
                assertTrue("$folder: '$key' is not in values/strings.xml", source != null)
                assertTrue("$folder: '$key' is marked translatable=\"false\"", source!!.getAttribute("translatable") != "false")
            }
        }
    }

    @Test
    fun translationsKeepTheSamePlaceholders() {
        val default = strings("values")
        translationFolders.forEach { folder ->
            strings(folder).forEach { (key, element) ->
                val expected = placeholder.findAll(default.getValue(key).textContent).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(element.textContent).map { it.value }.sorted().toList()
                assertEquals("$folder: placeholders of '$key'", expected, actual)
            }
        }
    }
}
