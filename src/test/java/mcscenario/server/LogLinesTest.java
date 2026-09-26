package mcscenario.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class LogLinesTest {
    @Test
    void stripsColours() {
        assertEquals("[12:00:00] [Server thread/INFO]: Done",
            LogLines.stripAnsi("\u001B[0;32m[12:00:00] [Server thread/INFO]\u001B[m: Done\u001B[0m"));
    }

    @Test
    void stripsBoldAndReset() {
        assertEquals("bold normal", LogLines.stripAnsi("\u001B[1mbold\u001B[22m normal\u001B[0m"));
    }

    @Test
    void strips256AndTrueColour() {
        assertEquals("a b", LogLines.stripAnsi("\u001B[38;5;208ma\u001B[39m \u001B[48;2;10;20;30mb\u001B[49m"));
    }

    @Test
    void stripsOscWithBelAndStringTerminator() {
        assertEquals("title link",
            LogLines.stripAnsi("\u001B]0;Minecraft\u0007title \u001B]8;;https://x.test\u001B\\link\u001B]8;;\u001B\\"));
    }

    @Test
    void stripsCursorControl() {
        assertEquals("> list", LogLines.stripAnsi("\u001B[2K\u001B[1G> list"));
    }

    @Test
    void leavesPlainTextUnchanged() {
        String line = "[Server thread/INFO]: [probe] tps=[20.0] 50% done; a[b]c";
        assertSame(line, LogLines.stripAnsi(line));
        assertEquals("", LogLines.stripAnsi(""));
    }
}
