package com.padtools.display;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;

/** One app-scoped worker serializes pairing, display mutations and confirmation. */
final class DisplayController {
    interface Listener { void onChanged(); }
    interface Action { void run() throws Exception; }
    private final Context context;
    final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private volatile LocalAdb adb;
    private volatile Listener listener;
    volatile boolean busy, connected;
    volatile String message = "开启本机无线调试后，完成首次配对。";
    volatile boolean error;
    volatile DisplayState display;
    volatile String pendingToken;
    volatile long deadline;
    volatile String discoveredHost = "";
    volatile int discoveredPairPort, discoveredConnectPort;
    volatile String connectedEndpoint = "";
    volatile int pairingGeneration;

    DisplayController(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("pad-display", Context.MODE_PRIVATE);
        pendingToken = preferences.getString("pending_token", null);
        if (pendingToken != null)
            deadline = SystemClock.elapsedRealtime() + Math.max(0, preferences.getLong("pending_deadline", 0) - System.currentTimeMillis());
    }

    void attach(Listener listener) { this.listener = listener; changed(); }
    void detach(Listener listener) { if (this.listener == listener) this.listener = null; }
    private void changed() { main.post(() -> { Listener current = listener; if (current != null) current.onChanged(); }); }
    private void phase(String text) { message = text; log(text); changed(); }
    void note(String text) { message = text; error = false; changed(); }
    private void log(String line) {
        synchronized (logs) {
            logs.addFirst(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + line);
            while (logs.size() > 16) logs.removeLast();
        }
    }
    String logText() { synchronized (logs) { return String.join("\n", logs); } }
    String diagnosticReport() {
        return "Pad Display 1.0.3\n设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid：" + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\n连接：" + (connected ? "已连接" : "未连接")
                + "\n恢复记录：" + (pendingToken == null ? "无" : "存在")
                + "\n\n" + logText();
    }

    private void run(String label, Action action) {
        if (busy) return;
        busy = true; error = false; message = label; changed();
        log(label);
        worker.execute(() -> {
            try { action.run(); }
            catch (Exception e) {
                error = true;
                message = friendly(e);
                log(message);
                if (adb != null) connected = adb.isConnected();
            } finally { busy = false; changed(); }
        });
    }

