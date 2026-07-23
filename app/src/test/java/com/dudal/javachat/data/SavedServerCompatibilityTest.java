package com.dudal.javachat.data;

import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SavedServerCompatibilityTest {
    @Test
    public void newServersDefaultToAutomaticVersionDetection() {
        assertEquals("auto", SavedServer.createDefault().getVersionId());
    }

    @Test
    public void readsLegacyServerAndDropsPerServerAuthWhenSavedAgain() {
        String legacy = "{"
                + "\"id\":\"legacy\","
                + "\"name\":\"테스트\","
                + "\"host\":\"example.com\","
                + "\"port\":25565,"
                + "\"versionId\":\"java-26.2\","
                + "\"authMode\":\"OFFLINE\","
                + "\"offlineNickname\":\"OldPlayer\""
                + "}";

        Gson gson = new Gson();
        SavedServer server = gson.fromJson(legacy, SavedServer.class);

        assertEquals("legacy", server.getId());
        assertEquals("example.com", server.getHost());
        String current = gson.toJson(server);
        assertFalse(current.contains("authMode"));
        assertFalse(current.contains("offlineNickname"));
    }
}
