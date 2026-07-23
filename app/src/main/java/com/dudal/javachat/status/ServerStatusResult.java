package com.dudal.javachat.status;

import java.util.Arrays;

public final class ServerStatusResult {
    private final boolean online;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final long latencyMs;
    private final String versionName;
    private final byte[] iconPng;

    private ServerStatusResult(boolean online, int onlinePlayers, int maxPlayers,
                               long latencyMs, String versionName, byte[] iconPng) {
        this.online = online;
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
        this.latencyMs = latencyMs;
        this.versionName = versionName;
        this.iconPng = iconPng == null ? null : Arrays.copyOf(iconPng, iconPng.length);
    }

    public static ServerStatusResult online(int onlinePlayers, int maxPlayers,
                                            long latencyMs, String versionName) {
        return online(onlinePlayers, maxPlayers, latencyMs, versionName, null);
    }

    public static ServerStatusResult online(int onlinePlayers, int maxPlayers,
                                            long latencyMs, String versionName, byte[] iconPng) {
        return new ServerStatusResult(
                true, onlinePlayers, maxPlayers, latencyMs, versionName, iconPng);
    }

    public static ServerStatusResult offline() {
        return new ServerStatusResult(false, 0, 0, -1, null, null);
    }

    public boolean isOnline() { return online; }
    public int getOnlinePlayers() { return onlinePlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public long getLatencyMs() { return latencyMs; }
    public String getVersionName() { return versionName; }
    public byte[] getIconPng() {
        return iconPng == null ? null : Arrays.copyOf(iconPng, iconPng.length);
    }
}
