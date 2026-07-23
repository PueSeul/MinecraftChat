package com.dudal.javachat;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.dudal.javachat.data.SavedServer;
import com.dudal.javachat.data.ServerRepository;
import com.dudal.javachat.protocol.ChatLine;
import com.dudal.javachat.protocol.CommandSuggestions;
import com.dudal.javachat.protocol.ConnectionState;
import com.dudal.javachat.protocol.PlayerView;
import com.dudal.javachat.service.MinecraftConnectionService;
import com.dudal.javachat.ui.UiKit;
import com.dudal.javachat.ui.SkinHeadLoader;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ChatActivity extends Activity implements MinecraftConnectionService.UiListener {
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.KOREA);
    private final Handler suggestionHandler = new Handler(Looper.getMainLooper());
    private SkinHeadLoader skinHeadLoader;

    private MinecraftConnectionService service;
    private boolean bound;
    private TextView serverName;
    private TextView statusView;
    private Button chatTab;
    private Button playersTab;
    private ScrollView scroll;
    private LinearLayout content;
    private HorizontalScrollView suggestionScroll;
    private LinearLayout suggestionRow;
    private LinearLayout inputBar;
    private EditText messageInput;
    private List<ChatLine> chats = List.of();
    private List<PlayerView> players = List.of();
    private boolean showingPlayers;
    private boolean connected;
    private boolean connectionRequested;
    private boolean exitDialogShowing;
    private ConnectionState currentState = ConnectionState.DISCONNECTED;
    private Object modernBackCallback;
    private final Runnable suggestionRequest = this::requestSuggestionsNow;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MinecraftConnectionService.LocalBinder) binder).getService();
            bound = true;
            service.addUiListener(ChatActivity.this);
            SavedServer active = service.getActiveServer();
            if (active != null) {
                serverName.setText(active.getName());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            onStateChanged(ConnectionState.DISCONNECTED, "연결 서비스가 종료되었습니다.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.prepareWindow(this);
        skinHeadLoader = new SkinHeadLoader(this);
        setContentView(buildContent());
        if (Build.VERSION.SDK_INT >= 33) {
            registerModernBackCallback();
        }

        String serverId = getIntent().getStringExtra(MinecraftConnectionService.EXTRA_SERVER_ID);
        if (serverId != null) {
            connectionRequested = true;
            SavedServer saved = new ServerRepository(this).getById(serverId);
            if (saved != null) {
                serverName.setText(saved.getName());
            }
            Intent start = new Intent(this, MinecraftConnectionService.class)
                    .setAction(MinecraftConnectionService.ACTION_CONNECT)
                    .putExtra(MinecraftConnectionService.EXTRA_SERVER_ID, serverId);
            startForegroundService(start);
        }
        bindService(new Intent(this, MinecraftConnectionService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        suggestionHandler.removeCallbacks(suggestionRequest);
        skinHeadLoader.close();
        if (Build.VERSION.SDK_INT >= 33) {
            unregisterModernBackCallback();
        }
        if (bound && service != null) {
            service.removeUiListener(this);
            unbindService(serviceConnection);
            bound = false;
        }
        super.onDestroy();
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        confirmDisconnectAndExit();
    }

    @Override
    public void onSnapshot(ConnectionState state, String detail,
                           List<ChatLine> chats, List<PlayerView> players) {
        this.chats = chats;
        this.players = players;
        onStateChanged(state, detail);
        renderContent();
    }

    @Override
    public void onStateChanged(ConnectionState state, String detail) {
        currentState = state;
        connectionRequested = state != ConnectionState.DISCONNECTED
                && state != ConnectionState.ERROR;
        statusView.setText(detail == null || detail.isBlank()
                ? state.getLabel()
                : getString(R.string.connection_status, state.getLabel(), detail));
        int color = switch (state) {
            case CONNECTED -> R.color.primary;
            case ERROR -> R.color.danger;
            default -> R.color.text_secondary;
        };
        statusView.setTextColor(getColor(color));
        boolean canSend = state == ConnectionState.CONNECTED;
        connected = canSend;
        messageInput.setEnabled(canSend);
        if (!canSend) {
            hideCommandSuggestions();
        }
    }

    @Override
    public void onChat(ChatLine line) {
        boolean wasEmpty = chats.isEmpty();
        java.util.ArrayList<ChatLine> updated = new java.util.ArrayList<>(chats);
        updated.add(line);
        chats = updated;
        if (!showingPlayers) {
            if (wasEmpty) {
                // Replace the empty-state guide when the first real line arrives.
                renderContent();
            } else {
                addChatLine(line);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    @Override
    public void onPlayersChanged(List<PlayerView> players) {
        this.players = players;
        playersTab.setText(getString(R.string.player_count, players.size()));
        if (showingPlayers) {
            renderContent();
        }
    }

    @Override
    public void onCommandSuggestions(CommandSuggestions suggestions) {
        if (showingPlayers || !connected) {
            hideCommandSuggestions();
            return;
        }
        int cursor = messageInput.getSelectionStart();
        String current = messageInput.getText().toString();
        if (cursor < 0 || cursor > current.length()
                || !current.substring(0, cursor).equals(suggestions.getRequestText())) {
            return;
        }
        renderCommandSuggestions(suggestions);
    }

    private View buildContent() {
        LinearLayout root = UiKit.vertical(this);
        root.setBackgroundColor(getColor(R.color.background));
        root.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 12));
        UiKit.applySafeInsets(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = UiKit.button(this, "‹", false);
        back.setOnClickListener(view -> confirmDisconnectAndExit());
        header.addView(back, new LinearLayout.LayoutParams(
                UiKit.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout heading = UiKit.vertical(this);
        serverName = UiKit.sectionTitle(this, "Minecraft 서버");
        heading.addView(serverName);
        statusView = UiKit.text(this, "연결 준비 중", 12, R.color.text_secondary);
        statusView.setMaxLines(3);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        UiKit.margin(statusView, 0, 2, 0, 0);
        heading.addView(statusView);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        headingParams.setMarginStart(UiKit.dp(this, 12));
        header.addView(heading, headingParams);

        Button disconnect = UiKit.button(this, "종료", false);
        disconnect.setTextColor(getColor(R.color.danger));
        disconnect.setOnClickListener(view -> confirmDisconnectAndExit());
        header.addView(disconnect);
        root.addView(header, UiKit.matchWrap());

        LinearLayout tabs = new LinearLayout(this);
        chatTab = UiKit.button(this, "채팅", true);
        chatTab.setOnClickListener(view -> showPlayers(false));
        tabs.addView(chatTab, UiKit.weight(1));
        playersTab = UiKit.button(this, "접속자 0", false);
        playersTab.setOnClickListener(view -> showPlayers(true));
        LinearLayout.LayoutParams playerTabParams = UiKit.weight(1);
        playerTabParams.setMarginStart(UiKit.dp(this, 8));
        tabs.addView(playersTab, playerTabParams);
        UiKit.margin(tabs, 0, 18, 0, 10);
        root.addView(tabs);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(UiKit.rounded(this, getColor(R.color.surface), 16));
        content = UiKit.vertical(this);
        content.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10),
                UiKit.dp(this, 14), UiKit.dp(this, 12));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        suggestionScroll = new HorizontalScrollView(this);
        suggestionScroll.setHorizontalScrollBarEnabled(false);
        suggestionScroll.setFillViewport(false);
        suggestionScroll.setVisibility(View.GONE);
        suggestionRow = new LinearLayout(this);
        suggestionRow.setGravity(Gravity.CENTER_VERTICAL);
        suggestionScroll.addView(suggestionRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        UiKit.margin(suggestionScroll, 0, 8, 0, 0);
        root.addView(suggestionScroll, UiKit.matchWrap());

        inputBar = new LinearLayout(this);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        messageInput = UiKit.input(this, "메시지 또는 /명령어 입력");
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        messageInput.setSingleLine(false);
        messageInput.setMaxLines(3);
        messageInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        messageInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        messageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable text) {
                scheduleSuggestionRequest();
            }
        });
        messageInput.setEnabled(false);
        inputBar.addView(messageInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button send = UiKit.button(this, "전송", true);
        send.setOnClickListener(view -> sendMessage());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendParams.setMarginStart(UiKit.dp(this, 8));
        inputBar.addView(send, sendParams);
        UiKit.margin(inputBar, 0, 10, 0, 0);
        root.addView(inputBar);
        return root;
    }

    private void showPlayers(boolean value) {
        showingPlayers = value;
        chatTab.setBackground(UiKit.rounded(this,
                getColor(value ? R.color.surface_high : R.color.primary), 12));
        chatTab.setTextColor(getColor(value ? R.color.text_primary : R.color.background));
        playersTab.setBackground(UiKit.rounded(this,
                getColor(value ? R.color.primary : R.color.surface_high), 12));
        playersTab.setTextColor(getColor(value ? R.color.background : R.color.text_primary));
        inputBar.setVisibility(value ? View.GONE : View.VISIBLE);
        if (value) {
            hideCommandSuggestions();
        } else {
            scheduleSuggestionRequest();
        }
        renderContent();
    }

    private void renderContent() {
        content.removeAllViews();
        if (showingPlayers) {
            renderPlayers();
        } else if (chats.isEmpty()) {
            TextView empty = UiKit.text(this,
                    "서버에 연결하면 이곳에 채팅이 표시됩니다.", 14, R.color.text_secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiKit.dp(this, 32), 0, UiKit.dp(this, 32));
            content.addView(empty, UiKit.matchWrap());
        } else {
            for (ChatLine line : chats) {
                addChatLine(line);
            }
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void addChatLine(ChatLine line) {
        LinearLayout row = UiKit.vertical(this);
        String time = timeFormat.format(new Date(line.getTimestamp()));
        String sender = line.getSender() == null || line.getSender().isBlank()
                ? "" : "  " + line.getSender();
        int messageColor = switch (line.getKind()) {
            case LOCAL_ERROR -> R.color.danger;
            case PRESENCE -> R.color.chat_presence;
            case SYSTEM -> R.color.chat_server;
            case PLAYER -> R.color.text_primary;
        };
        int metaColor = line.getKind() == ChatLine.Kind.PLAYER
                ? R.color.text_secondary : messageColor;
        TextView meta = UiKit.text(this, time + sender, 11, metaColor);
        meta.setGravity(Gravity.END);
        row.addView(meta, UiKit.matchWrap());
        TextView message = UiKit.text(this, line.getMessage(), 15, messageColor);
        UiKit.margin(message, 0, 2, 0, 0);
        row.addView(message);
        UiKit.margin(row, 0, 8, 0, 8);
        content.addView(row);
        content.addView(UiKit.divider(this));
    }

    private void renderPlayers() {
        if (players.isEmpty()) {
            TextView empty = UiKit.text(this,
                    "표시할 접속자가 없습니다.", 14, R.color.text_secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiKit.dp(this, 32), 0, UiKit.dp(this, 32));
            content.addView(empty, UiKit.matchWrap());
            return;
        }
        for (PlayerView player : players) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView head = new ImageView(this);
            skinHeadLoader.load(player, head);
            row.addView(head, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 32), UiKit.dp(this, 32)));
            TextView name = UiKit.text(this, player.getName(), 16, R.color.text_primary);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            nameParams.setMarginStart(UiKit.dp(this, 12));
            row.addView(name, nameParams);
            TextView latency = UiKit.text(this, player.getLatency() + " ms", 12,
                    player.getLatency() < 150 ? R.color.primary : R.color.text_secondary);
            row.addView(latency);
            row.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
            content.addView(row, UiKit.matchWrap());
            content.addView(UiKit.divider(this));
        }
    }

    private void sendMessage() {
        String message = messageInput.getText().toString();
        if (message.trim().isEmpty() || service == null) {
            return;
        }
        service.sendChat(message);
        hideCommandSuggestions();
        messageInput.setText("");
    }

    private void scheduleSuggestionRequest() {
        suggestionHandler.removeCallbacks(suggestionRequest);
        if (!connected || showingPlayers || messageInput == null) {
            hideCommandSuggestions();
            return;
        }
        int cursor = messageInput.getSelectionStart();
        String input = messageInput.getText().toString();
        if (cursor < 0 || cursor > input.length() || !input.startsWith("/")) {
            hideCommandSuggestions();
            return;
        }
        hideCommandSuggestions();
        suggestionHandler.postDelayed(suggestionRequest, 220L);
    }

    private void requestSuggestionsNow() {
        if (!connected || showingPlayers || service == null) {
            return;
        }
        int cursor = messageInput.getSelectionStart();
        String input = messageInput.getText().toString();
        if (cursor >= 0 && cursor <= input.length() && input.startsWith("/")) {
            service.requestCommandSuggestions(input.substring(0, cursor));
        }
    }

    private void renderCommandSuggestions(CommandSuggestions suggestions) {
        suggestionRow.removeAllViews();
        List<String> matches = suggestions.getMatches();
        int limit = Math.min(matches.size(), 12);
        for (int index = 0; index < limit; index++) {
            String match = matches.get(index);
            Button item = UiKit.button(this, match, false);
            item.setAllCaps(false);
            item.setOnClickListener(view -> applySuggestion(suggestions, match));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) {
                params.setMarginStart(UiKit.dp(this, 6));
            }
            suggestionRow.addView(item, params);
        }
        suggestionScroll.setVisibility(limit == 0 ? View.GONE : View.VISIBLE);
        if (limit > 0) {
            suggestionScroll.scrollTo(0, 0);
        }
    }

    private void applySuggestion(CommandSuggestions suggestions, String match) {
        Editable editable = messageInput.getText();
        int start = Math.max(0, Math.min(suggestions.getStart(), editable.length()));
        int end = Math.max(start,
                Math.min(start + suggestions.getLength(), editable.length()));
        editable.replace(start, end, match);
        int cursor = Math.min(start + match.length(), editable.length());
        messageInput.setSelection(cursor);
        hideCommandSuggestions();
        scheduleSuggestionRequest();
    }

    private void hideCommandSuggestions() {
        if (suggestionScroll != null) {
            suggestionScroll.setVisibility(View.GONE);
        }
        if (suggestionRow != null) {
            suggestionRow.removeAllViews();
        }
    }

    private void confirmDisconnectAndExit() {
        boolean active = connectionRequested
                || currentState == ConnectionState.CONNECTING
                || currentState == ConnectionState.AUTHENTICATING
                || currentState == ConnectionState.CONNECTED;
        if (!active) {
            finish();
            return;
        }
        if (exitDialogShowing) {
            return;
        }
        exitDialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("서버에서 나갈까요?")
                .setMessage("현재 연결을 종료하고 서버 목록으로 돌아갑니다.")
                .setNegativeButton("계속 접속", null)
                .setPositiveButton("나가기", (ignored, which) -> disconnectAndFinish())
                .create();
        dialog.setOnDismissListener(ignored -> exitDialogShowing = false);
        dialog.show();
    }

    private void disconnectAndFinish() {
        connectionRequested = false;
        if (service != null) {
            service.disconnectAndStop();
        } else {
            startService(new Intent(this, MinecraftConnectionService.class)
                    .setAction(MinecraftConnectionService.ACTION_DISCONNECT));
        }
        finish();
    }

    @TargetApi(33)
    private void registerModernBackCallback() {
        OnBackInvokedCallback callback = this::confirmDisconnectAndExit;
        modernBackCallback = callback;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
    }

    @TargetApi(33)
    private void unregisterModernBackCallback() {
        if (modernBackCallback instanceof OnBackInvokedCallback callback) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
            modernBackCallback = null;
        }
    }
}
