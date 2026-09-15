package com.qualflare.testng;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the flat, ordered stream of report entries into the nested shape the wire wants.
 *
 * <p>Steps arrive as start/stop pairs in emission order, with no ids -- nesting is implied
 * purely by ordering, exactly as in the pytest and Go reporters. A stack reconstructs it.
 */
final class Replay {

    /**
     * The client stops at 300 steps per attempt. The server's hard cap is 1000; stopping
     * lower keeps one pathological test from crowding out the rest of the report.
     */
    static final int MAX_STEPS_PER_ATTEMPT = 300;

    private Replay() {}

    static void apply(CaseMeta meta, List<String[]> entries) {
        // -1 is a SENTINEL for a dropped step, not a bare skip. Once the cap is hit the
        // dropped step's matching stop still arrives, and with nothing to pop it would
        // close whichever step is legitimately open -- overwriting that step's status and
        // duration, then discarding its real stop because the stack is empty. Measured in
        // the pytest reporter as an outer step around a 50ms sleep reporting 0.000ms.
        List<Integer> open = new ArrayList<>();
        boolean dropped = false;

        for (String[] e : entries) {
            String key = e[0];
            String value = e[1];

            switch (key) {
                case Keys.LABEL: {
                    String[] p = split(value, 2);
                    meta.labels.add(new String[]{p[0], p[1]});
                    break;
                }
                case Keys.TAG:
                    meta.tags.add(value);
                    break;
                case Keys.LINK: {
                    String[] p = split(value, 3);
                    meta.links.add(new String[]{p[0], p[1], p[2]});
                    break;
                }
                case Keys.PRIORITY:
                    meta.priority = value;
                    break;
                case Keys.DESCRIPTION:
                    meta.description = value;
                    break;
                case Keys.PARAMETER: {
                    String[] p = split(value, 2);
                    addParameter(meta, open, p[0], p[1], false);
                    break;
                }
                case Keys.MASKED_PARAMETER:
                    addParameter(meta, open, value, null, true);
                    break;
                case Keys.STEP_START: {
                    if (meta.steps.size() >= MAX_STEPS_PER_ATTEMPT) {
                        dropped = true;
                        open.add(-1);
                        break;
                    }
                    Step s = new Step(value);
                    s.parentIndex = parentOf(open);
                    meta.steps.add(s);
                    open.add(meta.steps.size() - 1);
                    break;
                }
                case Keys.STEP_STOP: {
                    if (open.isEmpty()) {
                        break; // a stop with no start: ignore rather than corrupt the tree
                    }
                    int idx = open.remove(open.size() - 1);
                    if (idx < 0) {
                        break; // the sentinel: a dropped step's stop closes nothing real
                    }
                    String[] p = split(value, 3);
                    Step s = meta.steps.get(idx);
                    s.status = p[0].isEmpty() ? Status.PASSED : p[0];
                    s.durationNanos = parseLong(p[1]);
                    s.error = p[2];
                    break;
                }
                default:
                    break;
            }
        }

        if (dropped) {
            meta.warnings.add("a test produced more than " + MAX_STEPS_PER_ATTEMPT
                    + " steps; the rest were dropped");
        }
    }

    /** A parameter inside an open step belongs to that step, not to the case. */
    private static void addParameter(CaseMeta meta, List<Integer> open,
                                     String name, String value, boolean masked) {
        Integer parent = parentOf(open);
        String[] row = new String[]{name, masked ? null : value, masked ? "1" : ""};
        if (parent == null) {
            meta.parameters.add(row);
        } else {
            meta.steps.get(parent).parameters.add(row);
        }
    }

    /** The innermost REAL open step; a dropped one must not become a parent. */
    private static Integer parentOf(List<Integer> open) {
        for (int i = open.size() - 1; i >= 0; i--) {
            int idx = open.get(i);
            if (idx >= 0) {
                return idx;
            }
        }
        return null;
    }

    /**
     * Split with a limit, so a value containing the separator cannot shift later fields.
     * Missing trailing fields come back as empty rather than throwing.
     */
    private static String[] split(String value, int fields) {
        String[] parts = value.split(Keys.SEP, fields);
        String[] out = new String[fields];
        for (int i = 0; i < fields; i++) {
            out[i] = i < parts.length ? parts[i] : "";
        }
        return out;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** One step in the replayed tree. */
    static final class Step {
        final String name;
        String status = Status.PASSED;
        long durationNanos;
        String error = "";
        Integer parentIndex;
        final List<String[]> parameters = new ArrayList<>();

        Step(String name) {
            this.name = name;
        }
    }
}
