package com.qualflare.testng;

/**
 * Configuration, resolved once per JVM.
 *
 * <p>Precedence, highest first: system property, {@code QUALFLARE_*} environment variable,
 * default. The same order the other eight reporters document, so one mental model covers
 * every package. There is deliberately no token option and no config file: this reporter
 * makes no network calls, so it has no credential to hold. {@code qf login} owns that.
 */
final class Config {

    static final String DEFAULT_OUTPUT_DIR = "qualflare-results";

    private Config() {}

    static String outputDir() {
        return resolve("qualflare.outputDir", "QUALFLARE_OUTPUT_DIR", DEFAULT_OUTPUT_DIR);
    }

    static String environment() {
        // Matched against the environment's uid (slug), not its display name -- "Staging"
        // in the UI is "staging" here. Worth knowing because it fails LATE: the reporter
        // makes no requests, so a wrong value cannot fail the test run; the run goes
        // green and `qf collect` 404s afterwards.
        return resolve("qualflare.environment", "QUALFLARE_ENVIRONMENT", "development");
    }

    static String language() {
        return resolve("qualflare.language", "QUALFLARE_LANGUAGE", "en-US");
    }

    static String platform() {
        return resolve("qualflare.platform", "QUALFLARE_PLATFORM", "api");
    }

    static boolean enabled() {
        String raw = resolve("qualflare.enabled", "QUALFLARE_ENABLED", "true");
        switch (raw.trim().toLowerCase()) {
            case "0": case "false": case "no": case "off":
                return false;
            default:
                return true;
        }
    }

    /** Null rather than empty: the wire wants an explicit null, never a guess. */
    static String branch() {
        return nullable("qualflare.branch", "QUALFLARE_BRANCH");
    }

    static String commit() {
        return nullable("qualflare.commit", "QUALFLARE_COMMIT");
    }

    private static String resolve(String property, String env, String fallback) {
        String v = System.getProperty(property);
        if (isSet(v)) {
            return v;
        }
        v = System.getenv(env);
        return isSet(v) ? v : fallback;
    }

    private static String nullable(String property, String env) {
        String v = System.getProperty(property);
        if (isSet(v)) {
            return v;
        }
        v = System.getenv(env);
        return isSet(v) ? v : null;
    }

    /**
     * An empty value falls through rather than winning. A declared-but-unset property is
     * the common case in build files, and treating "" as a choice would override the
     * environment with nothing.
     */
    private static boolean isSet(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
