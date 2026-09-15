package com.qualflare.testng;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Test-only temp-directory cleanup. Several tests point {@code qualflare.outputDir} at a
 * fresh temp directory and then let the reporter write into it; without this they leave a
 * directory (and, for the attachment tests, copied files) behind on every run.
 */
final class Temp {

    private Temp() {}

    /** Best effort: a test must not fail because the OS would not delete a temp file. */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // leave it to the OS
                }
            });
        } catch (IOException ignored) {
            // leave it to the OS
        }
    }
}
