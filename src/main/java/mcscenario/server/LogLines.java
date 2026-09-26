package mcscenario.server;

import java.util.regex.Pattern;

/** Helpers for server log output. */
public final class LogLines {
    /** OSC ({@code ESC ] ... BEL|ST}), CSI ({@code ESC [ params intermediates final}), or a two-byte escape. */
    private static final Pattern ANSI = Pattern.compile(
        "\u001B(?:\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[@-_])"
    );

    private LogLines() {
    }

    public static String stripAnsi(String line) {
        return line.indexOf('\u001B') < 0 ? line : ANSI.matcher(line).replaceAll("");
    }
}
