package io.github.sjtt2.mjcomeback;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 模块配置界面。 */
public final class SettingsActivity extends Activity implements MjService.Listener {

    private SharedPreferences prefs;
    private SharedPreferences boundPrefs;
    private boolean remoteConnected;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = localPrefs();
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MjService.addListener(this);
        onServiceChanged(MjService.get() != null);
    }

    @Override
    protected void onStop() {
        MjService.removeListener(this);
        super.onStop();
    }

    @Override
    public void onServiceChanged(boolean connected) {
        runOnUiThread(() -> {
            if (statusView != null) {
                statusView.setText(statusText(connected));
            }
            if (connected == remoteConnected) return;
            remoteConnected = connected;
            SharedPreferences next = connected ? MjService.remotePrefs() : null;
            prefs = next != null ? next : localPrefs();
            build();
        });
    }

    private SharedPreferences localPrefs() {
        return getSharedPreferences(MjSettings.PREFS_NAME, MODE_PRIVATE);
    }

    private String statusText(boolean connected) {
        return connected
                ? "✅ 已连接 LSPosed 服务：这里的设置会同步到抖音"
                : "⚠️ 未连接 LSPosed 服务：设置仅在本页预览生效（请在 LSPosed 中启用本模块后重开）";
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void build() {
        if (prefs != boundPrefs) {
            boundPrefs = prefs;
            MjSettings.bind(prefs);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(this);
        desc.setText("在抖音 / TikTok 的评论区、私信输入框里发送 “mj”（或 mjmj、MJMJ…），"
                + "就会重新播放蜘蛛侠动画彩蛋。\n\n"
                + "基于 libxposed API 102（LSPosed 现代 API），不依赖服务端配置，完全本地重放原版素材。");
        desc.setTextSize(14f);
        root.addView(desc);

        statusView = new TextView(this);
        statusView.setText(statusText(remoteConnected));
        statusView.setTextSize(12f);
        statusView.setPadding(0, dp(10), 0, dp(6));
        root.addView(statusView);

        Switch swEnabled = new Switch(this);
        swEnabled.setText("启用彩蛋");
        swEnabled.setChecked(prefs.getBoolean(MjSettings.KEY_ENABLED, true));
        swEnabled.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(MjSettings.KEY_ENABLED, checked).apply());
        root.addView(swEnabled, matchWidth());

        Switch swSound = new Switch(this);
        swSound.setText("播放音效");
        swSound.setChecked(prefs.getBoolean(MjSettings.KEY_SOUND, true));
        swSound.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(MjSettings.KEY_SOUND, checked).apply());
        root.addView(swSound, matchWidth());

        TextView modeTitle = new TextView(this);
        modeTitle.setText("播放顺序");
        modeTitle.setTextSize(15f);
        modeTitle.setPadding(0, dp(16), 0, dp(4));
        root.addView(modeTitle);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton alternate = new RadioButton(this);
        alternate.setText("交替");
        alternate.setId(1001);
        RadioButton random = new RadioButton(this);
        random.setText("随机");
        random.setId(1002);
        group.addView(alternate);
        group.addView(random);
        int mode = prefs.getInt(MjSettings.KEY_MODE, MjSettings.MODE_ALTERNATE);
        group.check(mode == MjSettings.MODE_RANDOM ? 1002 : 1001);
        group.setOnCheckedChangeListener((g, checkedId) ->
                prefs.edit().putInt(MjSettings.KEY_MODE,
                        checkedId == 1002 ? MjSettings.MODE_RANDOM : MjSettings.MODE_ALTERNATE)
                        .apply());
        root.addView(group, matchWidth());

        TextView extraTitle = new TextView(this);
        extraTitle.setText("附加关键词（可选，逗号分隔，整段匹配才触发）");
        extraTitle.setTextSize(15f);
        extraTitle.setPadding(0, dp(16), 0, dp(4));
        root.addView(extraTitle);

        EditText extra = new EditText(this);
        extra.setInputType(InputType.TYPE_CLASS_TEXT);
        extra.setHint("例如：蜘蛛侠,spiderman");
        extra.setText(prefs.getString(MjSettings.KEY_EXTRA_KEYWORDS, ""));
        extra.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString(MjSettings.KEY_EXTRA_KEYWORDS,
                        s == null ? "" : s.toString()).apply();
            }
        });
        root.addView(extra, matchWidth());

        TextView tryTitle = new TextView(this);
        tryTitle.setText("试一试（输入 mj 后点「发送」才触发）");
        tryTitle.setTextSize(15f);
        tryTitle.setPadding(0, dp(16), 0, dp(4));
        root.addView(tryTitle);

        EditText tryInput = new EditText(this);
        tryInput.setHint("输入 mj");
        tryInput.setInputType(InputType.TYPE_CLASS_TEXT);
        tryInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                MjEngine.onTextChanged(tryInput, s);
            }
        });
        root.addView(tryInput, matchWidth());

        Button sendButton = new Button(this);
        sendButton.setText("发送");
        sendButton.setOnClickListener(v -> {
            // 模拟真实发送：先有“发送意图”，再清空输入框
            MjEngine.onUserSendIntent();
            tryInput.setText("");
        });
        root.addView(sendButton, matchWidth());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(20), 0, 0);

        Button previewDrop = new Button(this);
        previewDrop.setText("预览：吊下来");
        previewDrop.setOnClickListener(v -> preview(0, swSound.isChecked()));
        buttons.addView(previewDrop, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button previewSwing = new Button(this);
        previewSwing.setText("预览：荡秋千");
        previewSwing.setOnClickListener(v -> preview(1, swSound.isChecked()));
        buttons.addView(previewSwing, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons, matchWidth());

        TextView footer = new TextView(this);
        footer.setText("\n使用步骤：\n"
                + "1. 在 LSPosed 里启用本模块；\n"
                + "2. 作用域勾选“抖音”（或 TikTok）；\n"
                + "3. 强制停止并重新打开抖音；\n"
                + "4. 在评论 / 私信输入框输入 mj 并发送。\n\n"
                + "素材来源：qiu7c/Tiktok-MJ-for-Wechat 仓库中的原版双蒙版视频，"
                + "构建时已转码为带 Alpha 通道的动画 WebP。\n"
                + "仅供个人研究与怀旧，请勿商用。");
        footer.setTextSize(12f);
        footer.setPadding(0, dp(24), 0, 0);
        root.addView(footer);

        if (!MjOverlay.isSupported()) {
            TextView warn = new TextView(this);
            warn.setText("当前系统低于 Android 9，动画播放不可用。");
            warn.setTextSize(13f);
            root.addView(warn);
        }

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void preview(int index, boolean sound) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        MjOverlay.show((ViewGroup) getWindow().getDecorView(), this, index, sound);
    }
}
