package com.padtools.display;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Native, resizable tablet UI. No WebView, root or companion application. */
public final class MainActivity extends Activity implements DisplayController.Listener {
    private static final int BG = Color.rgb(245, 246, 242), INK = Color.rgb(29, 37, 32),
            MUTED = Color.rgb(107, 119, 109), LINE = Color.rgb(226, 231, 221),
            LIME = Color.rgb(215, 249, 147), GREEN = Color.rgb(70, 108, 29);
    private DisplayController controller;
    private LocalDiscovery discovery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText host, pairPort, pairCode, connectPort, width, height, dpi;
    private TextView connectionStatus, statusMessage, resolutionValue, densityValue, physicalValue, workspaceValue,
            previewValue, pendingCountdown, logValue, operationFeedback;
    private ProgressBar operationProgress;
    private LinearLayout pendingCard;
    private CheckBox ratioLock, syncDpi;
    private SeekBar densitySlider;
    private Button applyButton, resetButton, keepButton, originalPreset, uhdPreset;
    private final List<View> operationButtons = new ArrayList<>();
    private final List<View> draftControls = new ArrayList<>();
    private DisplayState loaded;
    private boolean editing, expireRequested;
    private String draftWidth, draftHeight, draftDpi, draftHost, draftPairPort, draftCode, draftConnectPort;
    private boolean draftRatio = true, draftSync = true;
    private int shownPairingGeneration;
    private String lastAutoHost = "";
    private int lastAutoPairPort, lastAutoConnectPort;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (controller.pendingToken != null) {
                long left = Math.max(0, controller.deadline - SystemClock.elapsedRealtime());
                pendingCountdown.setText(left > 0 ? "剩余 " + (left + 999) / 1000 + " 秒 · 未确认将自动恢复"
                        : "已到期 · 正在确认恢复结果");
                keepButton.setEnabled(left > 0 && !controller.busy && controller.connected);
                if (left == 0 && !expireRequested && controller.connected && !controller.busy) {
                    expireRequested = true; controller.expire();
                }
                if (left == 0 && !controller.connected) pendingCountdown.setText("已到期 · 重新连接后确认恢复结果");
            } else expireRequested = false;
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        controller = ((PadApplication) getApplication()).controller;
        shownPairingGeneration = controller.pairingGeneration;
        if (state != null) {
            draftWidth = state.getString("width"); draftHeight = state.getString("height"); draftDpi = state.getString("dpi");
            draftHost = state.getString("host"); draftPairPort = state.getString("pairPort");
            draftConnectPort = state.getString("connectPort");
            draftRatio = state.getBoolean("ratio", true); draftSync = state.getBoolean("sync", true);
        }
        build();
        controller.attach(this);
        discovery = new LocalDiscovery(this, (address, port, pairing) -> controller.endpoint(address, port, pairing));
        discovery.start();
        handler.post(tick);
    }

    @Override protected void onResume() {
        super.onResume();
        if (discovery != null) discovery.start();
        if (controller != null && controller.connected && !controller.busy) controller.refresh();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        controller.detach(this);
        if (discovery != null) discovery.stop();
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        capture(); build(); onChanged();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        capture();
        state.putString("width", draftWidth); state.putString("height", draftHeight); state.putString("dpi", draftDpi);
        state.putString("host", draftHost); state.putString("pairPort", draftPairPort); state.putString("connectPort", draftConnectPort);
        state.putBoolean("ratio", draftRatio); state.putBoolean("sync", draftSync);
        // Pairing codes are deliberately excluded from persistent Activity state.
        super.onSaveInstanceState(state);
    }
    private void capture() {
        draftWidth = value(width); draftHeight = value(height); draftDpi = value(dpi);
        draftHost = value(host); draftPairPort = value(pairPort); draftCode = value(pairCode); draftConnectPort = value(connectPort);
        draftRatio = ratioLock.isChecked(); draftSync = syncDpi.isChecked();
    }

    private void build() {
        operationButtons.clear(); draftControls.clear();
        boolean wide = getResources().getConfiguration().screenWidthDp >= 900;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets;
        });
        LinearLayout page = column(); page.setPadding(dp(wide ? 32 : 20), dp(24), dp(wide ? 32 : 20), dp(24));
        scroll.addView(page);
        LinearLayout brand = row();
        TextView logo = text("▣  PAD DISPLAY", 13, INK, true); logo.setLetterSpacing(.12f);
        brand.addView(logo, new LinearLayout.LayoutParams(0, -2, 1));
        brand.addView(text("ON-DEVICE  /  v1.0.3", 10, MUTED, true)); page.addView(brand);
        TextView title = text("显示调校", 32, INK, true); add(page, title, 12);
        add(page, text("调整逻辑分辨率与 DPI，让每一寸屏幕更合适。", 14, MUTED, false), 4);

        LinearLayout body = new LinearLayout(this); body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setGravity(Gravity.TOP); add(page, body, 24);
        LinearLayout connection = connectionCard();
        LinearLayout right = column();
        if (wide) {
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(dp(330), -2); leftParams.setMarginEnd(dp(20));
            body.addView(connection, leftParams); body.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        } else { body.addView(connection); add(body, right, 16); }
        pendingCard = pendingCard(); right.addView(pendingCard);
        add(right, metricsCard(), 0);
        add(right, controlsCard(), 16);
        Button history = button("查看操作记录", false, () -> new AlertDialog.Builder(this).setTitle("操作记录")
                .setMessage(controller.diagnosticReport())
                .setPositiveButton("复制诊断", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Pad Display 诊断", controller.diagnosticReport()));
                    toast("诊断信息已复制");
                }).setNegativeButton("关闭", null).show());
        add(page, history, 14);
        logValue = text("仅连接当前设备 · 无需 root · Android 11+", 11, MUTED, false); add(page, logValue, 12);
        setContentView(scroll); scroll.requestApplyInsets();
    }

    private LinearLayout connectionCard() {
        LinearLayout card = card(Color.WHITE);
        card.addView(text("01  /  本机连接", 11, MUTED, true));
        add(card, text("无线调试", 23, INK, true), 10);
        connectionStatus = text("●  尚未连接", 12, MUTED, true); add(card, connectionStatus, 10);
        statusMessage = text(controller.message, 12, MUTED, false); add(card, statusMessage, 8);
        add(card, button("打开系统无线调试  ↗", false, this::openSettings), 16);
        add(card, text("首次配对", 14, INK, true), 20);
        add(card, text("与设置应用分屏，保持六位配对码窗口打开。", 11, MUTED, false), 4);
        host = field("本机 IP", draftHost != null ? draftHost : controller.preferences.getString("host", LocalDiscovery.defaultHost()), false);
        add(card, labeled("本机 IP 地址", host), 12);
        LinearLayout pairing = row();
        pairPort = field("配对窗口端口", draftPairPort, true);
        pairCode = field("六位配对码", draftCode, true);
        pairCode.setTransformationMethod(PasswordTransformationMethod.getInstance()); pairCode.setSaveEnabled(false);
        weighted(pairing, labeled("配对端口", pairPort), 1, 8); weighted(pairing, labeled("配对码", pairCode), 1, 0);
        add(card, pairing, 10);
        add(card, taskButton("配对本机", true, () -> controller.pair(value(host), value(pairPort), value(pairCode))), 12);
        add(card, text("已配对后连接", 14, INK, true), 20);
        connectPort = field("无线调试首页端口", draftConnectPort, true);
        add(card, labeled("连接端口 · 与配对端口不同", connectPort), 10);
        LinearLayout connectActions = row();
        weighted(connectActions, taskButton("连接", true, () -> controller.connect(value(host), value(connectPort))), 1, 8);
        weighted(connectActions, taskButton("自动连接", false, controller::connectAuto), 1, 0); add(card, connectActions, 12);
        add(card, button("配对与连接指南", false, this::showGuide), 10);
        return card;
    }

    private LinearLayout pendingCard() {
        LinearLayout card = card(Color.rgb(255, 242, 205));
        card.setVisibility(View.GONE);
        card.addView(text("保留这个设置？", 22, INK, true));
        pendingCountdown = text("未确认将自动恢复", 13, INK, false); add(card, pendingCountdown, 6);
        LinearLayout actions = row();
        keepButton = taskButton("保留修改", true, () -> controller.finish(true));
        weighted(actions, keepButton, 1, 10); weighted(actions, taskButton("立即撤销", false, () -> controller.finish(false)), 1, 0);
        add(card, actions, 12);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(16);
        card.setLayoutParams(params); return card;
    }

    private LinearLayout metricsCard() {
        LinearLayout card = card(INK);
        LinearLayout heading = row();
        weighted(heading, text("当前显示", 13, LIME, true), 1, 0);
        Button refresh = taskButton("刷新", false, controller::refresh); refresh.setTextColor(LIME);
        refresh.setBackground(background(Color.rgb(47, 57, 47), Color.rgb(47, 57, 47), 12));
        heading.addView(refresh); card.addView(heading);
        LinearLayout metrics = row();
        LinearLayout resolution = column(), density = column();
        resolutionValue = text("— × —", 29, Color.WHITE, true); resolution.addView(resolutionValue);
        add(resolution, text("逻辑分辨率 / px", 11, Color.rgb(166, 181, 162), false), 4);
        densityValue = text("—", 29, Color.WHITE, true); density.addView(densityValue);
        add(density, text("显示密度 / DPI", 11, Color.rgb(166, 181, 162), false), 4);
        weighted(metrics, resolution, 2, 16); weighted(metrics, density, 1, 0); add(card, metrics, 16);
        physicalValue = text("连接后读取系统原始参数", 11, Color.rgb(166, 181, 162), false); add(card, physicalValue, 16);
        workspaceValue = text("", 11, LIME, false); add(card, workspaceValue, 6);
        return card;
    }

    private LinearLayout controlsCard() {
        LinearLayout card = card(Color.WHITE);
        card.addView(text("02  /  显示参数", 11, MUTED, true));
        add(card, text("逻辑分辨率", 23, INK, true), 10);
        add(card, text("以屏幕自然方向填写宽高，不随当前横竖屏旋转。", 11, MUTED, false), 5);
        LinearLayout presets = row();
        originalPreset = button("原始分辨率", true, () -> preset(false));
        uhdPreset = button("标准 4K\n3840 × 2160", false, () -> preset(true));
        originalPreset.setMinHeight(dp(60)); uhdPreset.setMinHeight(dp(60));
        draftControls.add(originalPreset); draftControls.add(uhdPreset);
        weighted(presets, originalPreset, 1, 10); weighted(presets, uhdPreset, 1, 0);
        add(card, presets, 14);
        LinearLayout dimensions = row();
        width = field("宽度", draftWidth, true); height = field("高度", draftHeight, true);
        weighted(dimensions, labeled("宽度 / px", width), 1, 10);
        TextView multiply = text("×", 19, MUTED, false); dimensions.addView(multiply); multiply.setPadding(0, dp(20), dp(10), 0);
        weighted(dimensions, labeled("高度 / px", height), 1, 0); add(card, dimensions, 12);
        ratioLock = new CheckBox(this); ratioLock.setText("锁定原始屏幕比例"); ratioLock.setTextColor(MUTED); ratioLock.setTextSize(12);
        ratioLock.setChecked(draftRatio); ratioLock.setMinHeight(dp(48)); add(card, ratioLock, 4);
        draftControls.add(ratioLock);
        syncDpi = new CheckBox(this); syncDpi.setText("预设同步 DPI，保持界面大小"); syncDpi.setTextSize(12); syncDpi.setTextColor(MUTED);
        syncDpi.setChecked(draftSync); syncDpi.setMinHeight(dp(48)); add(card, syncDpi, 0); draftControls.add(syncDpi);
        add(card, text("显示密度", 19, INK, true), 18);
        add(card, text("DPI 越小，界面元素越小，可显示更多内容。", 11, MUTED, false), 5);
        LinearLayout density = row();
        densitySlider = new SeekBar(this); densitySlider.setMin(120); densitySlider.setMax(640); densitySlider.setProgress(240);
        densitySlider.setContentDescription("显示密度 DPI"); densitySlider.setMinimumHeight(dp(48));
        weighted(density, densitySlider, 1, 10);
        dpi = field("DPI", draftDpi, true); dpi.setMinWidth(dp(86)); density.addView(dpi, new LinearLayout.LayoutParams(dp(92), dp(52)));
        add(card, density, 10); draftControls.add(densitySlider);
        previewValue = text("连接后可编辑并预览工作区大小。", 12, GREEN, false); add(card, previewValue, 12);
        operationFeedback = text("请先连接本机无线 ADB。", 12, MUTED, false); add(card, operationFeedback, 12);
        operationFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        operationProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        operationProgress.setIndeterminate(true); operationProgress.setVisibility(View.GONE); add(card, operationProgress, 6);
        applyButton = taskButton("应用更改", true, this::applyDraft); add(card, applyButton, 18);
        resetButton = taskButton("恢复系统默认", false, () -> controller.apply(DisplaySpec.defaults())); add(card, resetButton, 8);
        add(card, text("应用后需在 30 秒内确认，否则自动恢复修改前的参数。", 11, MUTED, false), 10);
        draftControls.add(width); draftControls.add(height); draftControls.add(dpi);
        watch(width, () -> dimensionsChanged(true)); watch(height, () -> dimensionsChanged(false));
        watch(dpi, () -> {
            if (editing) return;
            editing = true;
            try { densitySlider.setProgress(Integer.parseInt(value(dpi))); } catch (NumberFormatException ignored) { }
            editing = false; preview();
        });
        densitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                if (fromUser) { editing = true; dpi.setText(Integer.toString(progress)); editing = false; preview(); }
            }
            @Override public void onStartTrackingTouch(SeekBar slider) { }
            @Override public void onStopTrackingTouch(SeekBar slider) { }
        });
        if (loaded != null && draftWidth == null) load(loaded);
        preview(); return card;
    }

    private void dimensionsChanged(boolean changedWidth) {
        if (editing) return;
        if (ratioLock.isChecked() && controller.display != null) {
            DisplayState state = controller.display;
            editing = true;
            try {
                if (changedWidth) height.setText(Integer.toString((int) Math.round(Double.parseDouble(value(width)) * state.physicalHeight / state.physicalWidth)));
                else width.setText(Integer.toString((int) Math.round(Double.parseDouble(value(height)) * state.physicalWidth / state.physicalHeight)));
            } catch (NumberFormatException ignored) { }
            editing = false;
        }
        preview();
    }
    private void preset(boolean fourK) {
        DisplayState state = controller.display;
        if (state == null) return;
        boolean portrait = state.physicalHeight > state.physicalWidth;
        int targetWidth = fourK ? (portrait ? 2160 : 3840) : state.physicalWidth;
        int targetHeight = fourK ? (portrait ? 3840 : 2160) : state.physicalHeight;
        double scale = (double) Math.min(targetWidth, targetHeight) / Math.min(state.physicalWidth, state.physicalHeight);
        editing = true;
        width.setText(Integer.toString(targetWidth));
        height.setText(Integer.toString(targetHeight));
        if (fourK && (long) targetWidth * state.physicalHeight != (long) targetHeight * state.physicalWidth)
            ratioLock.setChecked(false);
        if (syncDpi.isChecked()) dpi.setText(Integer.toString(Math.max(120, Math.min(640, (int) Math.round(state.physicalDensity * scale)))));
        try { densitySlider.setProgress(Integer.parseInt(value(dpi))); } catch (NumberFormatException ignored) { }
        editing = false; preview();
    }
    private DisplaySpec draft() { return new DisplaySpec(Integer.parseInt(value(width)), Integer.parseInt(value(height)), Integer.parseInt(value(dpi))); }
    private void applyDraft() {
        try { controller.apply(draft()); }
        catch (NumberFormatException e) { toast("请填写完整的宽度、高度和 DPI"); }
        catch (IllegalArgumentException e) { toast(e.getMessage()); }
    }
    private void preview() {
        if (previewValue == null) return;
        try {
            DisplaySpec spec = draft();
            DisplayState state = controller.display;
            boolean original = state != null && spec.width == state.physicalWidth && spec.height == state.physicalHeight;
            boolean uhd = Math.max(spec.width, spec.height) == 3840 && Math.min(spec.width, spec.height) == 2160;
            originalPreset.setBackground(background(original ? LIME : BG, original ? LIME : LINE, 12));
            uhdPreset.setBackground(background(uhd && !original ? LIME : BG, uhd && !original ? LIME : LINE, 12));
            previewValue.setText("工作区 ≈ " + spec.width * 160 / spec.density + " × " + spec.height * 160 / spec.density
                    + " dp · 最小宽度 " + spec.smallestWidthDp() + " dp"
                    + (spec.smallestWidthDp() < 600 ? "\n部分应用可能切换到手机布局。" : ""));
        } catch (RuntimeException ignored) { previewValue.setText("宽高 320–7680 px · DPI 120–640"); }
    }
    private void load(DisplayState state) {
        editing = true;
        width.setText(Integer.toString(state.width())); height.setText(Integer.toString(state.height())); dpi.setText(Integer.toString(state.density()));
        densitySlider.setProgress(state.density()); editing = false; preview();
    }

    @Override public void onChanged() {
        if (isFinishing() || isDestroyed()) return;
        connectionStatus.setText(controller.connected ? "●  已连接本机" : controller.busy ? "◌  正在处理" : "○  尚未连接");
        connectionStatus.setTextColor(controller.connected ? GREEN : MUTED);
        statusMessage.setText(controller.message); statusMessage.setTextColor(controller.error ? Color.rgb(161, 58, 43) : MUTED);
        operationFeedback.setText(controller.message);
        operationFeedback.setTextColor(controller.error ? Color.rgb(161, 58, 43) : controller.busy ? GREEN : MUTED);
        operationProgress.setVisibility(controller.busy ? View.VISIBLE : View.GONE);
        for (View button : operationButtons) button.setEnabled(!controller.busy);
        boolean editable = controller.connected && !controller.busy && controller.pendingToken == null;
        for (View control : draftControls) { control.setEnabled(editable); control.setAlpha(editable ? 1 : .5f); }
        applyButton.setEnabled(editable); resetButton.setEnabled(editable);
        applyButton.setText(controller.busy ? "正在处理…" : "应用更改");
        applyButton.setAlpha(editable ? 1 : .5f); resetButton.setAlpha(editable ? 1 : .5f);
        boolean revealConfirmation = controller.pendingToken != null && pendingCard.getVisibility() != View.VISIBLE;
        pendingCard.setVisibility(controller.pendingToken == null ? View.GONE : View.VISIBLE);
        if (revealConfirmation) pendingCard.post(() -> pendingCard.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, pendingCard.getWidth(), pendingCard.getHeight()), false));
        keepButton.setEnabled(controller.connected && !controller.busy && SystemClock.elapsedRealtime() < controller.deadline);
        if (controller.display != null) {
            DisplayState state = controller.display;
            resolutionValue.setText(state.width() + " × " + state.height()); densityValue.setText(Integer.toString(state.density()));
            physicalValue.setText("原始 " + state.physicalWidth + " × " + state.physicalHeight + " px  /  " + state.physicalDensity + " DPI");
            workspaceValue.setText("最小宽度 " + state.smallestWidthDp() + " dp" + (controller.connected ? "  ·  " + controller.connectedEndpoint : "  ·  上次读取"));
            if (loaded != state) { loaded = state; load(state); }
        }
        if (!controller.discoveredHost.isEmpty() && !host.hasFocus()
                && (value(host).isEmpty() || value(host).equals(lastAutoHost) || value(host).equals(LocalDiscovery.defaultHost()))) {
            host.setText(controller.discoveredHost); lastAutoHost = controller.discoveredHost;
        }
        if (controller.discoveredPairPort > 0 && !pairPort.hasFocus()
                && (value(pairPort).isEmpty() || value(pairPort).equals(Integer.toString(lastAutoPairPort)))) {
            pairPort.setText(Integer.toString(controller.discoveredPairPort)); lastAutoPairPort = controller.discoveredPairPort;
        }
        if (controller.discoveredConnectPort > 0 && !connectPort.hasFocus()
                && (value(connectPort).isEmpty() || value(connectPort).equals(Integer.toString(lastAutoConnectPort)))) {
            connectPort.setText(Integer.toString(controller.discoveredConnectPort)); lastAutoConnectPort = controller.discoveredConnectPort;
        }
        if (shownPairingGeneration != controller.pairingGeneration) {
            shownPairingGeneration = controller.pairingGeneration; pairCode.setText(""); draftCode = null;
        }
        preview();
    }

    private void openSettings() {
        // This AOSP activity is available on many devices; OEMs may expose only Developer options.
        try { startActivity(new Intent().setClassName("com.android.settings", "com.android.settings.Settings$WirelessDebuggingActivity")); }
        catch (ActivityNotFoundException | SecurityException e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (ActivityNotFoundException e2) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        }
    }
    private void showGuide() {
        new AlertDialog.Builder(this).setTitle("连接本机无线调试")
                .setMessage("1. 在系统设置中开启开发者选项和无线调试，设备需连接 Wi-Fi。\n\n"
                        + "2. 将本应用与设置分屏。点击“使用配对码配对设备”，保持配对窗口打开。\n\n"
                        + "3. 在本应用填写窗口中的本机 IP、配对端口和六位配对码，点击“配对本机”。自动发现成功时只需填写配对码。\n\n"
                        + "4. 返回无线调试首页，将“IP 地址和端口”中的端口填入连接端口，点击连接。配对端口和连接端口不同。\n\n"
                        + "5. 调整宽高和 DPI，应用后在倒计时内确认。重启或重新开启无线调试后端口可能变化，需要重新连接。")
                .setPositiveButton("知道了", null).show();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private LinearLayout card(int color) {
        LinearLayout layout = column(); layout.setPadding(dp(22), dp(22), dp(22), dp(22));
        layout.setBackground(background(color, color == Color.WHITE ? LINE : color, 22)); return layout;
    }
    private GradientDrawable background(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), stroke); return drawable;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setFontFeatureSettings("tnum"); text.setLineSpacing(dp(3), 1);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    private EditText field(String hint, String value, boolean numeric) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setTextSize(15); field.setTextColor(INK); field.setHintTextColor(MUTED);
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setHint(hint); field.setContentDescription(hint); field.setText(value == null ? "" : value);
        field.setPadding(dp(12), 0, dp(12), 0); field.setBackground(background(BG, LINE, 12));
        field.setMinimumHeight(dp(52)); field.setId(View.generateViewId()); return field;
    }
    private LinearLayout labeled(String label, EditText field) {
        LinearLayout group = column(); group.addView(text(label, 11, MUTED, false)); add(group, field, 6); return group;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(13); button.setTextColor(INK);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setStateListAnimator(null); button.setBackground(background(primary ? LIME : BG, primary ? LIME : LINE, 12));
        button.setOnClickListener(view -> action.run()); return button;
    }
    private Button taskButton(String label, boolean primary, Runnable action) { Button button = button(label, primary, action); operationButtons.add(button); return button; }
    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); parent.addView(child, params);
    }
    private void weighted(LinearLayout parent, View child, float weight, int endMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight); params.setMarginEnd(dp(endMargin)); parent.addView(child, params);
    }
    private static String value(EditText edit) { return edit == null ? "" : edit.getText().toString().trim(); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void watch(EditText field, Runnable action) {
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable s) { }
        });
    }
}
