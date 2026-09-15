package com.qualflare.testng;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run state for the whole JVM.
 *
 * <p>This class only accumulates; it never writes. Writing lives in {@link Run} and
 * {@link ReportWriter}, triggered from {@code onExecutionFinish} and, as a backstop, the
 * shutdown hook -- both fire at most once per JVM (see {@link Run} for the measured
 * facts), so there is no per-rerun callback here to guard against overwriting or
 * fragmenting the retry history. Retries still accumulate correctly because
 * {@link TestKey} gives a retried method the same key on every {@code IRetryAnalyzer}
 * attempt, all of them in-process in this same JVM.
 *
 * <p>Synchronised rather than merely concurrent-collection based: with TestNG configured
 * for {@code parallel="methods"}, the listener is called from multiple worker threads
 * concurrently, and appending an attempt is a read-modify-write on a case that must not
 * interleave.
 */
final class Accumulator {

    /** Insertion-ordered so the report lists cases in the order they first ran. */
    private final Map<String, CaseRecord> byUniqueId = new LinkedHashMap<>();
    private final Map<String, Long> startedNanos = new LinkedHashMap<>();

    /** Report entries for the attempt currently in flight, keyed by uniqueId. */
    private final Map<String, List<String[]>> pendingEntries = new LinkedHashMap<>();
    private final Map<String, List<Attachments.Attachment>> pendingAttachments = new LinkedHashMap<>();

    synchronized void started(String uniqueId, long nanoTime) {
        startedNanos.put(uniqueId, nanoTime);
        // A rerun starts a fresh attempt, so the previous attempt's entries must not
        // leak into it -- otherwise a retried test accumulates every attempt's steps.
        pendingEntries.remove(uniqueId);
        pendingAttachments.remove(uniqueId);
    }

    /**
     * Attachments land on the case directly rather than waiting for the replay: a file is
     * already resolved by the time it is published, and unlike steps it carries no
     * ordering that needs reconstructing.
     */
    synchronized void attachment(String uniqueId, Attachments.Attachment a) {
        // ALWAYS buffered, never written straight onto the case. On a rerun, finished()
        // replaces rec.meta with a freshly replayed one, so anything attached directly to
        // the previous meta would be silently dropped -- and a screenshot that vanishes
        // only on retried tests is exactly the bug nobody reproduces.
        pendingAttachments.computeIfAbsent(uniqueId, k -> new ArrayList<>()).add(a);
    }

    synchronized void entry(String uniqueId, String key, String value) {
        pendingEntries.computeIfAbsent(uniqueId, k -> new ArrayList<>())
                .add(new String[]{key, value});
    }

    /**
     * @param nanoTime System.nanoTime() at finish; the elapsed time is computed here
     *                 rather than trusted from the caller so a missing start degrades to
     *                 zero instead of a wild negative.
     */
    synchronized void finished(String uniqueId, String suiteName, String className,
                               String displayName, String legacyName,
                               String status, long nanoTime, String message, String trace) {
        Long start = startedNanos.remove(uniqueId);
        long elapsed = start == null ? 0L : Math.max(0L, nanoTime - start);

        CaseRecord rec = byUniqueId.computeIfAbsent(uniqueId,
                id -> new CaseRecord(id, suiteName, className, displayName, legacyName));
        rec.attempts.add(new Attempt(status, elapsed, message, trace));

        // Replace rather than merge: the final attempt's metadata is the case's metadata.
        List<String[]> entries = pendingEntries.remove(uniqueId);
        List<Attachments.Attachment> files = pendingAttachments.remove(uniqueId);
        if (entries != null && !entries.isEmpty()) {
            CaseMeta meta = new CaseMeta();
            Replay.apply(meta, entries);
            rec.meta = meta;
        }
        if (files != null) {
            for (Attachments.Attachment a : files) {
                if (rec.meta.attachments.size() < Attachments.MAX_PER_CASE) {
                    rec.meta.attachments.add(a);
                }
            }
        }
    }

    /**
     * A skip has no duration and no attempt of its own worth recording as a retry -- it
     * never ran. Recorded as a single attempt so the case exists in the report; a skipped
     * test missing entirely would look like a shrinking suite.
     */
    synchronized void skipped(String uniqueId, String suiteName, String className,
                              String displayName, String legacyName, String reason) {
        CaseRecord rec = byUniqueId.computeIfAbsent(uniqueId,
                id -> new CaseRecord(id, suiteName, className, displayName, legacyName));
        if (rec.attempts.isEmpty()) {
            rec.attempts.add(new Attempt(Status.SKIPPED, 0L, reason == null ? "" : reason, ""));
        }
    }

    synchronized Collection<CaseRecord> cases() {
        return new ArrayList<>(byUniqueId.values());
    }

    synchronized boolean isEmpty() {
        return byUniqueId.isEmpty();
    }

    synchronized void clear() {
        byUniqueId.clear();
        startedNanos.clear();
        pendingEntries.clear();
        pendingAttachments.clear();
    }

    /** Test-only: did any metadata arrive for this key? */
    synchronized boolean hasEntriesFor(String uniqueId) {
        return pendingEntries.containsKey(uniqueId);
    }
}
