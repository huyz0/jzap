package io.github.huyz0.jzap.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimal JSON writer, which exists so the report module needs no serialisation library.
 *
 * <p>Escaping is the part worth pinning. The values written here come from the code under test --
 * test names, source lines, failure messages -- so anything at all can appear in them, and a
 * single unescaped character makes a report that no tool can read. The native JSON report is what
 * the parity harness consumes, so that would break the comparison against PIT rather than merely
 * look untidy.
 */
class JsonTest {

    @Test
    void quotesTheCharactersJsonReserves() {
        assertEquals("\"plain\"", Json.quote("plain"));
        assertEquals("\"say \\\"hi\\\"\"", Json.quote("say \"hi\""));
        assertEquals("\"back\\\\slash\"", Json.quote("back\\slash"));
    }

    @Test
    void quotesTheWhitespaceThatWouldBreakALine() {
        assertEquals("\"a\\nb\"", Json.quote("a\nb"));
        assertEquals("\"a\\rb\"", Json.quote("a\rb"));
        assertEquals("\"a\\tb\"", Json.quote("a\tb"));
    }

    @Test
    void escapesOtherControlCharactersAsUnicode() {
        // A failure message can carry anything, including a NUL from a native library.
        assertEquals("\"a\\u0000b\"", Json.quote("a\u0000b"));
        assertEquals("\"\\u0007\"", Json.quote("\u0007"));
        assertEquals("\"\\u001f\"", Json.quote("\u001f"));
    }

    @Test
    void leavesOrdinaryAndNonAsciiCharactersAlone() {
        assertEquals("\"space at 0x20\"", Json.quote("space at 0x20"));
        assertEquals("\"éàü\"", Json.quote("éàü"),
                "the file is written as UTF-8, so these need no escaping");
        assertEquals("\"\uD83D\uDE80\"", Json.quote("\uD83D\uDE80"));
    }

    @Test
    void anEmptyStringIsStillQuoted() {
        assertEquals("\"\"", Json.quote(""));
    }

    @Test
    void objectsAndArraysAreWrittenWithCommasBetweenEntries() {
        String json = new Json()
                .startObject()
                .key("a").value(1L)
                .key("b").value("two")
                .key("c").startArray()
                .element().value(3L)
                .element().value(4L)
                .endArray()
                .endObject()
                .finish();

        assertTrue(json.contains("\"a\""), json);
        assertTrue(json.contains("\"two\""), json);
        assertFalse(json.contains(",,"), "a stray comma makes it unparseable: " + json);
        assertFalse(json.contains("[,") || json.contains(",]"), json);
        assertEquals(count(json, '{'), count(json, '}'), json);
        assertEquals(count(json, '['), count(json, ']'), json);
    }

    @Test
    void aDoubleIsWrittenWithAFixedScaleAndNoLocaleSeparator() {
        String json = new Json().startObject().key("score").value(66.6666).endObject().finish();

        assertTrue(json.contains("66.6667") || json.contains("66.6666"), json);
        assertFalse(json.contains("66,"),
                "a comma decimal separator would make the report unparseable wherever the "
                        + "build happens to run: " + json);
    }

    @Test
    void aRawValuePassesThroughUntouched() {
        String json = new Json().startObject().key("nested").rawValue("{\"a\":1}").endObject()
                .finish();

        assertTrue(json.contains("{\"a\":1}"), json);
    }

    private static long count(String text, char c) {
        return text.chars().filter(ch -> ch == c).count();
    }
}
