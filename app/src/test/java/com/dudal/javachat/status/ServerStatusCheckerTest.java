package com.dudal.javachat.status;

import com.dudal.javachat.data.SavedServer;

import net.kyori.adventure.text.Component;

import org.geysermc.mcprotocollib.network.Server;
import org.geysermc.mcprotocollib.network.server.NetworkServer;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServerStatusCheckerTest {
    @Test
    public void testMinecraftStatusPing() throws Exception {
        int port;
        try (ServerSocket availablePort = new ServerSocket(0)) {
            port = availablePort.getLocalPort();
        }

        Server testServer = new NetworkServer(
                new InetSocketAddress("127.0.0.1", port), MinecraftProtocol::new);
        byte[] icon = {1, 2, 3, 4};
        testServer.setGlobalFlag(MinecraftConstants.SERVER_INFO_BUILDER_KEY,
                session -> new ServerStatusInfo(
                        Component.text("Minecraft Chat test"),
                        new PlayerInfo(20, 3, List.of()),
                        new VersionInfo("26.2", 776),
                        icon,
                        false));
        testServer.bind(true);

        try {
            SavedServer saved = new SavedServer(
                    "test", "test", "127.0.0.1", port,
                    "java-26.2");
            ServerStatusResult result = ServerStatusChecker.query(saved);
            assertTrue(result.isOnline());
            assertEquals(3, result.getOnlinePlayers());
            assertEquals(20, result.getMaxPlayers());
            assertEquals("26.2", result.getVersionName());
            assertEquals(776, result.getProtocolVersion());
            assertArrayEquals(icon, result.getIconPng());
        } finally {
            testServer.close(true);
        }
    }
}