    private static String friendly(Exception e) {
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) detail = e.getClass().getSimpleName();
        if (detail.startsWith("【")) return detail;
        if (detail.contains("Connection refused")) return "端口连接被拒绝，请检查无线调试开关与当前端口。";
        if (detail.contains("timed out") || detail.contains("Timeout")) return "连接超时，请保持配对窗口打开，并检查端口。";
        if (detail.contains("pair") || detail.contains("Pair") || detail.contains("Exchanging"))
            return "配对未完成，请重新打开系统配对窗口，输入新的配对端口和六位配对码。";
        return detail;
    }

    private LocalAdb manager() throws Exception { if (adb == null) adb = new LocalAdb(context); return adb; }
    static int port(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value > 0 && value <= 65535) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("端口需要在 1–65535 之间");
    }
    private static void validateHost(String host) {
        if (!LocalDiscovery.isLocal(host)) throw new IllegalArgumentException("请输入本机的 Wi-Fi IP，或 127.0.0.1；仅支持调整当前设备。");
    }

    void endpoint(String host, int port, boolean pairing) {
        discoveredHost = host;
        if (pairing) discoveredPairPort = port; else discoveredConnectPort = port;
        changed();
        if (!pairing && !connected && !busy && preferences.getBoolean("paired", false)) connectAuto();
    }

    void pair(String host, String portText, String code) {
        run("正在配对本机无线调试…", () -> {
            validateHost(host);
            int port = port(portText);
            if (!code.matches("[0-9]{6}")) throw new IllegalArgumentException("配对码需要为六位数字");
            manager().pair(host, port, code);
            preferences.edit().putBoolean("paired", true).putString("host", host).apply();
            pairingGeneration++;
            log("无线 ADB 配对成功");
            message = "配对成功。请使用无线调试首页的连接端口连接。";
            if (discoveredConnectPort > 0) {
                try { connectNow(discoveredHost, discoveredConnectPort); }
                catch (Exception ignored) { message = "配对成功。自动连接未完成，请填写无线调试首页的连接端口。"; }
            }
        });
    }

    void connect(String host, String portText) {
        run("正在连接本机 ADB…", () -> connectNow(host, port(portText)));
    }
    void connectAuto() {
        run("正在连接本机 ADB…", () -> {
            int port = discoveredConnectPort > 0 ? discoveredConnectPort : preferences.getInt("connect_port", 0);
            String host = discoveredConnectPort > 0 ? discoveredHost : preferences.getString("host", LocalDiscovery.defaultHost());
            if (port == 0) throw new IllegalArgumentException("暂未发现连接端口，请在无线调试首页查看 IP 地址和端口并手动填写。");
            connectNow(host, port);
        });
    }

    private void connectNow(String host, int port) throws Exception {
        validateHost(host);
        LocalAdb manager = manager();
        manager.disconnect();
        connected = false;
        if (!manager.connect(host, port)) throw new IOException("ADB 连接未建立，请确认已配对并使用连接端口。");
        String uid = shell("id -u").trim();
        if (!(uid.equals("2000") || uid.equals("0"))) {
            manager.disconnect();
            throw new IOException("当前连接没有 shell 权限");
        }
        connected = true;
        connectedEndpoint = host + ":" + port;
        preferences.edit().putString("host", host).putInt("connect_port", port).apply();
        recoverPending();
        display = readDisplay();
        message = pendingToken == null ? "已连接本机，可以调整显示参数。" : "上次修改仍等待确认，超时将自动恢复。";
        log("已连接 " + connectedEndpoint);
    }

    void refresh() {
        run("正在读取显示参数…", () -> {
            requireConnection(); recoverPending(); display = readDisplay(); message = "显示参数已刷新。";
        });
    }
    void disconnect() {
        run("正在断开连接…", () -> {
            if (pendingToken != null) throw new IllegalStateException("请先确认或撤销当前修改。");
            if (adb != null) adb.disconnect(); connected = false; message = "已断开，点击连接即可继续。";
        });
    }

    private void requireConnection() throws Exception {
        if (adb == null || !adb.isConnected()) { connected = false; throw new IOException("请先连接本机无线 ADB"); }
    }
    private DisplayState readDisplay() throws Exception { return DisplayState.parse(shell("读取系统显示参数", "wm size; wm density")); }

    void apply(DisplaySpec spec) {
        run("正在应用显示参数…", () -> {
            requireConnection();
            if (pendingToken != null) throw new IllegalStateException("请先确认或撤销当前修改。");
            phase("1/4 · 正在读取修改前的参数…");
            String previousRaw = shell("步骤 1/4 · 读取修改前参数", "wm size; wm density");
            DisplayState previous = DisplayState.parse(previousRaw);
            String token = UUID.randomUUID().toString().replace("-", "");
            pendingToken = token;
            deadline = SystemClock.elapsedRealtime() + GuardScript.TIMEOUT_SECONDS * 1000L;
            if (!preferences.edit().putString("pending_token", token).putString("pending_previous", previousRaw)
                    .putLong("pending_deadline", System.currentTimeMillis() + GuardScript.TIMEOUT_SECONDS * 1000L).commit()) {
                pendingToken = null;
                throw new IOException("无法保存恢复记录，修改已取消。");
            }
            changed();
            try {
                phase("2/4 · 正在启动设备端自动恢复…");
                if (!shell("步骤 2/4 · 启动自动恢复", GuardScript.arm(token, previous)).trim().equals("armed"))
                    throw new IOException("无法启动自动恢复，修改已取消。");
                phase("3/4 · 正在写入分辨率与 DPI…");
                shell("步骤 3/4 · 写入显示参数", GuardScript.apply(token, spec));
                phase("4/4 · 正在核对系统实际参数…");
                display = readDisplay();
                boolean matches = spec.reset ? display.overrideWidth == null && display.overrideDensity == null
                        : display.width() == spec.width && display.height() == spec.height && display.density() == spec.density;
                if (!matches) throw new IOException("系统未接受目标参数，正在恢复。");
                message = "修改已生效，请在倒计时内确认保留。";
                log(spec.reset ? "已恢复出厂显示参数，等待确认" : "已应用 " + spec.sizeArg() + " / " + spec.density + " DPI，等待确认");
            } catch (Exception failure) {
                try { finishNow(false); }
                catch (Exception rollback) {
                    message = "修改未完成。恢复记录已保留，请重新连接以确认恢复结果。";
                    log(message);
                    log("恢复错误：" + rollback.getMessage());
                }
                throw failure;
            }
        });
    }

    void finish(boolean keep) {
        run(keep ? "正在保留修改…" : "正在恢复原来的参数…", () -> finishNow(keep));
    }
    void expire() { if (!busy && connected && pendingToken != null) finish(false); }

    private void finishNow(boolean keep) throws Exception {
        requireConnection();
        String token = pendingToken;
        if (token == null) return;
        if (keep && SystemClock.elapsedRealtime() >= deadline) keep = false;
        DisplayState beforeFinish = readDisplay();
        DisplayState saved = DisplayState.parse(preferences.getString("pending_previous", ""));
        boolean unchanged = beforeFinish.width() == saved.width() && beforeFinish.height() == saved.height()
                && beforeFinish.density() == saved.density();
        String command = !keep && unchanged ? GuardScript.cancelUnchanged(token) : GuardScript.finish(token, keep);
        String result = shell(keep ? "保留修改" : "撤销未确认修改", command).trim();
        if (!(result.endsWith("kept") || result.endsWith("restored"))) {
            throw new IOException("未能确认恢复结果，请保持无线调试开启并重新连接。");
        }
        boolean kept = result.endsWith("kept");
        display = readDisplay();
        if (!kept) verifyRestored(display);
        clearPending();
        cleanup(token);
        message = kept ? "已保留修改。" : "已恢复修改前的分辨率和 DPI。";
        log(message);
    }

    private void recoverPending() throws Exception {
        String token = pendingToken;
        if (token == null) return;
        String status = shell("核对上次修改状态", GuardScript.status(token)).trim();
        if (status.contains("pending")) {
            if (SystemClock.elapsedRealtime() >= deadline || status.contains("restore_failed")) finishNow(false);
        } else if (status.endsWith("kept") || status.endsWith("restored")) {
            if (status.endsWith("restored")) verifyRestored(readDisplay());
            clearPending(); cleanup(token);
        } else {
            // /data/local/tmp may have been cleared or the device may have rebooted.
            DisplayState previous = DisplayState.parse(preferences.getString("pending_previous", ""));
            shell("恢复上次未确认修改", "wm size " + previous.restoreSize() + " && wm density " + previous.restoreDensity());
            verifyRestored(readDisplay());
            clearPending(); cleanup(token);
            log("已根据本地恢复记录还原上次未确认的修改");
        }
    }

    private void cleanup(String token) {
        try { shell("清理恢复记录", GuardScript.cleanup(token)); }
        catch (Exception e) { log("显示参数已确认，临时文件清理失败：" + e.getMessage()); }
    }

    private void verifyRestored(DisplayState state) {
        DisplayState previous = DisplayState.parse(preferences.getString("pending_previous", ""));
        if (state.width() != previous.width() || state.height() != previous.height() || state.density() != previous.density())
            throw new IllegalStateException("系统未恢复原来的参数，请保持连接并再次点击立即撤销。");
    }

    private void clearPending() throws IOException {
        if (!preferences.edit().remove("pending_token").remove("pending_previous").remove("pending_deadline").commit())
            throw new IOException("显示参数已更新，但恢复记录未能保存，请重试确认。");
        pendingToken = null; deadline = 0;
    }

    private String shell(String command) throws Exception {
        return shell("执行本机 ADB 命令", command);
    }

    private String shell(String label, String command) throws Exception {
        LocalAdb manager = manager();
        AdbConnection connection = manager.getAdbConnection();
        if (connection == null) throw new IOException("请先连接本机无线 ADB");
        // A random trailer distinguishes the shell exit code from normal command output.
        String marker = "PAD_EXIT_" + UUID.randomUUID().toString().replace("-", "");
        AtomicBoolean timedOut = new AtomicBoolean();
        AtomicReference<AdbStream> activeStream = new AtomicReference<>();
        ScheduledFuture<?> timeout = timeouts.schedule(() -> {
            timedOut.set(true);
            AdbStream stream = activeStream.get();
            if (stream != null) stream.abort();
            try { connection.close(); } catch (IOException ignored) { }
        }, 12, TimeUnit.SECONDS);
        try (AdbStream stream = manager.openStream("shell:" + ShellCommand.wrap(command, marker)); InputStream input = stream.openInputStream()) {
            activeStream.set(stream);
            return ShellCommand.readResult(input, marker);
        } catch (Exception e) {
            if (timedOut.get()) throw new IOException("【" + label + "】ADB 命令超时，连接已中止。请重新连接后重试。", e);
            throw new IOException("【" + label + "】\n" + e.getMessage(), e);
        } finally { timeout.cancel(false); }
    }
}
