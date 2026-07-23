package com.dudal.javachat.protocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ProtocolRegistryTest {
    @Test
    public void testAllReleaseVersionsFrom1_8_9To26_2AreAvailable() {
        assertEquals(65, ProtocolRegistry.supportedVersions().size());
        assertSame(ProtocolRegistry.JAVA_26_2, ProtocolRegistry.supportedVersions().get(0));
        assertEquals("java-26.2", ProtocolRegistry.JAVA_26_2.getId());
        assertEquals("Java 26.2", ProtocolRegistry.JAVA_26_2.getDisplayName());
        assertEquals(776, ProtocolRegistry.JAVA_26_2.getProtocolNumber());

        ProtocolSpec oldest = ProtocolRegistry.require("java-1.8.9");
        assertEquals("Java 1.8.9", oldest.getDisplayName());
        assertEquals(47, oldest.getProtocolNumber());
        assertNotNull(ProtocolRegistry.adapterFor("java-1.21.11"));
    }

    @Test
    public void testPatchVersionsCanShareOneWireProtocol() {
        assertEquals(775, ProtocolRegistry.require("java-26.1").getProtocolNumber());
        assertEquals(775, ProtocolRegistry.require("java-26.1.2").getProtocolNumber());
        assertEquals(754, ProtocolRegistry.require("java-1.16.4").getProtocolNumber());
        assertEquals(754, ProtocolRegistry.require("java-1.16.5").getProtocolNumber());
        assertEquals(108, ProtocolRegistry.require("java-1.9.1").getProtocolNumber());
        assertEquals(109, ProtocolRegistry.require("java-1.9.1")
                .getTranslationProtocolNumber());
    }

    @Test
    public void testUnknownVersionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRegistry.adapterFor("java-1.7.10"));
    }

    @Test
    public void autoIsSelectableButNotAConnectionProtocol() {
        assertSame(ProtocolRegistry.AUTO, ProtocolRegistry.selectableVersions().get(0));
        assertEquals(66, ProtocolRegistry.selectableVersions().size());
        assertEquals(65, ProtocolRegistry.supportedVersions().size());
        assertTrue(ProtocolRegistry.isAuto("auto"));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRegistry.adapterFor(ProtocolRegistry.AUTO));
    }

    @Test
    public void detectsExactPatchVersionWhenProtocolsAreShared() {
        assertEquals("java-26.1.1",
                ProtocolRegistry.detect(775, "Paper 26.1.1").orElseThrow().getId());
        assertEquals("java-1.21.9",
                ProtocolRegistry.detect(773, "1.21.9").orElseThrow().getId());
        assertEquals("java-1.16.5",
                ProtocolRegistry.detect(754, "알 수 없음").orElseThrow().getId());
        assertTrue(ProtocolRegistry.detect(9999, "미래 버전").isEmpty());
    }

    @Test
    public void advertisedMultiVersionRangeTakesPriorityOverEchoedProtocol() {
        assertEquals("java-1.8.9",
                ProtocolRegistry.detect(776, "Requires MC 1.8 / 1.21")
                        .orElseThrow()
                        .getId());
    }
}
