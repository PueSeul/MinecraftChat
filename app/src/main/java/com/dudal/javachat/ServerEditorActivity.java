package com.dudal.javachat;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.dudal.javachat.data.SavedServer;
import com.dudal.javachat.data.ServerRepository;
import com.dudal.javachat.protocol.ProtocolRegistry;
import com.dudal.javachat.protocol.ProtocolSpec;
import com.dudal.javachat.ui.UiKit;

import java.util.List;

public final class ServerEditorActivity extends Activity {
    public static final String EXTRA_SERVER_ID = "server_id";

    private ServerRepository repository;
    private SavedServer server;
    private EditText nameInput;
    private EditText hostInput;
    private EditText portInput;
    private Spinner versionSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.prepareWindow(this);
        repository = new ServerRepository(this);
        String id = getIntent().getStringExtra(EXTRA_SERVER_ID);
        server = id == null ? SavedServer.createDefault() : repository.getById(id);
        if (server == null) {
            Toast.makeText(this, "서버 정보를 찾을 수 없습니다.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(buildContent(id != null));
        populate();
    }

    private View buildContent(boolean editing) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));
        LinearLayout root = UiKit.vertical(this);
        root.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 20),
                UiKit.dp(this, 20), UiKit.dp(this, 36));
        UiKit.applySafeInsets(root);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = UiKit.button(this, "‹", false);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(
                UiKit.dp(this, 48), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = UiKit.title(this, editing ? "서버 편집" : "서버 추가");
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(UiKit.dp(this, 12));
        header.addView(title, titleParams);
        root.addView(header, UiKit.matchWrap());

        nameInput = addInput(root, "표시 이름", "예: 우리 서버");
        hostInput = addInput(root, "서버 주소", "예: play.example.com");
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        portInput = addInput(root, "포트", "25565");
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);

        addLabel(root, "Minecraft 버전");
        versionSpinner = new Spinner(this);
        List<ProtocolSpec> versions = ProtocolRegistry.selectableVersions();
        ArrayAdapter<ProtocolSpec> versionAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, versions);
        versionSpinner.setAdapter(versionAdapter);
        versionSpinner.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 8), 0);
        versionSpinner.setBackground(UiKit.rounded(this, getColor(R.color.surface_high), 12));
        root.addView(versionSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        TextView autoGuide = UiKit.text(this,
                "Auto는 접속할 때 서버의 Minecraft 버전을 자동으로 감지합니다.",
                12, R.color.text_secondary);
        UiKit.margin(autoGuide, 2, 7, 2, 0);
        root.addView(autoGuide);

        Button save = UiKit.button(this, "저장", true);
        save.setOnClickListener(view -> save());
        LinearLayout.LayoutParams saveParams = UiKit.matchWrap();
        saveParams.topMargin = UiKit.dp(this, 22);
        root.addView(save, saveParams);

        if (editing) {
            Button delete = UiKit.button(this, "서버 삭제", false);
            delete.setTextColor(getColor(R.color.danger));
            delete.setOnClickListener(view -> confirmDelete());
            LinearLayout.LayoutParams deleteParams = UiKit.matchWrap();
            deleteParams.topMargin = UiKit.dp(this, 12);
            root.addView(delete, deleteParams);
        }
        return scroll;
    }

    private EditText addInput(LinearLayout parent, String label, String hint) {
        addLabel(parent, label);
        EditText input = UiKit.input(this, hint);
        parent.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        return input;
    }

    private void addLabel(LinearLayout parent, String label) {
        TextView view = UiKit.text(this, label, 13, R.color.text_secondary);
        UiKit.margin(view, 2, 18, 0, 7);
        parent.addView(view);
    }

    private void populate() {
        nameInput.setText(server.getName());
        hostInput.setText(server.getHost());
        portInput.setText(String.valueOf(server.getPort()));
        List<ProtocolSpec> versions = ProtocolRegistry.selectableVersions();
        for (int index = 0; index < versions.size(); index++) {
            if (versions.get(index).getId().equals(server.getVersionId())) {
                versionSpinner.setSelection(index);
                break;
            }
        }
    }

    private void save() {
        String name = nameInput.getText().toString().trim();
        String host = hostInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();

        if (name.isEmpty()) {
            nameInput.setError("표시 이름을 입력하세요.");
            return;
        }
        if (host.isEmpty() || host.contains("/")) {
            hostInput.setError("프로토콜 없이 서버 호스트명 또는 IP만 입력하세요.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException error) {
            portInput.setError("올바른 포트를 입력하세요.");
            return;
        }
        if (port < 1 || port > 65535) {
            portInput.setError("포트는 1~65535 범위여야 합니다.");
            return;
        }
        ProtocolSpec version = (ProtocolSpec) versionSpinner.getSelectedItem();
        server.setName(name);
        server.setHost(host);
        server.setPort(port);
        server.setVersionId(version.getId());
        repository.save(server);
        Toast.makeText(this, "서버를 저장했습니다.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("서버 삭제")
                .setMessage(server.getName() + " 서버 정보를 삭제할까요?")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    repository.delete(server.getId());
                    finish();
                })
                .show();
    }
}
