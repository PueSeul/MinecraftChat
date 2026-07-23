package com.dudal.javachat.status;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerStatusResultTest {
    @Test
    public void testOnlineAndOfflineResults() {
        ServerStatusResult online = ServerStatusResult.online(3, 20, 42, "26.2");
        assertTrue(online.isOnline());
        assertEquals(3, online.getOnlinePlayers());
        assertEquals(20, online.getMaxPlayers());
        assertEquals(42, online.getLatencyMs());
        assertEquals("26.2", online.getVersionName());

        assertFalse(ServerStatusResult.offline().isOnline());
    }

    @Test
    public void keepsDefensiveCopyOfServerIcon() {
        byte[] icon = {1, 2, 3, 4};
        ServerStatusResult result = ServerStatusResult.online(1, 20, 4, "1.21", icon);
        icon[0] = 9;

        assertArrayEquals(new byte[]{1, 2, 3, 4}, result.getIconPng());
        byte[] returned = result.getIconPng();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result.getIconPng());
    }
}
