package com.dudal.javachat.update;

import com.dudal.javachat.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class GitHubUpdateChecker {
    static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/PueSeul/JavaChat-Android/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/PueSeul/JavaChat-Android/releases";
    private static final String EXPECTED_RELEASE_PATH =
            "/PueSeul/JavaChat-Android/releases/";
    private static final int TIMEOUT_MS = 10_000;
    private static final long MAX_APK_BYTES = 300L * 1024L * 1024L;
    private final Gson gson = new Gson();

    public ReleaseInfo fetchLatest() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API)
                .openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        connection.setRequestProperty("User-Agent",
                "JavaChat/" + BuildConfig.VERSION_NAME + " Android");
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new IOException("아직 공개된 정식 릴리스가 없습니다.");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub 응답 오류 (HTTP " + status + ")");
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         input, StandardCharsets.UTF_8))) {
                return parseResponse(reader);
            }
        } finally {
            connection.disconnect();
        }
    }

    ReleaseInfo parseResponse(BufferedReader reader) throws IOException {
        try {
            ReleaseResponse response = gson.fromJson(reader, ReleaseResponse.class);
            if (response == null || response.tagName == null || response.tagName.isBlank()) {
                throw new IOException("GitHub 릴리스에 버전 정보가 없습니다.");
            }
            if (!isTrustedReleaseUrl(response.htmlUrl)) {
                throw new IOException("GitHub 릴리스 주소를 확인할 수 없습니다.");
            }
            return new ReleaseInfo(response.tagName.trim(), response.name,
                    response.body, response.htmlUrl,
                    selectApkAsset(response.tagName.trim(), response.assets));
        } catch (JsonParseException error) {
            throw new IOException("GitHub 릴리스 응답을 읽을 수 없습니다.", error);
        }
    }

    static boolean isTrustedReleaseUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().startsWith(EXPECTED_RELEASE_PATH);
        } catch (URISyntaxException error) {
            return false;
        }
    }

    private static ApkAsset selectApkAsset(String tagName, ReleaseAsset[] assets)
            throws IOException {
        String expectedName = "JavaChat-" + tagName + ".apk";
        if (assets != null) {
            for (ReleaseAsset asset : assets) {
                if (asset != null && expectedName.equals(asset.name)
                        && isTrustedAssetUrl(asset.browserDownloadUrl)) {
                    if (asset.size <= 0 || asset.size > MAX_APK_BYTES) {
                        throw new IOException("업데이트 APK 크기가 올바르지 않습니다.");
                    }
                    return new ApkAsset(asset.browserDownloadUrl, asset.digest, asset.size);
                }
            }
        }
        throw new IOException("GitHub 릴리스에서 업데이트 APK를 찾을 수 없습니다.");
    }

    static boolean isTrustedAssetUrl(String value) {
        if (value == null) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().startsWith(EXPECTED_RELEASE_PATH + "download/");
        } catch (URISyntaxException error) {
            return false;
        }
    }

    public File downloadApk(ReleaseInfo release, File destination,
                            ProgressListener listener) throws IOException {
        ApkAsset asset = release.apkAsset;
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("업데이트 임시 폴더를 만들 수 없습니다.");
        }
        File temporary = new File(parent, destination.getName() + ".part");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("이전 업데이트 임시 파일을 지울 수 없습니다.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(asset.downloadUrl)
                .openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent",
                "JavaChat/" + BuildConfig.VERSION_NAME + " Android");
        boolean complete = false;
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("APK 다운로드 오류 (HTTP " + status + ")");
            }
            if (!isTrustedDownloadDestination(connection.getURL())) {
                throw new IOException("APK 다운로드 주소를 신뢰할 수 없습니다.");
            }
            MessageDigest sha256 = sha256();
            long downloaded = 0;
            int lastProgress = -1;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    downloaded += read;
                    if (downloaded > MAX_APK_BYTES || downloaded > asset.size) {
                        throw new IOException("다운로드한 APK 크기가 올바르지 않습니다.");
                    }
                    output.write(buffer, 0, read);
                    sha256.update(buffer, 0, read);
                    int progress = (int) Math.min(100, downloaded * 100 / asset.size);
                    if (listener != null && progress != lastProgress) {
                        lastProgress = progress;
                        listener.onProgress(progress);
                    }
                }
                output.getFD().sync();
            }
            if (downloaded != asset.size) {
                throw new IOException("다운로드한 APK 크기가 릴리스 정보와 다릅니다.");
            }
            verifyDigest(asset.digest, sha256.digest());
            if (destination.exists() && !destination.delete()) {
                throw new IOException("이전 업데이트 APK를 교체할 수 없습니다.");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("업데이트 APK를 준비할 수 없습니다.");
            }
            complete = true;
            return destination;
        } finally {
            connection.disconnect();
            if (!complete && temporary.exists()) {
                // A partial APK must never be exposed to the package installer.
                temporary.delete();
            }
        }
    }

    private static boolean isTrustedDownloadDestination(URL url) {
        String host = url.getHost();
        return "https".equalsIgnoreCase(url.getProtocol())
                && host != null
                && (host.equalsIgnoreCase("github.com")
                || host.toLowerCase(java.util.Locale.ROOT).endsWith(".githubusercontent.com"));
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 검사를 사용할 수 없습니다.", error);
        }
    }

    private static void verifyDigest(String expected, byte[] actual) throws IOException {
        if (expected == null || expected.isBlank()) {
            return;
        }
        String prefix = "sha256:";
        if (!expected.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new IOException("지원하지 않는 APK 해시 형식입니다.");
        }
        StringBuilder hex = new StringBuilder(actual.length * 2);
        for (byte value : actual) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        if (!hex.toString().equalsIgnoreCase(expected.substring(prefix.length()))) {
            throw new IOException("다운로드한 APK의 SHA-256 해시가 일치하지 않습니다.");
        }
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static final class ReleaseInfo {
        private final String tagName;
        private final String name;
        private final String notes;
        private final String htmlUrl;
        private final ApkAsset apkAsset;

        private ReleaseInfo(String tagName, String name, String notes, String htmlUrl,
                            ApkAsset apkAsset) {
            this.tagName = tagName;
            this.name = name;
            this.notes = notes;
            this.htmlUrl = htmlUrl;
            this.apkAsset = apkAsset;
        }

        public String getTagName() {
            return tagName;
        }

        public String getName() {
            return name;
        }

        public String getNotes() {
            return notes;
        }

        public String getHtmlUrl() {
            return htmlUrl;
        }
    }

    private static final class ApkAsset {
        private final String downloadUrl;
        private final String digest;
        private final long size;

        private ApkAsset(String downloadUrl, String digest, long size) {
            this.downloadUrl = downloadUrl;
            this.digest = digest;
            this.size = size;
        }
    }

    private static final class ReleaseResponse {
        @com.google.gson.annotations.SerializedName("tag_name")
        private String tagName;
        private String name;
        private String body;
        @com.google.gson.annotations.SerializedName("html_url")
        private String htmlUrl;
        private ReleaseAsset[] assets;
    }

    private static final class ReleaseAsset {
        private String name;
        @com.google.gson.annotations.SerializedName("browser_download_url")
        private String browserDownloadUrl;
        private String digest;
        private long size;
    }
}
