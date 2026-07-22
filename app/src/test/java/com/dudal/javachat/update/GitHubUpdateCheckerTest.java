package com.dudal.javachat.update;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GitHubUpdateCheckerTest {
    @Test
    public void parsesExpectedGitHubRelease() throws Exception {
        String json = "{\"tag_name\":\"v1.1\",\"name\":\"Minecraft Chat v1.1\","
                + "\"body\":\"changes\",\"html_url\":"
                + "\"https://github.com/PueSeul/MinecraftChat/releases/tag/v1.1\","
                + "\"assets\":[{\"name\":\"MinecraftChat-v1.1.apk\",\"size\":12345,"
                + "\"browser_download_url\":"
                + "\"https://github.com/PueSeul/MinecraftChat/releases/download/v1.1/MinecraftChat-v1.1.apk\","
                + "\"digest\":\"sha256:0123456789abcdef\"}]}";

        GitHubUpdateChecker.ReleaseInfo result = new GitHubUpdateChecker()
                .parseResponse(new BufferedReader(new StringReader(json)));

        assertEquals("v1.1", result.getTagName());
        assertEquals("Minecraft Chat v1.1", result.getName());
        assertEquals("changes", result.getNotes());
    }

    @Test
    public void onlyAcceptsThisRepositoryReleasePages() {
        assertTrue(GitHubUpdateChecker.isTrustedReleaseUrl(
                "https://github.com/PueSeul/MinecraftChat/releases/tag/v1.1"));
        assertFalse(GitHubUpdateChecker.isTrustedReleaseUrl(
                "https://example.com/PueSeul/MinecraftChat/releases/tag/v1.1"));
        assertFalse(GitHubUpdateChecker.isTrustedReleaseUrl(
                "https://github.com/another/repository/releases/tag/v1.1"));
        assertTrue(GitHubUpdateChecker.isTrustedAssetUrl(
                "https://github.com/PueSeul/MinecraftChat/releases/download/v1.1/MinecraftChat-v1.1.apk"));
        assertFalse(GitHubUpdateChecker.isTrustedAssetUrl(
                "https://github.com/another/repository/releases/download/v1.1/MinecraftChat-v1.1.apk"));
    }
}
