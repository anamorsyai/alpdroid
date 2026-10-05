package com.alpdroid.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluginPackageTest {
    private val good = """
        {"alpdroid":1,"id":"hello","title":"Hello","description":"d","version":"1.0.0",
         "fields":[{"id":"name","type":"text","label":"Name","default":"world"},{"id":"mood","type":"select","options":["a","b"]}],
         "buttons":[{"id":"go","label":"Go","script":"go.sh"}],
         "schedules":[{"id":"tick","script":"lib/tick.sh","everyMinutes":5}],
         "files":{"go.sh":"echo hi\n","lib/tick.sh":"date\n"}}
    """.trimIndent()

    private fun rejects(text: String, contains: String) {
        try { PluginPackage.parse(text); fail("expected rejection containing: $contains") }
        catch (e: PluginPackage.PluginFormatException) { assertTrue(e.message!!, e.message!!.contains(contains)) }
    }

    @Test fun acceptsAValidPackage() {
        val p = PluginPackage.parse(good)
        assertEquals("hello", p.id)
        assertEquals(setOf("go.sh", "lib/tick.sh"), p.files.keys)
        assertFalse(p.manifest.has("files"))
        assertFalse(p.manifest.has("alpdroid"))
        assertEquals("Hello", p.manifest.getString("title"))
    }

    @Test fun rejectsNonJsonAndWrongVersion() {
        rejects("not json at all", "isn't JSON")
        rejects("""{"id":"x"}""", "format version")
        rejects("""{"alpdroid":2,"id":"x"}""", "format version")
    }

    @Test fun rejectsBadIds() {
        rejects("""{"alpdroid":1,"id":"Bad Id"}""", "\"id\"")
        rejects("""{"alpdroid":1}""", "\"id\"")
    }

    @Test fun rejectsPathTraversalAndReservedFiles() {
        rejects("""{"alpdroid":1,"id":"x","files":{"../evil.sh":"x"}}""", "not allowed")
        rejects("""{"alpdroid":1,"id":"x","files":{"/abs.sh":"x"}}""", "not allowed")
        rejects("""{"alpdroid":1,"id":"x","files":{"plugin.json":"{}"}}""", "not allowed")
        rejects("""{"alpdroid":1,"id":"x","files":{"logs/a.txt":"x"}}""", "not allowed")
        rejects("""{"alpdroid":1,"id":"x","files":{"a/../b.sh":"x"}}""", "not allowed")
    }

    @Test fun rejectsScriptsThatAreNotBundled() {
        rejects("""{"alpdroid":1,"id":"x","buttons":[{"id":"b","script":"missing.sh"}],"files":{}}""", "missing from \"files\"")
        rejects("""{"alpdroid":1,"id":"x","schedules":[{"id":"b","script":"../x.sh"}],"files":{}}""", "not an allowed file name")
    }

    @Test fun rejectsBadFieldsAndDuplicates() {
        rejects("""{"alpdroid":1,"id":"x","fields":[{"id":"a","type":"color"}]}""", "type")
        rejects("""{"alpdroid":1,"id":"x","fields":[{"id":"a","type":"select"}]}""", "options")
        rejects("""{"alpdroid":1,"id":"x","fields":[{"id":"a"},{"id":"a"}]}""", "Duplicate")
        rejects("""{"alpdroid":1,"id":"x","files":{"a.sh":1}}""", "text string")
    }

    @Test fun rejectsOversizedContent() {
        rejects("""{"alpdroid":1,"id":"x","files":{"a.sh":"${"y".repeat(300_000)}"}}""", "too large")
    }

    @Test fun exportThenParseRoundTrips() {
        val p = PluginPackage.parse(good)
        val manifest = JSONObject(p.manifest.toString()).put("id", p.id)
        val text = PluginPackage.export(manifest.toString(), p.files)
        val again = PluginPackage.parse(text)
        assertEquals(p.id, again.id)
        assertEquals(p.files, again.files)
        assertEquals(p.manifest.getJSONArray("buttons").length(), again.manifest.getJSONArray("buttons").length())
    }

    @Test fun theBundledExampleIsValid() {
        // Gradle runs unit tests with the module (app/) as working directory.
        val example = java.io.File("../examples/hello.ad")
        val parsed = PluginPackage.parse(example.readText())
        assertEquals("hello-ad", parsed.id)
        assertTrue(parsed.files.containsKey("greet.sh"))
    }

    @Test fun rejectsStateJsonAtAnyDepthAndDotSegments() {
        rejects("""{"alpdroid":1,"id":"x","files":{"lib/state.json":"x"}}""", "not allowed")
        rejects("""{"alpdroid":1,"id":"x","files":{"lib/state.json.sh":"x"}}""", "not allowed")
        rejects("""{"alpdroid":1,"id":"x","files":{"a/./b.sh":"x"}}""", "not allowed")
        rejects("""{"alpdroid":1,"id":"x","buttons":[{"id":"b","script":"state.json.sh"}],"files":{}}""", "not an allowed file name")
    }

    @Test fun rejectsAFileThatIsAlsoAFolder() {
        rejects("""{"alpdroid":1,"id":"x","files":{"a":"x","a/b.sh":"y"}}""", "both a file and a folder")
    }

    @Test fun rejectsEnvNameCollisionsAndSharedJobIds() {
        rejects("""{"alpdroid":1,"id":"x","fields":[{"id":"a-b"},{"id":"a_b"}]}""", "collides")
        rejects("""{"alpdroid":1,"id":"x","buttons":[{"id":"j","script":"a.sh"}],"schedules":[{"id":"j","script":"a.sh"}],"files":{"a.sh":"x"}}""", "Duplicate button/schedule")
    }

    @Test fun rejectsBadTypesAndLengths() {
        rejects("""{"alpdroid":1,"id":"x","title":5}""", "must be text")
        rejects("""{"alpdroid":1,"id":"x","title":"${"t".repeat(121)}"}""", "too long")
        rejects("""{"alpdroid":1,"id":"x","buttons":[{"id":"b","script":"a.sh","background":"yes"}],"files":{"a.sh":"x"}}""", "true or false")
        rejects("""{"alpdroid":1,"id":"x","schedules":[{"id":"b","script":"a.sh","everyMinutes":0}],"files":{"a.sh":"x"}}""", "1 to 10080")
        rejects("""{"alpdroid":1,"id":"x","fields":[{"id":"a","default":{"k":1}}]}""", "default")
    }
}
