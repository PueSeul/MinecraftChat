package com.dudal.javachat.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.UUID;

import org.junit.Test;

public final class PlayerViewTest {
    @Test
    public void equalSnapshotsCanSuppressRedundantUiUpdates() {
        UUID id = UUID.randomUUID();
        PlayerView first = new PlayerView(id, "PueSeul", 42, "https://textures.example/skin",
                true, "PueSeul");
        PlayerView same = new PlayerView(id, "PueSeul", 42, "https://textures.example/skin",
                true, "PueSeul");
        PlayerView changedLatency = new PlayerView(id, "PueSeul", 43,
                "https://textures.example/skin", true, "PueSeul");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, changedLatency);
    }
}
