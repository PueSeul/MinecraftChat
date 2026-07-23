package com.dudal.javachat.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.dudal.javachat.ChatActivity;
import com.dudal.javachat.R;
import com.dudal.javachat.auth.ConnectionIdentity;
import com.dudal.javachat.auth.MicrosoftAuthRepository;
import com.dudal.javachat.auth.OnlineIdentity;
import com.dudal.javachat.data.AuthMode;
import com.dudal.javachat.data.ConnectionSettingsRepository;
import com.dudal.javachat.data.SavedServer;
import com.dudal.javachat.data.ServerRepository;
import com.dudal.javachat.protocol.ChatLine;
import com.dudal.javachat.protocol.CommandSuggestions;
import com.dudal.javachat.protocol.ConnectionListener;
import com.dudal.javachat.protocol.ConnectionState;
import com.dudal.javachat.protocol.PlayerView;
import com.dudal.javachat.protocol.ProtocolConnection;
import com.dudal.javachat.protocol.ProtocolRegistry;
import com.dudal.javachat.protocol.ProtocolSpec;
import com.dudal.javachat.status.ServerStatusChecker;
import com.dudal.javachat.status.ServerStatusResult;
import com.dudal.javachat.util.ErrorText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MinecraftConnectionService extends Service implements ConnectionListener {
    public static final String ACTION_CONNECT = "com.dudal.javachat.CONNECT";
    public static final String ACTION_DISCONNECT = "com.dudal.javachat.DISCONNECT";
    public static final String EXTRA_SERVER_ID = "server_id";
    public static final String EXTRA_DETECTED_VERSION_ID = "detected_version_id";

    private static final String CHANNEL_ID = "minecraft_connection";
    private static final int NOTIFICATION_ID = 262;
    private static final int MAX_CHAT_LINES = 500;

    private final LocalBinder binder = new LocalBinder();
    private final Set<UiListener> uiListeners = new CopyOnWriteArraySet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ChatLine> chatHistory = new ArrayList<>();

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile String stateDetail = "";
    private volatile List<PlayerView> players = Collections.emptyList();
    private volatile ProtocolConnection connection;
    private volatile SavedServer activeServer;
    private volatile boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        if (ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnectAndStop();
            return START_NOT_STICKY;
        }
        if (ACTION_CONNECT.equals(intent.getAction())) {
            stopping = false;
            String serverId = intent.getStringExtra(EXTRA_SERVER_ID);
            String detectedVersionId = intent.getStringExtra(EXTRA_DETECTED_VERSION_ID);
            startForeground(NOTIFICATION_ID, buildNotification("연결 준비 중"));
            connect(serverId, detectedVersionId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        ProtocolConnection active = connection;
        if (active != null) {
            active.disconnect();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    public void addUiListener(UiListener listener) {
        uiListeners.add(listener);
        mainHandler.post(() -> listener.onSnapshot(
                state,
                stateDetail,
                snapshotChats(),
                new ArrayList<>(players)));
    }

    public void removeUiListener(UiListener listener) {
        uiListeners.remove(listener);
    }

    public SavedServer getActiveServer() {
        return activeServer;
    }

    public void sendChat(String message) {
        worker.execute(() -> {
            try {
                ProtocolConnection active = connection;
                if (active == null) {
                    throw new IllegalStateException("활성 연결이 없습니다.");
                }
                active.sendChat(message);
            } catch (Throwable error) {
                onChat(new ChatLine(
                        System.currentTimeMillis(),
                        ChatLine.Kind.LOCAL_ERROR,
                        "앱",
                        ErrorText.from(error)));
            }
        });
    }

    public void requestCommandSuggestions(String input) {
        worker.execute(() -> {
            try {
                ProtocolConnection active = connection;
                if (active != null) {
                    active.requestCommandSuggestions(input);
                }
            } catch (Throwable ignored) {
                // Suggestions are optional; a failed request must not interrupt chat input.
            }
        });
    }

    public void disconnectAndStop() {
        stopping = true;
        ProtocolConnection active = connection;
        connection = null;
        if (active != null) {
            active.disconnect();
        }
        onStateChanged(ConnectionState.DISCONNECTED, "사용자가 연결을 종료했습니다.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    @Override
    public void onStateChanged(ConnectionState newState, String detail) {
        state = newState;
        stateDetail = detail == null ? "" : detail;
        if (!stopping) {
            updateNotification(newState.getLabel()
                    + (stateDetail.isBlank() ? "" : " · " + stateDetail));
        }
        mainHandler.post(() -> {
            for (UiListener listener : uiListeners) {
                listener.onStateChanged(newState, stateDetail);
            }
        });
    }

    @Override
    public void onChat(ChatLine line) {
        synchronized (chatHistory) {
            chatHistory.add(line);
            while (chatHistory.size() > MAX_CHAT_LINES) {
                chatHistory.remove(0);
            }
        }
        mainHandler.post(() -> {
            for (UiListener listener : uiListeners) {
                listener.onChat(line);
            }
        });
    }

    @Override
    public void onPlayersChanged(List<PlayerView> updatedPlayers) {
        players = List.copyOf(updatedPlayers);
        mainHandler.post(() -> {
            for (UiListener listener : uiListeners) {
                listener.onPlayersChanged(new ArrayList<>(players));
            }
        });
    }

    @Override
    public void onCommandSuggestions(CommandSuggestions suggestions) {
        mainHandler.post(() -> {
            for (UiListener listener : uiListeners) {
                listener.onCommandSuggestions(suggestions);
            }
        });
    }

    private void connect(String serverId, String detectedVersionId) {
        SavedServer currentServer = activeServer;
        if (currentServer != null && currentServer.getId().equals(serverId)
                && state != ConnectionState.DISCONNECTED && state != ConnectionState.ERROR) {
            return;
        }
        worker.execute(() -> {
            try {
                SavedServer server = new ServerRepository(this).getById(serverId);
                if (server == null) {
                    throw new IllegalArgumentException("저장된 서버를 찾을 수 없습니다.");
                }
                validate(server);
                activeServer = server;

                ProtocolConnection old = connection;
                if (old != null) {
                    old.disconnect();
                }
                ProtocolSpec protocolSpec = resolveProtocol(server, detectedVersionId);

                ConnectionSettingsRepository settings =
                        new ConnectionSettingsRepository(this);
                ConnectionIdentity identity;
                if (settings.getAuthMode() == AuthMode.MICROSOFT) {
                    onStateChanged(ConnectionState.AUTHENTICATING, "Microsoft 토큰 갱신 중");
                    MicrosoftAuthRepository auth = new MicrosoftAuthRepository(this);
                    try {
                        OnlineIdentity onlineIdentity = auth.requireIdentity();
                        identity = ConnectionIdentity.online(onlineIdentity);
                    } finally {
                        auth.close();
                    }
                } else {
                    identity = ConnectionIdentity.offline(settings.getOfflineNickname());
                }

                ProtocolConnection created = ProtocolRegistry
                        .adapterFor(protocolSpec)
                        .create(server, identity, this);
                connection = created;
                created.connect();
            } catch (Throwable error) {
                onStateChanged(ConnectionState.ERROR, ErrorText.from(error));
            }
        });
    }

    private static void validate(SavedServer server) {
        if (server.getHost() == null || server.getHost().isBlank()) {
            throw new IllegalArgumentException("서버 주소가 비어 있습니다.");
        }
        if (server.getPort() < 1 || server.getPort() > 65535) {
            throw new IllegalArgumentException("포트는 1~65535 범위여야 합니다.");
        }
        if (!ProtocolRegistry.isAuto(server.getVersionId())) {
            ProtocolRegistry.require(server.getVersionId());
        }
    }

    private ProtocolSpec resolveProtocol(SavedServer server, String detectedVersionId) {
        if (!ProtocolRegistry.isAuto(server.getVersionId())) {
            return ProtocolRegistry.require(server.getVersionId());
        }
        if (detectedVersionId != null && !detectedVersionId.isBlank()) {
            ProtocolSpec hinted = ProtocolRegistry.require(detectedVersionId);
            onStateChanged(ConnectionState.CONNECTING,
                    "Auto → " + hinted.getDisplayName());
            return hinted;
        }
        onStateChanged(ConnectionState.CONNECTING, "서버 버전 자동 감지 중");
        ServerStatusResult status = ServerStatusChecker.query(server);
        if (!status.isOnline() || status.getProtocolVersion() < 0) {
            throw new IllegalArgumentException(
                    "서버 버전을 자동으로 감지하지 못했습니다. 서버 상태를 확인하거나 버전을 직접 선택해 주세요.");
        }
        ProtocolSpec detected = ProtocolRegistry.detect(
                        status.getProtocolVersion(), status.getVersionName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "자동 감지된 서버 버전을 지원하지 않습니다: "
                                + readableVersion(status)));
        onStateChanged(ConnectionState.CONNECTING,
                "Auto → " + detected.getDisplayName());
        return detected;
    }

    private static String readableVersion(ServerStatusResult status) {
        String name = status.getVersionName();
        return (name == null || name.isBlank() ? "알 수 없는 버전" : name)
                + " (프로토콜 " + status.getProtocolVersion() + ")";
    }

    private List<ChatLine> snapshotChats() {
        synchronized (chatHistory) {
            return new ArrayList<>(chatHistory);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, ChatActivity.class);
        if (activeServer != null) {
            openIntent.putExtra(EXTRA_SERVER_ID, activeServer.getId());
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent disconnectIntent = new Intent(this, MinecraftConnectionService.class)
                .setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPendingIntent = PendingIntent.getService(
                this,
                1,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(activeServer == null ? "Minecraft Chat" : activeServer.getName())
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(state != ConnectionState.DISCONNECTED && state != ConnectionState.ERROR);
        if (state != ConnectionState.DISCONNECTED && state != ConnectionState.ERROR) {
            builder.addAction(new Notification.Action.Builder(
                    null, "서버 나가기", disconnectPendingIntent).build());
        }
        return builder.build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    public final class LocalBinder extends Binder {
        public MinecraftConnectionService getService() {
            return MinecraftConnectionService.this;
        }
    }

    public interface UiListener {
        void onSnapshot(ConnectionState state, String detail, List<ChatLine> chats, List<PlayerView> players);
        void onStateChanged(ConnectionState state, String detail);
        void onChat(ChatLine line);
        void onPlayersChanged(List<PlayerView> players);
        void onCommandSuggestions(CommandSuggestions suggestions);
    }
}
