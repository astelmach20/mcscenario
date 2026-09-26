package mcscenario.datapack;

import mcscenario.model.ScenarioException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Edits to a world folder on disk between runs. */
public final class WorldFiles {
    private WorldFiles() {
    }

    /**
     * Deletes a file or directory tree under {@code worldDir}.
     *
     * @return whether anything existed and was deleted
     * @throws ScenarioException if {@code relativePath} does not point strictly inside {@code worldDir}
     */
    public static boolean delete(Path worldDir, String relativePath) {
        Path target = resolveInside(worldDir, relativePath);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        deleteRecursively(target);
        return true;
    }

    private static Path resolveInside(Path worldDir, String relativePath) {
        Path base = worldDir.toAbsolutePath().normalize();
        Path target;
        try {
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute() || relative.getRoot() != null) {
                throw new ScenarioException("World file path must be relative: " + relativePath);
            }
            target = base.resolve(relative).normalize();
        } catch (InvalidPathException e) {
            throw new ScenarioException("Invalid world file path: " + relativePath, e);
        }
        if (target.equals(base) || !target.startsWith(base)) {
            throw new ScenarioException("World file path must point inside the world folder: " + relativePath);
        }
        return target;
    }

    /** Deletes {@code path} and, if it is a directory, everything below it. Symbolic links are removed, not followed. */
    static void deleteRecursively(Path path) {
        try {
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(path)) {
                paths = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path p : paths) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new ScenarioException("Could not delete " + path, e);
        }
    }
}
