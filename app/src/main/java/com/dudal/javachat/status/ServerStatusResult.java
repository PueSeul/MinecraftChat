package com.dudal.javachat.status;

import java.util.Arrays;

public final class ServerStatusResult {
    private final boolean online;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final long latencyMs;
    private final String versionName;
    private final int protocolVersion;
    private final byte[] iconPng;

    private ServerStatusResult(boolean online, int onlinePlayers, int maxPlayers,
                               long latencyMs, String versionName, int protocolVersion,
                               byte[] iconPng) {
        this.online = online;
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
        this.latencyMs = latencyMs;
        this.versionName = versionName;
        this.protocolVersion = protocolVersion;
        this.iconPng = iconPng == null ? null : Arrays.copyOf(iconPng, iconPng.length);
    }

    public static ServerStatusResult online(int onlinePlayers, int maxPlayers,
                                            long latencyMs, String versionName) {
        return online(onlinePlayers, maxPlayers, latencyMs, versionName, -1, null);
    }

    public static ServerStatusResult online(int onlinePlayers, int maxPlayers,
                                            long latencyMs, String versionName, byte[] iconPng) {
        return online(onlinePlayers, maxPlayers, latencyMs, versionName, -1, iconPng);
    }

    public static ServerStatusResult online(int onlinePlayers, int maxPlayers,
                                            long latencyMs, String versionName,
                                            int protocolVersion, byte[] iconPng) {
        return new ServerStatusResult(
                true, onlinePlayers, maxPlayers, latencyMs, versionName,
                protocolVersion, iconPng);
    }

    public static ServerStatusResult offline() {
        return new ServerStatusResult(false, 0, 0, -1, null, -1, null);
    }

    public boolean isOnline() { return online; }
    public int getOnlinePlayers() { return onlinePlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public long getLatencyMs() { return latencyMs; }
    public String getVersionName() { return versionName; }
    public int getProtocolVersion() { return protocolVersion; }
    public byte[] getIconPng() {
        return iconPng == null ? null : Arrays.copyOf(iconPng, iconPng.length);
    }
}
