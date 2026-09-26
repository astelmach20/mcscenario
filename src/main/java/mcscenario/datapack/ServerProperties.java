package mcscenario.datapack;

import mcscenario.model.ScenarioException;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Edits {@code server.properties} and {@code eula.txt} in place, leaving everything else in them untouched. */
public final class ServerProperties {
    private ServerProperties() {
    }

    /**
     * Sets each override in {@code file}, creating it if missing. Existing lines keep their order, comments and
     * formatting; an entry whose key is overridden is replaced by {@code key=value}, and overrides for keys not yet
     * present are appended in key order. The file is not rewritten if its content would not change.
     */
    public static void merge(Path file, Map<String, String> overrides) {
        TreeMap<String, String> pending = new TreeMap<>();
        overrides.forEach((key, value) -> {
            validateKey(key);
            if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new ScenarioException("server.properties value for " + key + " must be single-line");
            }
            pending.put(key, value);
        });

        try {
            boolean exists = Files.exists(file);
            Charset charset = StandardCharsets.UTF_8;
            String original = "";
            if (exists) {
                try {
                    original = Files.readString(file, charset);
                } catch (CharacterCodingException e) {
                    // Older servers wrote server.properties as ISO-8859-1; keep its bytes intact.
                    charset = StandardCharsets.ISO_8859_1;
                    original = Files.readString(file, charset);
                }
            }
            String updated = mergeContent(original, pending);
            if (!exists || !updated.equals(original)) {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, updated, charset);
            }
        } catch (IOException e) {
            throw new ScenarioException("Could not update " + file, e);
        }
    }

    /** Writes {@code runDir/eula.txt} accepting the Minecraft EULA. */
    public static void acceptEula(Path runDir) {
        try {
            Files.createDirectories(runDir);
            Files.writeString(runDir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ScenarioException("Could not write eula.txt in " + runDir, e);
        }
    }

    static String mergeContent(String original, Map<String, String> overrides) {
        TreeMap<String, String> pending = new TreeMap<>(overrides);
        String separator = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(original.split("\r\n|\r|\n", -1)));
        boolean trailingNewline = lines.getLast().isEmpty();
        if (trailingNewline) {
            lines.removeLast();
        }

        List<String> out = new ArrayList<>(lines.size() + pending.size());
        for (int i = 0; i < lines.size(); ) {
            int end = logicalLineEnd(lines, i);
            String key = key(lines.get(i));
            if (key != null && overrides.containsKey(key)) {
                out.add(entry(key, overrides.get(key)));
                pending.remove(key);
            } else {
                out.addAll(lines.subList(i, end));
            }
            i = end;
        }
        boolean appended = !pending.isEmpty();
        pending.forEach((key, value) -> out.add(entry(key, value)));

        String joined = String.join(separator, out);
        return trailingNewline || appended ? joined + separator : joined;
    }

    /** Index after the last physical line of the logical line starting at {@code start}. Comments never continue. */
    private static int logicalLineEnd(List<String> lines, int start) {
        int i = start;
        if (!isComment(lines.get(i))) {
            while (i + 1 < lines.size() && endsWithContinuation(lines.get(i))) {
                i++;
            }
        }
        return i + 1;
    }

    private static boolean isComment(String line) {
        String s = line.stripLeading();
        return s.startsWith("#") || s.startsWith("!");
    }

    private static boolean endsWithContinuation(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** The key of an entry line as {@link java.util.Properties} parses it, or null for blank and comment lines. */
    static String key(String line) {
        String s = line.stripLeading();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                key.append(s.charAt(++i));
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                break;
            } else {
                key.append(c);
            }
        }
        return key.toString();
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty() || key.startsWith("#") || key.startsWith("!")
            || key.chars().anyMatch(c -> c == '=' || c == ':' || c == '\\' || Character.isWhitespace(c))) {
            throw new ScenarioException("Invalid server.properties key: " + key);
        }
    }

    /** Escapes backslashes and a leading space so {@link java.util.Properties} reads {@code value} back verbatim. */
    private static String entry(String key, String value) {
        String escaped = value.replace("\\", "\\\\");
        if (escaped.startsWith(" ")) {
            escaped = "\\" + escaped;
        }
        return key + "=" + escaped;
    }
}
