package com.qualflare.testng;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class VersionTest {

    /**
     * Pins the defect qualflare-junit5 0.1.0 shipped: every report claimed 0.0.0-dev
     * because the jar manifest had no Implementation-Version. Running from target/classes
     * there is no manifest at all, so the fallback IS correct here -- what this asserts is
     * that the constant exists, is never null, and is never empty.
     */
    @Test
    public void versionIsAlwaysPresent() {
        assertNotNull(Version.VALUE, "version must never be null");
        assertFalse(Version.VALUE.trim().isEmpty(), "version must never be blank");
    }
}
