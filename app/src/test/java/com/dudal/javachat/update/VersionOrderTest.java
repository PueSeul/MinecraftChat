package com.dudal.javachat.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VersionOrderTest {
    @Test
    public void vPrefixAndMissingPatchAreEquivalent() {
        assertEquals(0, VersionOrder.compare("v1.0", "1.0.0"));
    }

    @Test
    public void comparesNumericComponentsInsteadOfAlphabetically() {
        assertTrue(VersionOrder.isNewer("v1.10", "1.9"));
        assertFalse(VersionOrder.isNewer("v1.0", "1.0"));
        assertFalse(VersionOrder.isNewer("v0.9.9", "1.0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonNumericVersions() {
        VersionOrder.compare("latest", "1.0");
    }
}
