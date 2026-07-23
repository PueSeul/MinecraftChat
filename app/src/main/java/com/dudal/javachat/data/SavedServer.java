package com.dudal.javachat.data;

import java.util.Objects;
import java.util.UUID;

public final class SavedServer {
    private String id;
    private String name;
    private String host;
    private int port;
    private String versionId;

    public SavedServer() {
        // Gson
    }

    public SavedServer(String id, String name, String host, int port, String versionId) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.port = port;
        this.versionId = versionId;
    }

    public static SavedServer createDefault() {
        return new SavedServer(
                UUID.randomUUID().toString(),
                "새 서버",
                "",
                25565,
                "auto"
        );
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getVersionId() { return versionId; }

    public void setName(String name) { this.name = name; }
    public void setHost(String host) { this.host = host; }
    public void setPort(int port) { this.port = port; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    @Override
    public boolean equals(Object value) {
        return value instanceof SavedServer other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
