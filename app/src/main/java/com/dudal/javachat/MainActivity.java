package com.dudal.javachat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import com.dudal.javachat.auth.AuthErrorText;
import com.dudal.javachat.auth.MicrosoftAuthRepository;
import com.dudal.javachat.data.AuthMode;
import com.dudal.javachat.data.ConnectionSettingsRepository;
import com.dudal.javachat.data.SavedServer;
import com.dudal.javachat.data.ServerRepository;
import com.dudal.javachat.service.MinecraftConnectionService;
import com.dudal.javachat.protocol.ProtocolRegistry;
import com.dudal.javachat.status.ServerStatusChecker;
import com.dudal.javachat.status.ServerStatusResult;
import com.dudal.javachat.ui.UiKit;
import com.dudal.javachat.ui.ServerEndpointText;
import com.dudal.javachat.update.GitHubUpdateChecker;
import com.dudal.javachat.update.UpdateFileProvider;
import com.dudal.javachat.update.VersionOrder;
import com.dudal.javachat.util.ErrorText;

import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
    private static final int REQUEST_INSTALL_PERMISSION = 201;
    private ServerRepository servers;
    private ConnectionSettingsRepository connectionSettings;
    private MicrosoftAuthRepository auth;
    private LinearLayout serverList;
    private LinearLayout microsoftFields;
    private LinearLayout offlineFields;
    private LinearLayout updateCard;
    private TextView pullRefreshIndicator;
    private TextView modeStatus;
    private TextView accountStatus;
    private TextView updateStatus;
    private Button accountButton;
    private Button onlineModeButton;
    private Button offlineModeButton;
    private Button updateButton;
    private Button serverRefreshButton;
    private EditText offlineNicknameInput;
    private boolean loginInProgress;
    private boolean updateCheckInProgress;
    private GitHubUpdateChecker.ReleaseInfo availableUpdate;
    private GitHubUpdateChecker.ReleaseInfo pendingUpdate;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean pullRefreshActive;
    private boolean pullTracking;
    private float pullStartY;
    private float pullDistance;
    private int pullRefreshGeneration = -1;
    private int pendingPullRefreshChecks;
    private final Runnable hidePullRefreshIndicator = () -> {
        if (!pullRefreshActive && pullRefreshIndicator != null) {
            pullRefreshIndicator.setVisibility(View.GONE);
        }
    };
    private final ExecutorService statusExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger statusGeneration = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.prepareWindow(this);
        servers = new ServerRepository(this);
        connectionSettings = new ConnectionSettingsRepository(this);
        auth = new MicrosoftAuthRepository(this);
        setContentView(buildContent());
        checkForUpdates(false);
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnectionMode();
        refreshAccount();
        refreshServers();
    }

    @Override
    protected void onDestroy() {
        statusGeneration.incrementAndGet();
        mainHandler.removeCallbacks(hidePullRefreshIndicator);
        statusExecutor.shutdownNow();
        updateExecutor.shutdownNow();
        auth.close();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL_PERMISSION || pendingUpdate == null) {
            return;
        }
        GitHubUpdateChecker.ReleaseInfo release = pendingUpdate;
        pendingUpdate = null;
        if (getPackageManager().canRequestPackageInstalls()) {
            downloadAndInstall(release);
        } else {
            Toast.makeText(this,
                    "업데이트를 설치하려면 이 앱의 출처 허용이 필요합니다.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));

        LinearLayout root = UiKit.vertical(this);
        root.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 24),
                UiKit.dp(this, 20), UiKit.dp(this, 36));
        // Keep the scroll viewport itself inside the system bars. Applying the
        // insets to the scrolling child lets its padding scroll under a bar.
        UiKit.applySafeInsets(scroll);
        configurePullToRefresh(scroll);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        pullRefreshIndicator = UiKit.text(this,
                "↓ 서버 목록 새로고침", 12, R.color.text_secondary);
        pullRefreshIndicator.setGravity(Gravity.CENTER);
        pullRefreshIndicator.setVisibility(View.GONE);
        pullRefreshIndicator.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pullRefreshIndicator.setPadding(0, 0, 0, UiKit.dp(this, 10));
        root.addView(pullRefreshIndicator, UiKit.matchWrap());

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = UiKit.title(this, "Minecraft Chat");
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button more = UiKit.button(this, "⋮", false);
        more.setContentDescription("더보기");
        more.setTextSize(22);
        more.setPadding(0, 0, 0, 0);
        more.setOnClickListener(this::showMoreMenu);
        titleRow.addView(more, new LinearLayout.LayoutParams(
                UiKit.dp(this, 48), UiKit.dp(this, 44)));
        root.addView(titleRow, UiKit.matchWrap());
        TextView subtitle = UiKit.text(this,
                "Minecraft Java 1.8.9–26.2 · 채팅과 접속자 목록",
                14, R.color.text_secondary);
        UiKit.margin(subtitle, 0, 3, 0, 12);
        root.addView(subtitle);

        updateCard = UiKit.card(this);
        updateCard.setVisibility(View.GONE);
        TextView updateTitle = UiKit.sectionTitle(this, "앱 업데이트");
        updateCard.addView(updateTitle);
        updateStatus = UiKit.text(this, "신규 버전이 존재합니다.",
                13, R.color.primary);
        UiKit.margin(updateStatus, 0, 5, 0, 12);
        updateCard.addView(updateStatus);
        updateButton = UiKit.button(this, "업데이트", true);
        updateButton.setOnClickListener(view -> {
            GitHubUpdateChecker.ReleaseInfo release = availableUpdate;
            if (release != null) {
                beginUpdate(release);
            }
        });
        updateCard.addView(updateButton, UiKit.matchWrap());
        LinearLayout.LayoutParams updateCardParams = UiKit.matchWrap();
        updateCardParams.bottomMargin = UiKit.dp(this, 12);
        root.addView(updateCard, updateCardParams);

        LinearLayout connectionCard = UiKit.card(this);
        TextView connectionTitle = UiKit.sectionTitle(this, "접속 방식");
        connectionCard.addView(connectionTitle);
        TextView connectionGuide = UiKit.text(this,
                "여기서 선택한 방식으로 모든 저장 서버에 접속합니다.",
                13, R.color.text_secondary);
        UiKit.margin(connectionGuide, 0, 5, 0, 12);
        connectionCard.addView(connectionGuide);

        LinearLayout modeButtons = new LinearLayout(this);
        onlineModeButton = UiKit.button(this, "온라인", false);
        onlineModeButton.setOnClickListener(view -> selectAuthMode(AuthMode.MICROSOFT));
        modeButtons.addView(onlineModeButton, UiKit.weight(1));
        offlineModeButton = UiKit.button(this, "오프라인", false);
        offlineModeButton.setOnClickListener(view -> selectAuthMode(AuthMode.OFFLINE));
        LinearLayout.LayoutParams offlineModeParams = UiKit.weight(1);
        offlineModeParams.setMarginStart(UiKit.dp(this, 8));
        modeButtons.addView(offlineModeButton, offlineModeParams);
        connectionCard.addView(modeButtons, UiKit.matchWrap());

        modeStatus = UiKit.text(this, "접속 방식 확인 중", 13, R.color.text_secondary);
        UiKit.margin(modeStatus, 2, 9, 2, 0);
        connectionCard.addView(modeStatus);

        microsoftFields = UiKit.vertical(this);
        TextView accountTitle = UiKit.sectionTitle(this, "Microsoft 계정");
        UiKit.margin(accountTitle, 0, 18, 0, 0);
        microsoftFields.addView(accountTitle);
        accountStatus = UiKit.text(this, "확인 중", 14, R.color.text_secondary);
        UiKit.margin(accountStatus, 0, 6, 0, 12);
        microsoftFields.addView(accountStatus);
        accountButton = UiKit.button(this, "Microsoft 로그인", true);
        accountButton.setOnClickListener(view -> handleAccountButton());
        microsoftFields.addView(accountButton, UiKit.matchWrap());
        connectionCard.addView(microsoftFields, UiKit.matchWrap());

        offlineFields = UiKit.vertical(this);
        TextView nicknameTitle = UiKit.sectionTitle(this, "오프라인 닉네임");
        UiKit.margin(nicknameTitle, 0, 18, 0, 8);
        offlineFields.addView(nicknameTitle);
        offlineNicknameInput = UiKit.input(this, "영문, 숫자, 밑줄 3~16자");
        offlineNicknameInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        offlineFields.addView(offlineNicknameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        Button saveNickname = UiKit.button(this, "닉네임 저장", true);
        saveNickname.setOnClickListener(view -> saveOfflineNickname(true));
        UiKit.margin(saveNickname, 0, 10, 0, 0);
        offlineFields.addView(saveNickname, UiKit.matchWrap());
        TextView offlineGuide = UiKit.text(this,
                "오프라인 방식은 online-mode=false 서버에서만 사용할 수 있습니다.",
                12, R.color.text_secondary);
        UiKit.margin(offlineGuide, 2, 8, 2, 0);
        offlineFields.addView(offlineGuide);
        connectionCard.addView(offlineFields, UiKit.matchWrap());
        root.addView(connectionCard, UiKit.matchWrap());

        LinearLayout serverHeader = new LinearLayout(this);
        serverHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView serverTitle = UiKit.sectionTitle(this, "저장한 서버");
        serverHeader.addView(serverTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        serverRefreshButton = UiKit.button(this, "새로고침", false);
        serverRefreshButton.setTextSize(13);
        serverRefreshButton.setOnClickListener(view -> startPullRefresh());
        LinearLayout.LayoutParams refreshButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshButtonParams.setMarginEnd(UiKit.dp(this, 8));
        serverHeader.addView(serverRefreshButton, refreshButtonParams);
        Button add = UiKit.button(this, "+ 서버 추가", true);
        add.setOnClickListener(view -> startActivity(new Intent(this, ServerEditorActivity.class)));
        serverHeader.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        UiKit.margin(serverHeader, 0, 24, 0, 12);
        root.addView(serverHeader);

        serverList = UiKit.vertical(this);
        root.addView(serverList, UiKit.matchWrap());

        TextView note = UiKit.text(this,
                "Microsoft 로그인 정보는 이 기기의 Android Keystore로 암호화됩니다.",
                12, R.color.text_secondary);
        UiKit.margin(note, 2, 18, 2, 0);
        root.addView(note);
        return scroll;
    }

    private void refreshAccount() {
        if (auth.hasAccount()) {
            loginInProgress = false;
            String name = auth.getAccountName();
            accountStatus.setText(name == null ? "로그인됨" : name + " 계정으로 로그인됨");
            accountStatus.setTextColor(getColor(R.color.primary));
            accountButton.setText("로그아웃");
            accountButton.setEnabled(true);
        } else if (loginInProgress) {
            accountStatus.setText("브라우저에서 Microsoft 인증 완료를 기다리는 중입니다.");
            accountStatus.setTextColor(getColor(R.color.text_secondary));
            accountButton.setText("로그인 진행 중…");
            accountButton.setEnabled(false);
        } else {
            accountStatus.setText("정품 인증 서버 접속에 필요합니다.");
            accountStatus.setTextColor(getColor(R.color.text_secondary));
            accountButton.setText(R.string.microsoft_login);
            accountButton.setEnabled(true);
        }
        updateModeStatus();
    }

    private void refreshConnectionMode() {
        AuthMode mode = connectionSettings.getAuthMode();
        boolean online = mode == AuthMode.MICROSOFT;
        styleModeButton(onlineModeButton, online);
        styleModeButton(offlineModeButton, !online);
        microsoftFields.setVisibility(online ? View.VISIBLE : View.GONE);
        offlineFields.setVisibility(online ? View.GONE : View.VISIBLE);
        String savedNickname = connectionSettings.getOfflineNickname();
        if (!offlineNicknameInput.hasFocus()
                && !savedNickname.equals(offlineNicknameInput.getText().toString())) {
            offlineNicknameInput.setText(savedNickname);
        }
        updateModeStatus();
    }

    private void selectAuthMode(AuthMode mode) {
        connectionSettings.setAuthMode(mode);
        refreshConnectionMode();
        refreshAccount();
        refreshServers();
    }

    private void styleModeButton(Button button, boolean selected) {
        button.setTextColor(getColor(selected ? R.color.background : R.color.text_primary));
        button.setBackground(UiKit.rounded(this,
                getColor(selected ? R.color.primary : R.color.surface_high), 12));
    }

    private void updateModeStatus() {
        if (modeStatus == null) {
            return;
        }
        if (connectionSettings.getAuthMode() == AuthMode.MICROSOFT) {
            String name = auth.getAccountName();
            modeStatus.setText(auth.hasAccount()
                    ? "현재 온라인 · " + (name == null ? "Microsoft 계정" : name)
                    : "현재 온라인 · Microsoft 로그인이 필요합니다.");
            modeStatus.setTextColor(getColor(
                    auth.hasAccount() ? R.color.primary : R.color.danger));
        } else {
            modeStatus.setText("현재 오프라인 · "
                    + connectionSettings.getOfflineNickname());
            modeStatus.setTextColor(getColor(R.color.primary));
        }
    }

    private boolean saveOfflineNickname(boolean showConfirmation) {
        String nickname = offlineNicknameInput.getText().toString().trim();
        if (!ConnectionSettingsRepository.isValidOfflineNickname(nickname)) {
            offlineNicknameInput.setError("영문, 숫자, 밑줄 3~16자로 입력하세요.");
            offlineNicknameInput.requestFocus();
            return false;
        }
        connectionSettings.setOfflineNickname(nickname);
        updateModeStatus();
        refreshServers();
        if (showConfirmation) {
            Toast.makeText(this, "오프라인 닉네임을 저장했습니다.",
                    Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void refreshServers() {
        int generation = statusGeneration.incrementAndGet();
        serverList.removeAllViews();
        List<SavedServer> values = servers.getAll();
        if (pullRefreshActive) {
            pullRefreshGeneration = generation;
            pendingPullRefreshChecks = values.size();
        }
        if (values.isEmpty()) {
            TextView empty = UiKit.text(this,
                    "아직 저장한 서버가 없습니다.\n서버 주소를 추가해 주세요.",
                    15, R.color.text_secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiKit.dp(this, 34), 0, UiKit.dp(this, 34));
            empty.setBackground(UiKit.rounded(this, getColor(R.color.surface), 16));
            serverList.addView(empty, UiKit.matchWrap());
            finishPullRefresh(generation);
            return;
        }
        for (SavedServer server : values) {
            serverList.addView(buildServerCard(server, generation));
        }
    }

    private View buildServerCard(SavedServer server, int generation) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 14),
                UiKit.dp(this, 10), UiKit.dp(this, 14));
        card.setBackground(UiKit.rounded(this, getColor(R.color.surface), 16));

        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout identity = UiKit.vertical(this);
        TextView name = UiKit.text(this, server.getName(), 15, R.color.text_primary);
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        identity.addView(name, UiKit.matchWrap());

        TextView endpoint = UiKit.text(this,
                ServerEndpointText.format(server.getHost(), server.getPort()),
                14, R.color.text_secondary);
        endpoint.setSingleLine(true);
        endpoint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        endpoint.setAutoSizeTextTypeUniformWithConfiguration(
                12, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        UiKit.margin(endpoint, 0, 3, 0, 0);
        identity.addView(endpoint, UiKit.matchWrap());

        TextView status = UiKit.text(this, getString(R.string.server_status_checking),
                13, R.color.text_secondary);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setAutoSizeTextTypeUniformWithConfiguration(
                11, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        UiKit.margin(status, 0, 5, 0, 0);
        identity.addView(status, UiKit.matchWrap());

        String authLabel = connectionSettings.getAuthMode() == AuthMode.MICROSOFT
                ? "현재 온라인" : "현재 오프라인";
        TextView details = UiKit.text(this,
                ProtocolRegistry.require(server.getVersionId()).getDisplayName()
                        + " · " + authLabel, 13, R.color.primary);
        details.setSingleLine(true);
        details.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.setAutoSizeTextTypeUniformWithConfiguration(
                11, 13, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        UiKit.margin(details, 0, 3, 0, 0);
        identity.addView(details, UiKit.matchWrap());
        infoRow.addView(identity, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView serverIcon = new ImageView(this);
        serverIcon.setContentDescription(server.getName() + " 서버 아이콘");
        serverIcon.setBackground(UiKit.rounded(this, getColor(R.color.surface_high), 10));
        serverIcon.setClipToOutline(true);
        applyServerIcon(serverIcon, null);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                UiKit.dp(this, 64), UiKit.dp(this, 64));
        iconParams.setMarginStart(UiKit.dp(this, 14));
        infoRow.addView(serverIcon, iconParams);
        card.addView(infoRow, UiKit.matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        Button connect = UiKit.button(this, "접속", true);
        connect.setTextSize(14);
        connect.setPadding(0, 0, 0, 0);
        connect.setOnClickListener(view -> openChat(server));
        actions.addView(connect, new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 48), 1));

        Button edit = UiKit.button(this, "편집", false);
        edit.setTextSize(14);
        edit.setPadding(0, 0, 0, 0);
        edit.setOnClickListener(view -> {
            Intent intent = new Intent(this, ServerEditorActivity.class);
            intent.putExtra(ServerEditorActivity.EXTRA_SERVER_ID, server.getId());
            startActivity(intent);
        });
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 48), 1);
        editParams.setMarginStart(UiKit.dp(this, 8));
        actions.addView(edit, editParams);
        LinearLayout.LayoutParams actionsParams = UiKit.matchWrap();
        actionsParams.topMargin = UiKit.dp(this, 8);
        card.addView(actions, actionsParams);

        checkServerStatus(server, status, serverIcon, generation);

        LinearLayout.LayoutParams cardParams = UiKit.matchWrap();
        cardParams.bottomMargin = UiKit.dp(this, 18);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void checkServerStatus(SavedServer server, TextView view,
                                   ImageView iconView, int generation) {
        statusExecutor.execute(() -> {
            ServerStatusResult result = ServerStatusChecker.query(server);
            Bitmap icon = decodeServerIcon(result.getIconPng());
            runOnUiThread(() -> {
                if (isDestroyed() || generation != statusGeneration.get()
                        || !view.isAttachedToWindow()) {
                    completePullRefreshCheck(generation);
                    return;
                }
                if (result.isOnline()) {
                    view.setText(getString(R.string.server_status_online,
                            result.getOnlinePlayers(), result.getMaxPlayers(), result.getLatencyMs()));
                    view.setTextColor(getColor(R.color.primary));
                    applyServerIcon(iconView, icon);
                } else {
                    view.setText(R.string.server_status_offline);
                    view.setTextColor(getColor(R.color.danger));
                }
                completePullRefreshCheck(generation);
            });
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configurePullToRefresh(ScrollView scroll) {
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        scroll.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    pullTracking = !scroll.canScrollVertically(-1);
                    pullStartY = event.getY();
                    pullDistance = 0;
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!pullTracking && !scroll.canScrollVertically(-1)) {
                        pullTracking = true;
                        pullStartY = event.getY();
                    }
                    if (pullTracking && !scroll.canScrollVertically(-1)) {
                        pullDistance = Math.max(0, event.getY() - pullStartY);
                        updatePullRefreshPreview();
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    boolean shouldRefresh = event.getActionMasked() == MotionEvent.ACTION_UP
                            && pullTracking
                            && pullDistance >= UiKit.dp(this, 72)
                            && !pullRefreshActive;
                    pullTracking = false;
                    pullDistance = 0;
                    if (shouldRefresh) {
                        startPullRefresh();
                    } else if (!pullRefreshActive) {
                        pullRefreshIndicator.setVisibility(View.GONE);
                    }
                }
                default -> { }
            }
            return false;
        });
    }

    private void updatePullRefreshPreview() {
        if (pullRefreshActive || pullDistance < UiKit.dp(this, 12)) {
            return;
        }
        boolean ready = pullDistance >= UiKit.dp(this, 72);
        pullRefreshIndicator.setText(ready
                ? "놓아서 서버 목록 새로고침"
                : "↓ 서버 목록 새로고침");
        pullRefreshIndicator.setTextColor(getColor(
                ready ? R.color.primary : R.color.text_secondary));
        pullRefreshIndicator.setVisibility(View.VISIBLE);
    }

    private void startPullRefresh() {
        if (pullRefreshActive) {
            return;
        }
        mainHandler.removeCallbacks(hidePullRefreshIndicator);
        pullRefreshActive = true;
        serverRefreshButton.setEnabled(false);
        pullRefreshIndicator.setText("서버 목록 새로고침 중…");
        pullRefreshIndicator.setTextColor(getColor(R.color.primary));
        pullRefreshIndicator.setVisibility(View.VISIBLE);
        refreshServers();
    }

    private void completePullRefreshCheck(int generation) {
        if (!pullRefreshActive || generation != pullRefreshGeneration) {
            return;
        }
        pendingPullRefreshChecks--;
        if (pendingPullRefreshChecks <= 0) {
            finishPullRefresh(generation);
        }
    }

    private void finishPullRefresh(int generation) {
        if (!pullRefreshActive || generation != pullRefreshGeneration) {
            return;
        }
        pullRefreshActive = false;
        pendingPullRefreshChecks = 0;
        serverRefreshButton.setEnabled(true);
        pullRefreshIndicator.setText("서버 목록 새로고침 완료");
        pullRefreshIndicator.setTextColor(getColor(R.color.primary));
        pullRefreshIndicator.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(hidePullRefreshIndicator);
        mainHandler.postDelayed(hidePullRefreshIndicator, 700L);
    }

    private Bitmap decodeServerIcon(byte[] iconPng) {
        if (iconPng == null || iconPng.length == 0 || iconPng.length > 1024 * 1024) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(iconPng, 0, iconPng.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || bounds.outWidth > 512 || bounds.outHeight > 512) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        return BitmapFactory.decodeByteArray(iconPng, 0, iconPng.length, options);
    }

    private void applyServerIcon(ImageView view, Bitmap icon) {
        if (icon == null) {
            int padding = UiKit.dp(this, 8);
            view.setPadding(padding, padding, padding, padding);
            view.setScaleType(ImageView.ScaleType.FIT_CENTER);
            view.setImageResource(R.drawable.ic_launcher);
            return;
        }
        view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        BitmapDrawable drawable = new BitmapDrawable(getResources(), icon);
        drawable.setFilterBitmap(false);
        view.setImageDrawable(drawable);
    }

    private void openChat(SavedServer server) {
        if (connectionSettings.getAuthMode() == AuthMode.MICROSOFT
                && !auth.hasAccount()) {
            showError("온라인 로그인 필요",
                    "온라인 방식으로 접속하려면 먼저 Microsoft 로그인을 완료해 주세요.");
            return;
        }
        if (connectionSettings.getAuthMode() == AuthMode.OFFLINE
                && !saveOfflineNickname(false)) {
            return;
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(MinecraftConnectionService.EXTRA_SERVER_ID, server.getId());
        startActivity(intent);
    }

    private void handleAccountButton() {
        if (loginInProgress) {
            return;
        }
        if (auth.hasAccount()) {
            new AlertDialog.Builder(this)
                    .setTitle("Microsoft 로그아웃")
                    .setMessage("이 기기에 암호화 저장된 로그인 정보를 삭제할까요?")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("로그아웃", (dialog, which) -> {
                        auth.logout();
                        refreshAccount();
                    })
                    .show();
            return;
        }

        loginInProgress = true;
        accountButton.setEnabled(false);
        accountButton.setText("로그인 준비 중…");
        accountStatus.setText("Microsoft 로그인 준비 중입니다.");
        auth.login(new MicrosoftAuthRepository.LoginCallback() {
            @Override
            public void onStatus(String status) {
                runOnUiThread(() -> {
                    if (loginInProgress) {
                        accountButton.setText("로그인 중…");
                        accountStatus.setText(status);
                    }
                });
            }

            @Override
            public void onDeviceCode(MsaDeviceCode code) {
                runOnUiThread(() -> {
                    refreshAccount();
                    showDeviceCode(code);
                });
            }

            @Override
            public void onSuccess(String profileName) {
                runOnUiThread(() -> {
                    loginInProgress = false;
                    Toast.makeText(MainActivity.this,
                            profileName + " 로그인 완료", Toast.LENGTH_LONG).show();
                    refreshAccount();
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    loginInProgress = false;
                    refreshAccount();
                    showError("로그인 실패", AuthErrorText.from(error));
                });
            }
        });
    }

    private void showDeviceCode(MsaDeviceCode code) {
        LinearLayout content = UiKit.vertical(this);
        int padding = UiKit.dp(this, 24);
        content.setPadding(padding, UiKit.dp(this, 8), padding, 0);
        TextView guide = UiKit.text(this,
                "브라우저에서 Microsoft 계정으로 로그인한 뒤 아래 코드를 확인하세요.",
                15, R.color.text_primary);
        content.addView(guide);
        TextView codeView = UiKit.title(this, code.getUserCode());
        codeView.setTextIsSelectable(true);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(0, UiKit.dp(this, 22), 0, UiKit.dp(this, 22));
        content.addView(codeView, UiKit.matchWrap());

        new AlertDialog.Builder(this)
                .setTitle("Microsoft 기기 로그인")
                .setView(content)
                .setNeutralButton("코드 복사", (dialog, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Microsoft code", code.getUserCode()));
                })
                .setNegativeButton("닫기", null)
                .setPositiveButton("브라우저 열기", (dialog, which) -> {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(code.getDirectVerificationUri())));
                })
                .show();
    }

    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("업데이트 확인");
        popup.setOnMenuItemClickListener(item -> {
            checkForUpdates(true);
            return true;
        });
        popup.show();
    }

    private void checkForUpdates(boolean showResultDialog) {
        if (updateCheckInProgress) {
            if (showResultDialog) {
                Toast.makeText(this, "이미 업데이트를 확인하고 있습니다.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        updateCheckInProgress = true;
        if (availableUpdate == null) {
            updateCard.setVisibility(View.GONE);
        }
        updateExecutor.execute(() -> {
            try {
                GitHubUpdateChecker.ReleaseInfo release =
                        new GitHubUpdateChecker().fetchLatest();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    applyUpdateResult(release);
                    if (showResultDialog) {
                        showUpdateResult(release);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    updateCheckInProgress = false;
                    if (availableUpdate == null) {
                        updateCard.setVisibility(View.GONE);
                    } else {
                        showAvailableUpdate(availableUpdate);
                    }
                    if (showResultDialog) {
                        showError("업데이트 확인 실패", ErrorText.from(error));
                    }
                });
            }
        });
    }

    private void applyUpdateResult(GitHubUpdateChecker.ReleaseInfo release) {
        updateCheckInProgress = false;
        if (VersionOrder.isNewer(release.getTagName(), BuildConfig.VERSION_NAME)) {
            availableUpdate = release;
            showAvailableUpdate(release);
        } else {
            availableUpdate = null;
            updateCard.setVisibility(View.GONE);
        }
    }

    private void showAvailableUpdate(GitHubUpdateChecker.ReleaseInfo release) {
        updateCard.setVisibility(View.VISIBLE);
        updateStatus.setText("신규 버전이 존재합니다. " + release.getTagName());
        updateStatus.setTextColor(getColor(R.color.primary));
        updateButton.setText("업데이트");
        updateButton.setEnabled(true);
        styleUpdateButton(true);
    }

    private void styleUpdateButton(boolean primary) {
        updateButton.setTextColor(getColor(primary ? R.color.background : R.color.text_primary));
        updateButton.setBackground(UiKit.rounded(this,
                getColor(primary ? R.color.primary : R.color.surface_high), 12));
    }

    private void showUpdateResult(GitHubUpdateChecker.ReleaseInfo release) {
        if (!VersionOrder.isNewer(release.getTagName(), BuildConfig.VERSION_NAME)) {
            new AlertDialog.Builder(this)
                    .setTitle("최신 버전입니다")
                    .setMessage("현재 설치된 Minecraft Chat v" + BuildConfig.VERSION_NAME
                            + "이 최신 정식 버전입니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        StringBuilder message = new StringBuilder()
                .append("현재 버전: v").append(BuildConfig.VERSION_NAME)
                .append("\n최신 버전: ").append(release.getTagName());
        String notes = release.getNotes();
        if (notes != null && !notes.isBlank()) {
            notes = notes.trim();
            if (notes.length() > 500) {
                notes = notes.substring(0, 499) + "…";
            }
            message.append("\n\n").append(notes);
        }
        new AlertDialog.Builder(this)
                .setTitle("새 업데이트가 있습니다")
                .setMessage(message.toString())
                .setNegativeButton("나중에", null)
                .setNeutralButton("GitHub 보기",
                        (dialog, which) -> openReleasePage(release.getHtmlUrl()))
                .setPositiveButton("업데이트",
                        (dialog, which) -> beginUpdate(release))
                .show();
    }

    private void beginUpdate(GitHubUpdateChecker.ReleaseInfo release) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            pendingUpdate = release;
            new AlertDialog.Builder(this)
                    .setTitle("설치 권한이 필요합니다")
                    .setMessage("처음 한 번만 Minecraft Chat의 '이 출처 허용'을 켜 주세요. "
                            + "설정에서 돌아오면 다운로드와 설치를 계속합니다.")
                    .setNegativeButton("취소", (dialog, which) -> pendingUpdate = null)
                    .setPositiveButton("설정 열기", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_INSTALL_PERMISSION);
                    })
                    .show();
            return;
        }
        downloadAndInstall(release);
    }

    private void downloadAndInstall(GitHubUpdateChecker.ReleaseInfo release) {
        updateCheckInProgress = true;
        updateCard.setVisibility(View.VISIBLE);
        updateStatus.setText(release.getTagName() + " 업데이트를 다운로드하는 중입니다.");
        updateStatus.setTextColor(getColor(R.color.primary));
        updateButton.setEnabled(false);
        updateButton.setText("업데이트 다운로드 중…");
        updateExecutor.execute(() -> {
            try {
                File destination = new File(new File(getCacheDir(), "updates"),
                        UpdateFileProvider.FILE_NAME);
                GitHubUpdateChecker checker = new GitHubUpdateChecker();
                File apk = checker.downloadApk(release, destination, percent -> {
                    if (percent % 5 == 0 || percent == 100) {
                        runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                updateButton.setText("업데이트 다운로드 " + percent + "%");
                            }
                        });
                    }
                });
                verifyUpdateApk(apk);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    updateCheckInProgress = false;
                    showAvailableUpdate(release);
                    launchPackageInstaller();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    updateCheckInProgress = false;
                    showAvailableUpdate(release);
                    showError("업데이트 실패", ErrorText.from(error));
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void verifyUpdateApk(File apk) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(), flags);
        PackageInfo update = getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), flags);
        if (update == null || !getPackageName().equals(update.packageName)) {
            throw new SecurityException("업데이트 APK의 앱 패키지가 일치하지 않습니다.");
        }
        long installedCode = Build.VERSION.SDK_INT >= 28
                ? installed.getLongVersionCode() : installed.versionCode;
        long updateCode = Build.VERSION.SDK_INT >= 28
                ? update.getLongVersionCode() : update.versionCode;
        if (updateCode <= installedCode) {
            throw new SecurityException("현재 앱보다 새로운 APK가 아닙니다.");
        }
        if (!signerDigests(installed).equals(signerDigests(update))) {
            throw new SecurityException("업데이트 APK의 서명이 현재 앱과 일치하지 않습니다.");
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signerDigests(PackageInfo info)
            throws NoSuchAlgorithmException {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            throw new SecurityException("APK 서명 정보를 확인할 수 없습니다.");
        }
        Set<String> digests = new HashSet<>();
        for (Signature signature : signatures) {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray());
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            digests.add(hex.toString());
        }
        return digests;
    }

    private void launchPackageInstaller() {
        Uri apkUri = new Uri.Builder()
                .scheme("content")
                .authority(getPackageName() + ".update_files")
                .appendPath(UpdateFileProvider.FILE_NAME)
                .build();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            showError("업데이트 실패", "Android 설치 화면을 열 수 없습니다.");
        }
    }

    private void openReleasePage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException error) {
            Toast.makeText(this, "GitHub 릴리스 페이지를 열 수 없습니다.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

}
