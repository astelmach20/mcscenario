package mcscenario.datapack;

import mcscenario.model.ScenarioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerPropertiesTest {
    @TempDir
    Path dir;

    @Test
    void preservesCommentsAndOrderReplacesAndAppendsSorted() throws IOException {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, """
            #Minecraft server properties
            #Fri Jan 01 00:00:00 UTC 2026

            level-name=world
            online-mode = true
            motd: A Minecraft Server
              spawn-protection=16
            ! bang comment
            """);

        ServerProperties.merge(file, Map.of(
            "online-mode", "false",
            "motd", "test",
            "spawn-protection", "0",
            "zeta", "z",
            "function-permission-level", "4",
            "alpha", "a"
        ));

        assertEquals("""
            #Minecraft server properties
            #Fri Jan 01 00:00:00 UTC 2026

            level-name=world
            online-mode=false
            motd=test
            spawn-protection=0
            ! bang comment
            alpha=a
            function-permission-level=4
            zeta=z
            """, Files.readString(file));
    }

    @Test
    void noOpLeavesFileUntouched() throws IOException {
        Path file = dir.resolve("server.properties");
        String content = "# comment\nlevel-name=world\nonline-mode=false";
        Files.writeString(file, content);
        FileTime old = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
        Files.setLastModifiedTime(file, old);

        ServerProperties.merge(file, Map.of("online-mode", "false", "level-name", "world"));
        ServerProperties.merge(file, Map.of());

        assertEquals(content, Files.readString(file));
        assertEquals(old, Files.getLastModifiedTime(file));
    }

    @Test
    void createsMissingFile() throws IOException {
        Path file = dir.resolve("run/server.properties");

        ServerProperties.merge(file, Map.of("b", "2", "a", "1"));

        assertEquals("a=1\nb=2\n", Files.readString(file));
    }

    @Test
    void keepsCrlfAndMissingTrailingNewline() throws IOException {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "#c\r\na=1\r\nb=2");

        ServerProperties.merge(file, Map.of("b", "3"));
        assertEquals("#c\r\na=1\r\nb=3", Files.readString(file));

        ServerProperties.merge(file, Map.of("c", "4"));
        assertEquals("#c\r\na=1\r\nb=3\r\nc=4\r\n", Files.readString(file));
    }

    @Test
    void replacesContinuedEntryWhole() {
        String merged = ServerProperties.mergeContent("motd=line one \\\n    line two\nnext=1\n", Map.of("motd", "x"));
        assertEquals("motd=x\nnext=1\n", merged);
    }

    @Test
    void escapesValuesForPropertiesParsing() throws IOException {
        Path file = dir.resolve("server.properties");
        ServerProperties.merge(file, Map.of("path", "C:\\x", "motd", " padded"));

        Properties parsed = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            parsed.load(reader);
        }
        assertEquals("C:\\x", parsed.getProperty("path"));
        assertEquals(" padded", parsed.getProperty("motd"));
    }

    @Test
    void parsesKeysLikeJavaProperties() {
        assertEquals("a", ServerProperties.key("a=1"));
        assertEquals("a", ServerProperties.key("  a = 1"));
        assertEquals("a", ServerProperties.key("a: 1"));
        assertEquals("a", ServerProperties.key("a 1"));
        assertEquals("a=b", ServerProperties.key("a\\=b=1"));
        assertNull(ServerProperties.key("# a=1"));
        assertNull(ServerProperties.key("   "));
    }

    @Test
    void rejectsNewlinesAndBadKeys() {
        Path file = dir.resolve("server.properties");
        assertThrows(ScenarioException.class, () -> ServerProperties.merge(file, Map.of("a", "1\n2")));
        assertThrows(ScenarioException.class, () -> ServerProperties.merge(file, Map.of("a", "1\r2")));
        assertThrows(ScenarioException.class, () -> ServerProperties.merge(file, Map.of("a\nb", "1")));
        assertThrows(ScenarioException.class, () -> ServerProperties.merge(file, Map.of("a=b", "1")));
        assertThrows(ScenarioException.class, () -> ServerProperties.merge(file, Map.of("", "1")));
    }

    @Test
    void acceptEulaWritesFileAndCreatesDir() throws IOException {
        Path runDir = dir.resolve("nested/run");

        ServerProperties.acceptEula(runDir);

        assertEquals("eula=true\n", Files.readString(runDir.resolve("eula.txt")));
    }
}
