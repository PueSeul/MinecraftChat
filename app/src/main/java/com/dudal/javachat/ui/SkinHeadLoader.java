package com.dudal.javachat.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.dudal.javachat.protocol.PlayerView;
import com.dudal.javachat.protocol.ProfileSkin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SkinHeadLoader implements AutoCloseable {
    private static final int MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 512 * 1024;
    private static final int MAX_SKIN_DIMENSION = 2048;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache;
    private final Map<String, List<WeakReference<ImageView>>> waiting = new HashMap<>();
    private final int targetPixels;
    private final Bitmap fallback;
    private volatile boolean closed;

    public SkinHeadLoader(Context context) {
        int cacheKb = Math.max(1024,
                Math.min(8 * 1024, (int) (Runtime.getRuntime().maxMemory() / 1024 / 16)));
        cache = new LruCache<>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getByteCount() / 1024);
            }
        };
        targetPixels = UiKit.dp(context, 32);
        fallback = createFallback(targetPixels);
    }

    public void load(PlayerView player, ImageView target) {
        String url = player.getSkinUrl();
        String profileName = player.getProfileName();
        String source = url != null ? url : lookupKey(profileName);
        String key = source == null ? null
                : source + (player.isShowHat() ? "#hat" : "#base");
        target.setTag(key);
        target.setImageBitmap(fallback);
        target.setContentDescription(player.getName() + " 스킨 머리");
        target.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (key == null || closed) {
            return;
        }

        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        List<WeakReference<ImageView>> targets = waiting.get(key);
        if (targets != null) {
            targets.add(new WeakReference<>(target));
            return;
        }
        targets = new ArrayList<>();
        targets.add(new WeakReference<>(target));
        waiting.put(key, targets);

        boolean showHat = player.isShowHat();
        downloadExecutor.execute(() -> {
            String resolvedUrl = url != null ? url : resolveSkinUrl(profileName);
            Bitmap head = resolvedUrl == null
                    ? null : downloadHead(resolvedUrl, showHat, targetPixels);
            mainHandler.post(() -> finish(key, head));
        });
    }

    private void finish(String key, Bitmap head) {
        List<WeakReference<ImageView>> targets = waiting.remove(key);
        if (closed || targets == null) {
            return;
        }
        Bitmap result = head == null ? fallback : head;
        cache.put(key, result);
        for (WeakReference<ImageView> reference : targets) {
            ImageView target = reference.get();
            if (target != null && key.equals(target.getTag())) {
                target.setImageBitmap(result);
            }
        }
    }

    private static String lookupKey(String profileName) {
        if (profileName == null || !profileName.matches("[A-Za-z0-9_]{1,16}")) {
            return null;
        }
        return "profile:" + profileName.toLowerCase(Locale.ROOT);
    }

    private static String resolveSkinUrl(String profileName) {
        if (lookupKey(profileName) == null) {
            return null;
        }
        try {
            JsonObject lookup = requestJson(
                    "https://api.mojang.com/users/profiles/minecraft/" + profileName,
                    64 * 1024);
            if (lookup == null || !lookup.has("id")) {
                return null;
            }
            String id = lookup.get("id").getAsString();
            if (!id.matches("[0-9a-fA-F]{32}")) {
                return null;
            }
            JsonObject session = requestJson(
                    "https://sessionserver.mojang.com/session/minecraft/profile/"
                            + id + "?unsigned=false",
                    MAX_JSON_BYTES);
            if (session == null || !session.has("properties")) {
                return null;
            }
            JsonArray properties = session.getAsJsonArray("properties");
            for (JsonElement element : properties) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(property.get("name").getAsString())) {
                    continue;
                }
                byte[] decoded = Base64.getDecoder().decode(
                        property.get("value").getAsString());
                if (decoded.length > MAX_JSON_BYTES) {
                    return null;
                }
                JsonObject payload = JsonParser.parseString(
                        new String(decoded, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject textures = payload.getAsJsonObject("textures");
                if (textures == null || !textures.has("SKIN")) {
                    return null;
                }
                JsonObject skin = textures.getAsJsonObject("SKIN");
                return skin == null || !skin.has("url")
                        ? null : ProfileSkin.safeUrl(skin.get("url").getAsString());
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject requestJson(String url, int maxBytes) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(7_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "JavaChat/1.0 Android");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            byte[] data = readLimited(connection, maxBytes);
            return data == null ? null : JsonParser.parseString(
                    new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readLimited(HttpURLConnection connection, int maxBytes)
            throws Exception {
        int contentLength = connection.getContentLength();
        if (contentLength > maxBytes) {
            return null;
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     contentLength > 0 ? contentLength : Math.min(32 * 1024, maxBytes))) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    return null;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Bitmap downloadHead(String url, boolean showHat, int targetPixels) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(7_000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "JavaChat/1.0 Android");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            byte[] encoded = readLimited(connection, MAX_DOWNLOAD_BYTES);
            if (encoded == null) {
                return null;
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
            if (!validDimensions(bounds.outWidth, bounds.outHeight)) {
                return null;
            }
            Bitmap skin = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
            if (skin == null) {
                return null;
            }
            try {
                return cropHead(skin, showHat, targetPixels);
            } finally {
                skin.recycle();
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean validDimensions(int width, int height) {
        if (width < 64 || width > MAX_SKIN_DIMENSION || width % 64 != 0) {
            return false;
        }
        int scale = width / 64;
        return height >= 32 * scale && height <= MAX_SKIN_DIMENSION;
    }

    private static Bitmap cropHead(Bitmap skin, boolean showHat, int targetPixels) {
        int scale = skin.getWidth() / 64;
        Bitmap head = Bitmap.createBitmap(
                targetPixels, targetPixels, Bitmap.Config.ARGB_8888);
        head.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(head);
        Paint paint = new Paint();
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        paint.setDither(false);
        Rect output = new Rect(0, 0, targetPixels, targetPixels);
        canvas.drawBitmap(skin,
                new Rect(8 * scale, 8 * scale, 16 * scale, 16 * scale),
                output, paint);
        if (showHat) {
            canvas.drawBitmap(skin,
                    new Rect(40 * scale, 8 * scale, 48 * scale, 16 * scale),
                    output, paint);
        }
        return head;
    }

    private static Bitmap createFallback(int targetPixels) {
        Bitmap pixels = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        int skin = Color.rgb(181, 133, 99);
        int hair = Color.rgb(74, 47, 36);
        int eye = Color.rgb(52, 101, 122);
        pixels.eraseColor(skin);
        for (int x = 0; x < 8; x++) {
            pixels.setPixel(x, 0, hair);
            if (x < 2 || x > 5) {
                pixels.setPixel(x, 1, hair);
            }
        }
        pixels.setPixel(1, 2, hair);
        pixels.setPixel(6, 2, hair);
        pixels.setPixel(2, 3, eye);
        pixels.setPixel(5, 3, eye);
        pixels.setPixel(3, 6, Color.rgb(126, 79, 63));
        pixels.setPixel(4, 6, Color.rgb(126, 79, 63));
        Bitmap bitmap = Bitmap.createScaledBitmap(
                pixels, targetPixels, targetPixels, false);
        pixels.recycle();
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        return bitmap;
    }

    @Override
    public void close() {
        closed = true;
        waiting.clear();
        downloadExecutor.shutdownNow();
    }
}
