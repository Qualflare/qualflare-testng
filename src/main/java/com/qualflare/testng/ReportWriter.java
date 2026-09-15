package com.qualflare.testng;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Serialises the accumulated run into the report directory {@code qf collect} reads. */
final class ReportWriter {

    /** The server drops an attempts array shorter than this, so it is not worth sending. */
    private static final int MIN_ATTEMPTS_TO_SEND = 2;

    private ReportWriter() {}

    /**
     * Chosen ONCE per JVM, not once per write.
     *
     * <p>Two requirements pull in opposite directions. Between JVMs the name must be
     * unique: with {@code forkCount > 1} or {@code reuseForks=false} each fork writes
     * into the SAME directory and {@code qf collect} merges every file it finds, so a
     * collision would lose a fork's results. Within a JVM it must be STABLE, because the
     * report is now written more than once -- from {@code onExecutionFinish}, and again
     * from the shutdown hook -- and a fresh name each time would leave a trail of partial
     * reports for collect to merge into duplicate cases.
     *
     * <p>Stable plus unique means: compute it once, from the pid and a random suffix.
     */
    private static final String FILE_NAME = String.format("qualflare-testng-%d-%d-%d.json",
            ProcessHandle.current().pid(),
            System.currentTimeMillis(),
            ThreadLocalRandom.current().nextInt(100000));

    static Path write(Collection<CaseRecord> cases) throws IOException {
        Path dir = Paths.get(Config.outputDir());
        Files.createDirectories(dir);

        Path file = dir.resolve(FILE_NAME);
        Files.write(file, render(cases).getBytes(StandardCharsets.UTF_8));
        System.out.println("[qualflare-testng] wrote " + cases.size() + " case(s) to " + file);

        // Anomalies go to stderr, where a build log will actually show them. Replay
        // records these and nothing used to read them, so a user whose steps were
        // truncated was never told -- the report just quietly had fewer steps than the
        // test emitted.
        for (CaseRecord c : cases) {
            for (String w : c.meta.warnings) {
                System.err.println("[qualflare-testng] " + c.displayName + ": " + w);
            }
        }
        return file;
    }

