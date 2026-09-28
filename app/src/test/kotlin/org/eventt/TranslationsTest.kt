package org.eventt

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every translation (composeResources/values-xx/strings.xml) must carry exactly the keys of the
 * English default in the same module, with the same %N$s placeholders — a missing key silently
 * falls back to English, a missing placeholder silently drops a number from the UI.
 */
class TranslationsTest {
    private val entry = Regex("""<(string|plurals) name="([^"]+)">(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%\d+\$[sd]""")

    private fun parse(file: File): Map<String, Set<String>> =
        entry.findAll(file.readText()).associate { m ->
            m.groupValues[2] to placeholder.findAll(m.groupValues[3]).map { it.value }.toSet()
        }

    private val root = File("..").canonicalFile

    @Test
    fun `translations match the english keys and placeholders`() {
        val defaults =
            root
                .walk()
                .onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.name == "strings.xml" && it.parentFile.name == "values" }
                .toList()
        defaults.shouldNotBeEmpty()

        val problems =
            defaults.flatMap { en ->
                val expected = parse(en)
                en.parentFile.parentFile
                    .listFiles { f -> f.name.startsWith("values-") }
                    .orEmpty()
                    .flatMap { dir ->
                        val actual = parse(File(dir, "strings.xml"))
                        val where = "${dir.relativeTo(root)}/strings.xml"
                        val mismatched = expected.keys.intersect(actual.keys).filter { expected[it] != actual[it] }
                        (expected.keys - actual.keys).map { "$where: missing $it" } +
                            (actual.keys - expected.keys).map { "$where: extra $it" } +
                            mismatched.map { "$where: placeholders differ in $it" }
                    }
            }
        problems.shouldBeEmpty()
    }
}
