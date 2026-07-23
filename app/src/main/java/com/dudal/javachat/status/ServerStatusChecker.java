package com.dudal.javachat.status;

import com.dudal.javachat.data.SavedServer;

import org.geysermc.mcprotocollib.network.BuiltinFlags;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ServerStatusChecker {
    private static final int CONNECT_TIMEOUT_SECONDS = 4;
    private static final int TOTAL_TIMEOUT_SECONDS = 6;

    private ServerStatusChecker() {}

    public static ServerStatusResult query(SavedServer server) {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<ServerStatusInfo> status = new AtomicReference<>();
        AtomicLong latency = new AtomicLong(-1);
        long startedAt = System.nanoTime();

        ClientNetworkSession session = ClientNetworkSessionFactory.factory()
                .setAddress(server.getHost(), server.getPort())
                .setProtocol(new MinecraftProtocol())
                .create();
        session.setFlag(BuiltinFlags.CLIENT_CONNECT_TIMEOUT, CONNECT_TIMEOUT_SECONDS);
        session.setFlag(MinecraftConstants.SERVER_INFO_HANDLER_KEY,
                (active, info) -> status.set(info));
        session.setFlag(MinecraftConstants.SERVER_PING_TIME_HANDLER_KEY,
                (active, pingMs) -> {
                    latency.set(Math.max(0, pingMs));
                    finished.countDown();
                });
        session.addListener(new SessionAdapter() {
            @Override
            public void disconnected(DisconnectedEvent event) {
                finished.countDown();
            }
        });

        try {
            session.connect();
            finished.await(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ServerStatusInfo info = status.get();
            if (info == null) {
                return ServerStatusResult.offline();
            }
            long measuredLatency = latency.get();
            if (measuredLatency < 0) {
                measuredLatency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            }
            PlayerInfo players = info.getPlayerInfo();
            VersionInfo version = info.getVersionInfo();
            return ServerStatusResult.online(
                    players == null ? 0 : players.getOnlinePlayers(),
                    players == null ? 0 : players.getMaxPlayers(),
                    measuredLatency,
                    version == null ? null : version.getVersionName(),
                    version == null ? -1 : version.getProtocolVersion(),
                    info.getIconPng());
        } catch (Throwable ignored) {
            return ServerStatusResult.offline();
        } finally {
            if (session.isConnected()) {
                session.disconnect("Status check finished");
            }
        }
    }
}
