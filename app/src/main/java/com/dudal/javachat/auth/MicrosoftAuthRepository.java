package com.dudal.javachat.auth;

import android.content.Context;
import android.content.SharedPreferences;

import com.dudal.javachat.BuildConfig;
import com.dudal.javachat.util.BackgroundExecutors;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftPlayerCertificates;
import net.raphimc.minecraftauth.java.model.MinecraftProfile;
import net.raphimc.minecraftauth.java.model.MinecraftToken;
import net.raphimc.minecraftauth.java.request.MinecraftPlayerCertificatesRequest;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;
import net.raphimc.minecraftauth.util.http.content.JsonContent;
import net.lenni0451.commons.httpclient.HttpClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class MicrosoftAuthRepository {
    private static final String SESSION_KEY = "microsoft_session";
    private static final String META_PREFS = "auth_meta";
    private static final String ACCOUNT_NAME = "account_name";
    private static final String USER_AGENT =
            "MinecraftChat/" + BuildConfig.VERSION_NAME + " Android";
    private static final long NETWORK_READY_WAIT_MS = 6_000L;

    private final Context appContext;
    private final SecureStore secureStore;
    private final SharedPreferences metadata;
    private final ExecutorService executor =
            BackgroundExecutors.fixed("microsoft-auth", 1);
    private final AtomicLong loginGeneration = new AtomicLong();
    private volatile Future<?> loginTask;

    public MicrosoftAuthRepository(Context context) {
        appContext = context.getApplicationContext();
        secureStore = new SecureStore(appContext);
        metadata = appContext.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasAccount() {
        return secureStore.contains(SESSION_KEY);
    }

    public String getAccountName() {
        return metadata.getString(ACCOUNT_NAME, null);
    }

    public synchronized void login(LoginCallback callback) {
        cancelLogin();
        long generation = loginGeneration.incrementAndGet();
        loginTask = executor.submit(() -> {
            try {
                sendStatus(callback, generation, "네트워크 연결 확인 중…");
                AuthNetworkWaiter.awaitValidated(appContext, NETWORK_READY_WAIT_MS);
                sendStatus(callback, generation, "Microsoft 인증 서버에 연결 중…");
                Consumer<MsaDeviceCode> deviceCodeConsumer = code -> {
                    if (isCurrent(generation)) {
                        callback.onDeviceCode(code);
                    }
                };
                HttpClient authHttpClient = AuthHttpClientFactory.create(
                        USER_AGENT, status -> sendStatus(callback, generation, status));
                JavaAuthManager manager = JavaAuthManager
                        .create(authHttpClient)
                        .login(
                                (httpClient, config, consumer) ->
                                        new DeviceCodeMsaAuthService(httpClient, config, consumer),
                                 deviceCodeConsumer);
                if (!isCurrent(generation)) {
                    return;
                }
                OnlineIdentity identity = resolveIdentity(manager);
                if (!isCurrent(generation)) {
                    return;
                }
                save(manager, identity.getProfileName());
                if (isCurrent(generation)) {
                    callback.onSuccess(identity.getProfileName());
                }
            } catch (Throwable error) {
                if (isCurrent(generation)) {
                    callback.onError(error);
                }
            }
        });
    }

    public synchronized void cancelLogin() {
        loginGeneration.incrementAndGet();
        Future<?> active = loginTask;
        loginTask = null;
        if (active != null) {
            active.cancel(true);
        }
    }

    public OnlineIdentity requireIdentity() throws Exception {
        AuthNetworkWaiter.awaitValidated(appContext, NETWORK_READY_WAIT_MS);
        String json = secureStore.get(SESSION_KEY);
        if (json == null) {
            throw new IllegalStateException("Microsoft 계정 로그인이 필요합니다.");
        }
        JavaAuthManager manager = JavaAuthManager.fromJson(
                AuthHttpClientFactory.create(USER_AGENT, status -> { }),
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
        cancelLogin();
        executor.shutdownNow();
    }

    private boolean isCurrent(long generation) {
        return loginGeneration.get() == generation
                && !Thread.currentThread().isInterrupted();
    }

    private void sendStatus(LoginCallback callback, long generation, String status) {
        if (isCurrent(generation)) {
            callback.onStatus(status);
        }
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
        default void onStatus(String status) {
        }

        void onDeviceCode(MsaDeviceCode code);
        void onSuccess(String profileName);
        void onError(Throwable error);
    }
}
