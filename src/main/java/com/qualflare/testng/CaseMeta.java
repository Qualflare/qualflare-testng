package com.qualflare.testng;

import java.util.ArrayList;
import java.util.List;

/**
 * The author-supplied half of a case: everything JUnit itself has no concept of.
 *
 * <p>Rows are plain arrays rather than small classes on purpose. They exist only between
 * {@link Replay} and {@link ReportWriter}, never cross a public boundary, and a handful of
 * value types would be more ceremony than the two-field pairs justify.
 */
final class CaseMeta {
    /** {name, value} */
    final List<String[]> labels = new ArrayList<>();
    final List<String> tags = new ArrayList<>();
    /** {url, type, name} */
    final List<String[]> links = new ArrayList<>();
    /** {name, value-or-null, "1" when masked} */
    final List<String[]> parameters = new ArrayList<>();
    final List<Replay.Step> steps = new ArrayList<>();
    final List<Attachments.Attachment> attachments = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    String priority = "";
    String description = "";
}
