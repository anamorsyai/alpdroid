package com.alpdroid.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit tests run on the json.org library, which has methods Android's own org.json lacks; calling one compiles and passes
 * here and then dies with NoSuchMethodError on a phone (an Error, so nothing answers the request). This keeps them out of the sources.
 */
class AndroidApiGuardTest {
    private val banned = listOf("valueToString(", "stringToValue(", "doubleToString(", "JSONObject.getNames(", "JSONObject.toMap(")

    @Test fun `main sources use only APIs that exist in Android's org json`() {
        val root = listOf("src/main/java", "alpbrowser/src/main/java").map { File(it) }.first { it.isDirectory }
        val hits = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> banned.any { l.contains(it) } && !l.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
                .map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }.toList()
        assertTrue("json.org-only calls (missing on Android): $hits", hits.isEmpty())
    }
}