    static String render(Collection<CaseRecord> cases) {
        Map<String, List<CaseRecord>> bySuite = new LinkedHashMap<>();
        for (CaseRecord c : cases) {
            bySuite.computeIfAbsent(c.suiteName, k -> new ArrayList<>()).add(c);
        }

        StringBuilder suites = new StringBuilder("[");
        boolean firstSuite = true;
        for (Map.Entry<String, List<CaseRecord>> e : bySuite.entrySet()) {
            if (!firstSuite) {
                suites.append(',');
            }
            firstSuite = false;
            suites.append(renderSuite(e.getKey(), e.getValue()));
        }
        suites.append(']');

        String metadata = Json.object()
                .field("version", Version.VALUE)
                .field("timestamp", Instant.now().toString())
                .field("cliName", "qualflare-testng")
                .end();

        return Json.object()
                .field("framework", "testng")
                .field("platform", Config.platform())
                .field("os", System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""))
                .field("browser", "")
                .field("environment", Config.environment())
                .field("language", Config.language())
                .raw("metadata", metadata)
                // Explicit nulls, not omissions: the wire contract distinguishes "not
                // detected" from "absent", and the server treats a missing key differently.
                .nullableField("branch", Config.branch())
                .nullableField("commit", Config.commit())
                .raw("milestone", "null")
                .raw("suites", suites.toString())
                .end();
    }

    private static String renderSuite(String suiteName, List<CaseRecord> cases) {
        long total = 0L;
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            CaseRecord c = cases.get(i);
            total += c.durationNanos();
            arr.append(renderCase(c));
        }
        arr.append(']');

        return Json.object()
                .field("name", suiteName)
                .field("duration", total)
                // "unit" everywhere: TestNG runs unit, integration and E2E suites alike,
                // and the framework cannot tell them apart. Guessing from the runner
                // would mislabel a Selenium suite as a unit test.
                .field("category", "unit")
                .raw("cases", arr.toString())
                .end();
    }

    private static String renderCase(CaseRecord c) {
        Json j = Json.object()
                .field("id", c.uniqueId)
                .field("name", c.displayName)
                .field("status", c.status())
                .field("duration", c.durationNanos());

        if (!c.className.isEmpty()) {
            j.field("className", c.className);
        }
        String msg = c.message();
        if (!msg.isEmpty()) {
            j.field("error", msg);
        }
        if (c.retryCount() > 0) {
            j.field("retryCount", c.retryCount());
        }
        if (c.isFlaky()) {
            j.field("isFlaky", true);
        }
        if (c.attempts.size() >= MIN_ATTEMPTS_TO_SEND) {
            j.raw("attempts", renderAttempts(c));
        }

        CaseMeta m = c.meta;
        if (!m.priority.isEmpty()) {
            j.field("priority", m.priority);
        }
        if (!m.description.isEmpty()) {
            j.field("description", m.description);
        }
        if (!m.tags.isEmpty()) {
            j.raw("tags", strings(m.tags));
        }
        if (!m.labels.isEmpty()) {
            j.raw("labels", rows(m.labels, r ->
                    Json.object().field("name", r[0]).field("value", r[1]).end()));
        }
        if (!m.links.isEmpty()) {
            j.raw("links", rows(m.links, r -> {
                Json k = Json.object().field("url", r[0]).field("type", r[1]);
                if (!r[2].isEmpty()) {
                    k.field("name", r[2]);
                }
                return k.end();
            }));
        }
        if (!m.parameters.isEmpty()) {
            j.raw("properties", renderProperties(m.parameters));
        }
        if (!m.steps.isEmpty()) {
            j.raw("steps", renderSteps(m.steps));
        }
        if (!m.attachments.isEmpty()) {
            j.raw("attachments", renderAttachments(m.attachments));
        }
        return j.end();
    }

    private static String renderAttachments(List<Attachments.Attachment> list) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            Attachments.Attachment a = list.get(i);
            Json j = Json.object().field("name", a.name);
            if (!a.mimeType.isEmpty()) {
                j.field("mimeType", a.mimeType);
            }
            if (a.contentBase64 != null) {
                j.field("content", a.contentBase64);
            }
            if (a.localImagePath != null) {
                j.field("localImagePath", a.localImagePath);
            }
            j.field("fileSize", a.fileSize);
            arr.append(j.end());
        }
        return arr.append(']').toString();
    }

    /** Case-level parameters ride as `properties`, matching the wire's map shape. */
    private static String renderProperties(List<String[]> params) {
        Json j = Json.object();
        for (String[] p : params) {
            // A masked parameter carries no value at all. `masked` is a display hint the
            // server does not act on, so withholding it here is what keeps it secret.
            j.field(p[0], "1".equals(p[2]) ? "" : (p[1] == null ? "" : p[1]));
        }
        return j.end();
    }

    private static String renderSteps(List<Replay.Step> steps) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            Replay.Step s = steps.get(i);
            Json j = Json.object()
                    .field("name", s.name)
                    .field("status", s.status)
                    .field("duration", s.durationNanos);
            if (!s.error.isEmpty()) {
                j.field("error", s.error);
            }
            if (s.parentIndex != null) {
                j.field("parentIndex", s.parentIndex.longValue());
            }
            if (!s.parameters.isEmpty()) {
                j.raw("parameters", rows(s.parameters, r -> {
                    Json k = Json.object().field("name", r[0]);
                    if ("1".equals(r[2])) {
                        k.field("masked", true);
                    } else if (r[1] != null) {
                        k.field("value", r[1]);
                    }
                    return k.end();
                }));
            }
            arr.append(j.end());
        }
        return arr.append(']').toString();
    }

    private static String strings(List<String> values) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(Json.escape(values.get(i)));
        }
        return arr.append(']').toString();
    }

    private static String rows(List<String[]> rows, java.util.function.Function<String[], String> f) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(f.apply(rows.get(i)));
        }
        return arr.append(']').toString();
    }

    private static String renderAttempts(CaseRecord c) {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < c.attempts.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            Attempt a = c.attempts.get(i);
            Json j = Json.object()
                    .field("attempt", i + 1) // 1-based; the server drops anything lower
                    .field("status", a.status)
                    .field("duration", a.durationNanos);
            if (!a.message.isEmpty()) {
                j.field("message", a.message);
            }
            if (!a.trace.isEmpty()) {
                j.field("trace", a.trace);
            }
            arr.append(j.end());
        }
        return arr.append(']').toString();
    }
}
