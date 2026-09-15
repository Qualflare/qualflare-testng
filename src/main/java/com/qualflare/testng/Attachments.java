package com.qualflare.testng;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How a published file becomes a wire attachment.
 *
 * <p>Two routes, and which one a file takes is decided by whether the upload endpoint can
 * accept it out of band:
 *
 * <ul>
 *   <li><b>png, jpeg, gif</b> are copied into {@code outputDir} and referenced by name
 *       ({@code localImagePath}). They never touch the report body, so a screenshot does
 *       not compete with the run's inline budget.</li>
 *   <li><b>everything else</b> is base64-inlined, because the endpoint has nowhere else to
 *       put it.</li>
 * </ul>
 *
 * <p>Needs {@code @qualflare/cli} v0.1.24 or newer, the first release that reads
 * {@code localImagePath}. On an older CLI the field is ignored, and because such an
 * attachment carries neither content nor a storage key the server records it from its name
 * alone -- an undownloadable placeholder. Upgrade the CLI before this reporter.
 */
final class Attachments {

    /** Per run, charged in ENCODED bytes: base64 inflates by ~4/3, and the budget that
     *  matters is what actually travels, not what was on disk. */
    private static final long INLINE_BUDGET_BYTES = 8L * 1024 * 1024;

    /** Per case. Beyond this the report is not more useful, just larger. */
    static final int MAX_PER_CASE = 50;

    private static final AtomicLong inlinedBytes = new AtomicLong();

    private Attachments() {}

    /**
     * @return a wire-ready attachment, or null when the file cannot be read. A missing
     *         attachment must never fail the run -- the test already passed or failed on
     *         its own merits.
     */
    static Attachment of(String name, Path source, String mediaType) {
        try {
            if (source == null || !Files.isReadable(source)) {
                return null;
            }
            String mime = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
            long size = Files.size(source);

            if (isImage(mime)) {
                Path dir = Paths.get(Config.outputDir());
                Files.createDirectories(dir);
                // Prefixed and randomised: two tests can publish "screenshot.png" and the
                // second must not overwrite the first.
                String target = "qf-attach-" + ThreadLocalRandom.current().nextInt(1_000_000)
                        + "-" + source.getFileName();
                Path copied = dir.resolve(target);
                Files.copy(source, copied, StandardCopyOption.REPLACE_EXISTING);
                return new Attachment(name, mime, null, target, size);
            }

            long encoded = 4 * ((size + 2) / 3);
            if (inlinedBytes.addAndGet(encoded) > INLINE_BUDGET_BYTES) {
                inlinedBytes.addAndGet(-encoded);
                return new Attachment(name, mime, null, null, size);
            }
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(source));
            return new Attachment(name, mime, b64, null, size);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isImage(String mime) {
        return mime.startsWith("image/png")
                || mime.startsWith("image/jpeg")
                || mime.startsWith("image/jpg")
                || mime.startsWith("image/gif");
    }

    /** Test-only. */
    static void resetBudget() {
        inlinedBytes.set(0);
    }

    static final class Attachment {
        final String name;
        final String mimeType;
        final String contentBase64;
        final String localImagePath;
        final long fileSize;

        Attachment(String name, String mimeType, String contentBase64, String localImagePath, long fileSize) {
            this.name = name;
            this.mimeType = mimeType;
            this.contentBase64 = contentBase64;
            this.localImagePath = localImagePath;
            this.fileSize = fileSize;
        }
    }
}
