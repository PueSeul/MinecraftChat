package com.dudal.javachat.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftPlayerCertificates;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.java.request.MinecraftPlayerCertificatesRequest;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.util.http.content.JsonContent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MicrosoftAuthRepository {
    private static final String SESSION_KEY = "microsoft_session";
    private static final String META_PREFS = "auth_meta";
    private static final String ACCOUNT_NAME = "account_name";
    private static final String USER_AGENT = "JavaChat/1.2 Android";

    private final SecureStore secureStore;
    private final SharedPreferences metadata;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public MicrosoftAuthRepository(Context context) {
        Context appContext = context.getApplicationContext();
        secureStore = new SecureStore(appContext);
        metadata = appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasAccount() {
        return secureStore.contains(SESSION_KEY);
    }

    public String getAccountName() {
        return metadata.getString(ACCOUNT_NAME, null);
    }

    public void login(LoginCallback callback) {
        executor.execute(() -> {
            try {
                Consumer<MsaDeviceCode> deviceCodeConsumer = callback::onDeviceCode;
                JavaAuthManager manager = JavaAuthManager
                        .create(MinecraftAuth.createHttpClient(USER_AGENT))
                        .login(
                                (httpClient, config, consumer) ->
                                        new DeviceCodeMsaAuthService(httpClient, config, consumer),
                                deviceCodeConsumer);
                OnlineIdentity identity = resolveIdentity(manager);
                save(manager, identity.getProfileName());
                callback.onSuccess(identity.getProfileName());
            } catch (Throwable error) {
                callback.onError(error);
            }
        });
    }

    public OnlineIdentity requireIdentity() throws Exception {
        String json = secureStore.get(SESSION_KEY);
        if (json == null) {
            throw new IllegalStateException("Microsoft 계정 로그인이 필요합니다.");
        }
        JavaAuthManager manager = JavaAuthManager.fromJson(
                MinecraftAuth.createHttpClient(USER_AGENT),
                JsonParser.parseString(json).getAsJsonObject());
        OnlineIdentity identity = resolveIdentity(manager);
        save(manager, identity.getProfileName());
        return identity;
    }

    public void logout() {
        secureStore.remove(SESSION_KEY);
        metadata.edit().remove(ACCOUNT_NAME).apply();
    }

    public void close() {
        executor.shutdownNow();
    }

    private OnlineIdentity resolveIdentity(JavaAuthManager manager) throws Exception {
        MinecraftProfile profile = manager.getMinecraftProfile().getUpToDate();
        MinecraftToken token = manager.getMinecraftToken().getUpToDate();
        MinecraftPlayerCertificates certificates = manager.getMinecraftPlayerCertificates().getCached();
        if (certificates == null || certificates.isExpired()) {
            MinecraftPlayerCertificatesRequest request = certificateRequest(token);
            certificates = manager.getHttpClient().executeAndHandle(request);
            manager.getMinecraftPlayerCertificates().set(certificates);
        }
        return new OnlineIdentity(profile.getId(), profile.getName(), token.getToken(), certificates);
    }

    static MinecraftPlayerCertificatesRequest certificateRequest(MinecraftToken token)
            throws Exception {
        MinecraftPlayerCertificatesRequest request = new MinecraftPlayerCertificatesRequest(token);
        request.setContent(new JsonContent(new JsonObject()));
        return request;
    }

    private void save(JavaAuthManager manager, String accountName) throws Exception {
        secureStore.put(SESSION_KEY, JavaAuthManager.toJson(manager).toString());
        metadata.edit().putString(ACCOUNT_NAME, accountName).apply();
    }

    public interface LoginCallback {
        void onDeviceCode(MsaDeviceCode code);
        void onSuccess(String profileName);
        void onError(Throwable error);
    }
}
