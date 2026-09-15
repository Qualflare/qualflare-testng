package com.qualflare.testng;

/** The reporter version, stamped into every report. */
final class Version {

    /**
     * Read from the jar manifest's Implementation-Version, which maven-jar-plugin adds via
     * addDefaultImplementationEntries. Null when running from target/classes (no manifest),
     * which is why the fallback exists -- but it must never be what a RELEASED jar reports.
     */
    static final String VALUE = value();

    private Version() {}

    private static String value() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v == null || v.trim().isEmpty() ? "0.0.0-dev" : v;
    }
}
