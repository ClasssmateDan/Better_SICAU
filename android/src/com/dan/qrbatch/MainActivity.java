package com.dan.qrbatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.dan.qrbatch.core.*;
import com.dan.qrbatch.network.ChaoxingClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** A foreground-only app. Nothing signs in or contacts the network on launch. */
public final class MainActivity extends Activity {
    private static final int SCAN = 501, IMAGE = 502;
    private static final int INK = SigninTheme.INK, MUTED = SigninTheme.MUTED, ACCENT = SigninTheme.ACCENT;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final Map<String, List<ActivityContext>> prepared = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();
    private final Map<String, Integer> statusColors = new LinkedHashMap<>();
    private SessionStore store;
    private SessionStore.Snapshot snapshot = new SessionStore.Snapshot();
    private final ChaoxingClient client = new ChaoxingClient();
    private volatile BatchRunner runner;
    private volatile boolean busy;
    private boolean foreground;
    private boolean loaded;
    private boolean preparing;
    private long preparationStarted;
    private String preparationDetail = "";
    private AlertDialog activeLogin;
    private TextView summary, queueStatus, accountNotice, prepareNotice;
    private ProgressBar progress;
    private LinearLayout accountsPanel;
    private Button add, prepare, scan, image, paste, stop, demo, prepareStop;
    private final Runnable preparationTicker = new Runnable() {
        @Override public void run() {
            if (!preparing) return;
            renderPreparationProgress(); prepareNotice.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(SigninTheme.BACKGROUND);
        getWindow().setNavigationBarColor(SigninTheme.BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        store = new SessionStore(this);
        buildUi();
        setBusy(true, "正在读取本地加密账号…");
        worker.execute(() -> {
            try {
                SessionStore.Snapshot loadedData = store.load();
                synchronized (stateLock) { snapshot = loadedData; }
                ui(() -> { loaded = true; setBusy(false, "先选择账号并准备签到，再扫描当前二维码。"); renderAccounts(); });
            } catch (Exception error) {
                ui(() -> {
                    setBusy(false, "本地会话无法解密，请重新添加账号。");
                    new AlertDialog.Builder(this).setTitle("无法读取本地账号")
                            .setMessage("设备密钥可能已变化。保留现有数据可稍后重试；重置会删除本机保存的会话和处理记录。")
                            .setNegativeButton("保留并退出", (d, w) -> finish())
                            .setPositiveButton("重置本地数据", (d, w) -> resetStore()).setCancelable(false).show();
                });
            }
        });
    }

    private void buildUi() {
        LinearLayout root = column(); root.setTag("learningpass_root"); root.setBackgroundColor(SigninTheme.BACKGROUND); SigninTheme.insets(root);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(16), dp(10), dp(20), dp(10));
        Button back = button("‹ 返回", false); back.setTag("learningpass_back"); back.setContentDescription("返回课程表");
        back.setOnClickListener(v -> finish()); header.addView(back, new LinearLayout.LayoutParams(dp(80), dp(46)));
        TextView title = text("学习通签到", 21, INK); title.setTypeface(null, Typeface.BOLD); title.setPadding(dp(16), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(header);
        View divider = new View(this); divider.setBackgroundColor(SigninTheme.BORDER); root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        ScrollView scroll = new ScrollView(this); scroll.setTag("learningpass_scroll"); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout body = column(); body.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        body.addView(text("每个账号使用独立会话。先准备活动，再扫描当前签到二维码。", 13, MUTED));

        LinearLayout accountCard = card(body, "01  已保存账号");
        summary = text("尚未添加账号", 12, MUTED); accountCard.addView(summary);
        accountNotice = text("", 13, ACCENT); accountNotice.setVisibility(View.GONE);
        accountNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); accountCard.addView(accountNotice);
        accountsPanel = column(); accountCard.addView(accountsPanel);
        add = button("＋ 添加账号", false); add.setTag("add_account"); add.setOnClickListener(v -> loginDialog(null)); accountCard.addView(add);
        accountCard.addView(text("按列表顺序执行；点击账号右侧的管理可重新登录或删除。", 11, MUTED));

        LinearLayout taskCard = card(body, "02  准备与扫码");
        prepare = button("准备所选账号的签到活动", false); prepare.setOnClickListener(v -> prepareAccounts()); taskCard.addView(prepare);
        prepareNotice = text("", 12, ACCENT); prepareNotice.setVisibility(View.GONE); taskCard.addView(prepareNotice);
        prepareStop = button("停止准备", false); prepareStop.setVisibility(View.GONE);
        prepareStop.setOnClickListener(v -> {
            stopRequested.set(true); prepareStop.setEnabled(false);
            preparationDetail = "正在停止，等待已发出的查询结束…"; renderPreparationProgress();
        }); taskCard.addView(prepareStop);
        taskCard.addView(text("最多同时准备 2 个账号，每个账号最多并行查询 3 门课程。扫码后仍按账号列表顺序提交。", 11, MUTED));
        scan = button("扫码并依次签到", true); scan.setOnClickListener(v -> {
            if (checkReady()) startActivityForResult(new Intent(this, CameraScanActivity.class), SCAN);
        }); taskCard.addView(scan);
        LinearLayout alternatives = new LinearLayout(this); alternatives.setOrientation(LinearLayout.HORIZONTAL);
        image = button("选图并签到", false); image.setOnClickListener(v -> {
            if (!checkReady()) return;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT); pick.setType("image/*"); pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(pick, IMAGE);
        });
        paste = button("粘贴码并签到", false); paste.setOnClickListener(v -> pasteDialog());
        alternatives.addView(image, new LinearLayout.LayoutParams(0, -2, 1)); alternatives.addView(paste, new LinearLayout.LayoutParams(0, -2, 1));
        taskCard.addView(alternatives);
        taskCard.addView(text("动态码过期会暂停后续账号；补扫新码时跳过已完成账号。", 11, MUTED));

        LinearLayout resultCard = card(body, "03  执行进度");
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT)); progress.setProgressTintList(ColorStateList.valueOf(ACCENT)); progress.setVisibility(View.GONE); resultCard.addView(progress);
        queueStatus = text("等待操作", 13, INK); gap(queueStatus, 8, 8); resultCard.addView(queueStatus);
        stop = button("停止后续账号", false); stop.setVisibility(View.GONE); stop.setOnClickListener(v -> {
            stopRequested.set(true); BatchRunner active = runner; if (active != null) active.cancel();
            if (preparing) { prepareStop.setEnabled(false); preparationDetail = "正在停止，等待已发出的查询结束…"; renderPreparationProgress(); }
            queueStatus.setText("将在当前请求结束后停止，不会重复发送当前请求。"); stop.setEnabled(false);
        }); resultCard.addView(stop);
        demo = button("离线演示队列", false); demo.setOnClickListener(v -> demo()); resultCard.addView(demo);
        TextView note = text("会话通过设备密钥加密保存，密码不落盘。扫码链接仅被解析，不会打开。\n接口兼容性尚未真实验证；结果待确认时，请先在官方应用核对。", 11, MUTED);
        gap(note, 18, 10); body.addView(note);
    }

    private void renderAccounts() {
        accountsPanel.removeAllViews();
        List<Account> accounts;
        synchronized (stateLock) { accounts = new ArrayList<>(snapshot.accounts); }
        int selected = 0;
        for (Account account : accounts) {
            if (account.selected) selected++;
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(8), 0, dp(8));
            CheckBox select = new CheckBox(this); select.setButtonTintList(ColorStateList.valueOf(ACCENT)); select.setChecked(account.selected); select.setEnabled(!busy);
            select.setContentDescription("选择账号 " + account.name);
            select.setOnCheckedChangeListener((v, checked) -> {
                synchronized (stateLock) { account.selected = checked; }
                worker.execute(this::persistOrWarn); renderAccounts();
            }); row.addView(select);
            LinearLayout labels = column();
            labels.addView(text(account.name + " · " + mask(account.phone), 14, INK));
            String detail = statuses.get(account.uid);
            if (detail == null) detail = hasUncertain(account.uid) ? "有结果待核对，请点管理" : "待准备";
            labels.addView(text(detail, 11, statusColors.containsKey(account.uid) ? statusColors.get(account.uid) : MUTED));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            Button manage = button("管理", false); manage.setMinWidth(0); manage.setMinimumWidth(0); manage.setTextSize(11); manage.setEnabled(!busy);
            manage.setOnClickListener(v -> manage(account)); row.addView(manage, new LinearLayout.LayoutParams(dp(62), -2));
            accountsPanel.addView(row);
        }
        summary.setText(accounts.size() + " 个账号 · 已选 " + selected + " 个");
        updateButtons();
    }

    private void loginDialog(Account previous) {
        if (busy || !loaded) return;
        activeLogin = LoginDialog.show(this, previous == null ? null : previous.phone, (number, secret, realName, feedback) -> {
            setBusy(true, "正在登录并保存会话…"); stop.setVisibility(View.GONE);
            showAccountNotice("正在登录并保存会话…", ACCENT);
            worker.execute(() -> {
                boolean saving = false;
                try {
                    Account account = client.login(number, secret, realName, feedback::progress);
                    saving = true; feedback.progress("登录成功，正在加密保存本机会话…");
                    synchronized (stateLock) {
                        // Commit a candidate first. A failed disk/Keystore write must not add a phantom account.
                        SessionStore.Snapshot candidate = new SessionStore.Snapshot();
                        candidate.accounts.addAll(snapshot.accounts);
                        candidate.completed.addAll(snapshot.completed); candidate.uncertain.addAll(snapshot.uncertain);
                        int index = -1;
                        for (int i = 0; i < candidate.accounts.size(); i++) if (candidate.accounts.get(i).uid.equals(account.uid)
                                || candidate.accounts.get(i).phone.equals(account.phone)) { index = i; break; }
                        if (index < 0) candidate.accounts.add(account);
                        else { account.selected = candidate.accounts.get(index).selected; candidate.accounts.set(index, account); }
                        store.save(candidate); snapshot = candidate;
                    }
                    ui(() -> {
                        prepared.remove(account.uid); statuses.put(account.uid, "已登录，待准备"); statusColors.remove(account.uid);
                        setBusy(false, "账号已保存。准备活动后再扫码。"); renderAccounts();
                        showAccountNotice("账号已登录并保存。", SigninTheme.SUCCESS); feedback.success(); toast("账号已保存");
                    });
                } catch (Exception error) {
                    String detail = saving ? "登录成功，但本地加密保存失败。账号未更新，请检查设备存储后重试。" : message(error);
                    ui(() -> { setBusy(false, detail); renderAccounts(); showAccountNotice(detail, SigninTheme.ERROR); feedback.error(detail); });
                }
            });
        });
    }

    private void showAccountNotice(String message, int color) {
        accountNotice.setText(message); accountNotice.setTextColor(color); accountNotice.setVisibility(View.VISIBLE);
    }

    private void prepareAccounts() {
        if (busy || !loaded) return;
        List<Account> selected = selectedAccounts();
        if (selected.isEmpty()) { toast("请先添加并选择账号"); return; }
        stopRequested.set(false); prepared.clear(); setBusy(true, "正在并行查询所选账号的活动并预签…");
        preparing = true; preparationStarted = SystemClock.elapsedRealtime(); preparationDetail = "正在开始准备，最多并行 2 个账号…";
        prepareNotice.setVisibility(View.VISIBLE); prepareStop.setVisibility(View.VISIBLE); prepareStop.setEnabled(true);
        renderPreparationProgress(); prepareNotice.postDelayed(preparationTicker, 1000);
        worker.execute(() -> {
            int ready = 0; String failure = "";
            try {
                long[] lastProgress = new long[selected.size()];
                List<PreparationRunner.Result> outcomes = new PreparationRunner(client::prepare).run(selected, stopRequested::get, new PreparationRunner.Listener() {
                    @Override public void progress(int index, int total, Account account, String detail) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastProgress[index] < 200 && detail.startsWith("正在查询课程活动")) return;
                        lastProgress[index] = now;
                        ui(() -> {
                            if (stopRequested.get()) return;
                            preparationDetail = "账号 " + (index + 1) + "/" + total + " · " + detail;
                            statuses.put(account.uid, detail); renderPreparationProgress(); renderAccounts();
                        });
                    }
                    @Override public void finished(int finished, int total, PreparationRunner.Result outcome) {
                        ui(() -> {
                            String uid = outcome.account.uid;
                            if (outcome.error == null) {
                                prepared.put(uid, outcome.activities);
                                statuses.put(uid, outcome.activities.isEmpty() ? "未发现进行中的二维码签到" : "已准备 " + outcome.activities.size() + " 个二维码活动");
                                statusColors.remove(uid);
                            } else {
                                statuses.put(uid, stopRequested.get() ? "准备已停止" : message(outcome.error));
                                statusColors.put(uid, SigninTheme.ERROR);
                            }
                            preparationDetail = "已处理 " + finished + "/" + total + " 个账号";
                            renderPreparationProgress(); renderAccounts();
                        });
                    }
                });
                // Every account and course worker has finished before the shared encrypted snapshot is written.
                synchronized (stateLock) { store.save(snapshot); }
                for (PreparationRunner.Result outcome : outcomes) if (outcome.error == null && !outcome.activities.isEmpty()) ready++;
            } catch (Exception error) { failure = message(error); }
            final int readyCount = ready; final String error = failure;
            ui(() -> {
                preparing = false; prepareNotice.removeCallbacks(preparationTicker); prepareStop.setVisibility(View.GONE);
                if (!error.isEmpty()) { prepared.clear(); preparationDetail = "准备未完成：" + error; }
                else preparationDetail = stopRequested.get() ? "准备已停止，已完成准备的账号仍可使用。" : "准备完成：" + readyCount + " 个账号有进行中的二维码活动。";
                renderPreparationProgress(); setBusy(false, prepareNotice.getText().toString()); renderAccounts();
            });
        });
    }

    private void renderPreparationProgress() {
        long seconds = Math.max(0, (SystemClock.elapsedRealtime() - preparationStarted) / 1000);
        prepareNotice.setText(preparationDetail + "\n已用时 " + seconds + " 秒");
    }

    private boolean checkReady() {
        if (busy || !loaded) return false;
        List<Account> selected = selectedAccounts();
        if (selected.isEmpty()) { toast("请先选择账号"); return false; }
        for (Account account : selected) if (prepared.containsKey(account.uid) && !prepared.get(account.uid).isEmpty()) return true;
        toast("请先准备所选账号的签到活动"); return false;
    }

    private void pasteDialog() {
        if (!checkReady()) return;
        EditText input = field("粘贴完整签到二维码文本", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, "", 100000);
        input.setMinLines(4);
        new AlertDialog.Builder(this).setTitle("粘贴码并签到").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("解析并开始", (d, w) -> handleQr(input.getText().toString())).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || busy) return;
        if (requestCode == SCAN) handleQr(data.getStringExtra("qr_text"));
        else if (requestCode == IMAGE && data.getData() != null) {
            setBusy(true, "正在本地识别图片…");
            worker.execute(() -> {
                try { String raw = GalleryDecoder.read(getContentResolver(), data.getData()); ui(() -> { setBusy(false, "图片已识别"); handleQr(raw); }); }
                catch (Exception error) { ui(() -> setBusy(false, message(error))); }
            });
        }
    }

    private void handleQr(String raw) {
        if (!foreground) { queueStatus.setText("应用已离开前台，未提交。返回后请重新扫码。"); return; }
        if (!checkReady()) return;
        final QrPayload qr;
        try { qr = QrPayload.parse(raw); } catch (Exception error) { queueStatus.setText(message(error)); return; }
        List<PreparedAccount> queue = new ArrayList<>();
        boolean matches = false;
        for (Account account : selectedAccounts()) {
            List<ActivityContext> contexts = prepared.get(account.uid);
            PreparedAccount item = new PreparedAccount(account, contexts == null ? Collections.emptyList() : contexts);
            queue.add(item); if (item.findActivity(qr.activeId) != null) matches = true;
        }
        if (!matches) { queueStatus.setText("二维码活动与已准备活动不匹配，未提交。请重新准备或选择对应活动的账号。"); return; }
        stopRequested.set(false); setBusy(true, "活动 " + qr.activeId + " · 正在按账号顺序提交…");
        progress.setIndeterminate(false); progress.setMax(queue.size()); progress.setProgress(0);
        final BatchRunner activeRunner = new BatchRunner(journalGateway());
        runner = activeRunner;
        worker.execute(() -> {
            try {
                List<BatchRunner.Entry> result = activeRunner.run(queue, qr, LocationValue.empty(), snapshot.completed, (finished, total, entry) -> ui(() -> {
                    progress.setMax(total); progress.setProgress(finished);
                    statuses.put(entry.account.uid, entry.result.message); statusColors.put(entry.account.uid, color(entry.result.state));
                    queueStatus.setText("活动 " + qr.activeId + " · " + finished + " / " + total + "\n" + entry.account.name + "：" + entry.result.message); renderAccounts();
                }));
                synchronized (stateLock) { store.save(snapshot); }
                String report = report(result);
                ui(() -> { if (runner == activeRunner) runner = null; setBusy(false, "活动 " + qr.activeId + "\n" + report); renderAccounts(); });
            } catch (Exception error) { ui(() -> { if (runner == activeRunner) runner = null; setBusy(false, message(error)); }); }
        });
    }

    /** Write-ahead journal: a process death or timeout never silently resubmits the same account. */
    private SigninGateway journalGateway() {
        return new SigninGateway() {
            @Override public Account login(String phone, String password) throws Exception { return client.login(phone, password); }
            @Override public List<ActivityContext> prepare(Account account) throws Exception { return client.prepare(account); }
            @Override public SignResult submit(Account account, ActivityContext activity, QrPayload qr, LocationValue value) throws Exception {
                String key = account.uid + "|" + qr.activeId;
                synchronized (stateLock) {
                    if (snapshot.uncertain.contains(key)) return new SignResult(SignResult.State.UNCERTAIN, "上次结果待核对，未重复提交。请点管理核对后再继续。");
                    snapshot.uncertain.add(key);
                    store.save(snapshot);
                }
                SignResult result = client.submit(account, activity, qr, LocationValue.empty());
                synchronized (stateLock) {
                    if (result != null && result.state != SignResult.State.UNCERTAIN) snapshot.uncertain.remove(key);
                    if (result != null && (result.state == SignResult.State.SUCCESS || result.state == SignResult.State.ALREADY_SIGNED)) snapshot.completed.add(key);
                    store.save(snapshot);
                }
                return result;
            }
        };
    }

    private void manage(Account account) {
        String[] choices = hasUncertain(account.uid) ? new String[]{"重新登录", "核对上次不确定结果", "删除本地账号"} : new String[]{"重新登录", "删除本地账号"};
        new AlertDialog.Builder(this).setTitle(account.name).setItems(choices, (d, index) -> {
            String choice = choices[index];
            if (choice.equals("重新登录")) loginDialog(account);
            else if (choice.startsWith("核对")) reconcile(account);
            else new AlertDialog.Builder(this).setTitle("删除本地账号？").setMessage("仅移除本机保存的会话，不操作学习通账户。")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> mutateStore(() -> {
                        snapshot.accounts.removeIf(item -> item.uid.equals(account.uid));
                    }, () -> { prepared.remove(account.uid); statuses.remove(account.uid); renderAccounts(); })).show();
        }).show();
    }

    private void reconcile(Account account) {
        List<String> keys = new ArrayList<>();
        synchronized (stateLock) { for (String key : snapshot.uncertain) if (key.startsWith(account.uid + "|")) keys.add(key); }
        if (keys.isEmpty()) return;
        String[] titles = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) titles[i] = "活动 " + keys.get(i).substring(keys.get(i).indexOf('|') + 1);
        new AlertDialog.Builder(this).setTitle("先在官方应用核对实际结果").setItems(titles, (d, index) -> {
            String key = keys.get(index);
            new AlertDialog.Builder(this).setTitle(titles[index]).setMessage("请根据官方应用中的实际状态选择。程序不会自行重试不确定的请求。")
                    .setNegativeButton("尚未核对", null)
                    .setNeutralButton("已确认未完成，允许补扫", (dialog, which) -> mutateStore(() -> snapshot.uncertain.remove(key), () -> { statuses.remove(account.uid); renderAccounts(); }))
                    .setPositiveButton("已确认完成，记录成功", (dialog, which) -> mutateStore(() -> { snapshot.uncertain.remove(key); snapshot.completed.add(key); }, () -> { statuses.put(account.uid, "已根据核对结果标记完成"); renderAccounts(); })).show();
        }).show();
    }

    private void demo() {
        if (busy) return;
        stopRequested.set(false);
        setBusy(true, "离线演示：使用合成账号，不会联网或修改已保存账号。");
        worker.execute(() -> {
            try {
                List<PreparedAccount> fake = new ArrayList<>();
                ActivityContext activity = new ActivityContext("5000000000000", "1", "1", "离线演示");
                for (int i = 1; i <= 3; i++) fake.add(new PreparedAccount(new Account("演示", "demo" + i, "演示账号 " + i, "-1", Collections.emptyMap()), Collections.singletonList(activity)));
                SigninGateway gateway = new SigninGateway() {
                    public Account login(String p, String w) { throw new UnsupportedOperationException(); }
                    public List<ActivityContext> prepare(Account a) { return Collections.singletonList(activity); }
                    public SignResult submit(Account a, ActivityContext c, QrPayload q, LocationValue l) throws Exception {
                        Thread.sleep(350);
                        return a.uid.equals("demo2") ? new SignResult(SignResult.State.EXPIRED, "演示：二维码过期，暂停剩余账号") : new SignResult(SignResult.State.SUCCESS, "演示：签到成功");
                    }
                };
                BatchRunner demoRunner = new BatchRunner(gateway);
                runner = demoRunner;
                if (stopRequested.get()) demoRunner.cancel();
                List<BatchRunner.Entry> result = demoRunner.run(fake, QrPayload.parse("id=5000000000000&enc=demo"), LocationValue.empty(), new LinkedHashSet<>(), null);
                StringBuilder report = new StringBuilder("离线演示完成（未联网）：\n");
                for (BatchRunner.Entry entry : result) report.append(entry.account.name).append("：").append(entry.result.message).append('\n');
                ui(() -> { if (runner == demoRunner) runner = null; setBusy(false, report.toString()); });
            } catch (Exception error) { ui(() -> { runner = null; setBusy(false, "演示未完成"); }); }
        });
    }

    private void mutateStore(Runnable mutation, Runnable after) {
        setBusy(true, "正在保存本地变更…");
        worker.execute(() -> { try { synchronized (stateLock) { mutation.run(); store.save(snapshot); } ui(() -> { setBusy(false, "本地变更已保存"); after.run(); }); }
            catch (Exception error) { ui(() -> setBusy(false, "保存失败，请关闭应用后重新打开核对本地状态。")); } });
    }
    private void resetStore() {
        worker.execute(() -> { try { store.clear(); synchronized (stateLock) { snapshot = new SessionStore.Snapshot(); } ui(() -> { loaded = true; setBusy(false, "已重置，请添加账号。"); renderAccounts(); }); }
            catch (Exception error) { ui(() -> { toast("重置失败，请通过系统设置清除应用数据。"); finish(); }); } });
    }
    private void persistOrWarn() {
        try { synchronized (stateLock) { store.save(snapshot); } }
        catch (Exception error) { ui(() -> toast("本地选择未能保存，请重新打开应用核对。")); }
    }
    private boolean hasUncertain(String uid) { synchronized (stateLock) { for (String key : snapshot.uncertain) if (key.startsWith(uid + "|")) return true; return false; } }
    private List<Account> selectedAccounts() { synchronized (stateLock) { List<Account> out = new ArrayList<>(); for (Account a : snapshot.accounts) if (a.selected) out.add(a); return out; } }
    private void setBusy(boolean value, String message) {
        busy = value; queueStatus.setText(message); progress.setVisibility(value ? View.VISIBLE : View.GONE); progress.setIndeterminate(value);
        stop.setVisibility(value ? View.VISIBLE : View.GONE); stop.setEnabled(value); updateButtons(); renderAccounts();
    }
    private void updateButtons() {
        if (add == null) return;
        boolean editable = loaded && !busy;
        boolean scanReady = editable && selectedAccounts().stream().anyMatch(account -> prepared.containsKey(account.uid) && !prepared.get(account.uid).isEmpty());
        add.setEnabled(editable); prepare.setEnabled(editable && !selectedAccounts().isEmpty());
        scan.setEnabled(scanReady); image.setEnabled(scanReady); paste.setEnabled(scanReady); demo.setEnabled(!busy);
    }
    private static int color(SignResult.State state) {
        if (state == SignResult.State.SUCCESS || state == SignResult.State.ALREADY_SIGNED) return SigninTheme.SUCCESS;
        if (state == SignResult.State.UNCERTAIN || state == SignResult.State.FAILED || state == SignResult.State.AUTH_REQUIRED) return SigninTheme.ERROR;
        return MUTED;
    }
    private static String report(List<BatchRunner.Entry> entries) {
        int success = 0, already = 0, uncertain = 0, pending = 0;
        for (BatchRunner.Entry entry : entries) {
            if (entry.result.state == SignResult.State.SUCCESS) success++;
            else if (entry.result.state == SignResult.State.ALREADY_SIGNED) already++;
            else if (entry.result.state == SignResult.State.UNCERTAIN) uncertain++;
            else pending++;
        }
        return "成功 " + success + " · 服务端已签到 " + already + " · 待核对 " + uncertain + " · 其他/未提交 " + pending + "\n请查看各账号结果；二维码过期可补扫新码。";
    }
    private static String mask(String phone) { return phone.matches("[0-9]{11}") ? phone.substring(0, 3) + "****" + phone.substring(7) : phone; }
    private static String message(Exception error) { String text = error.getMessage(); return text == null || text.isEmpty() ? "操作未完成，请稍后重试。" : text; }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout dialogColumn() { LinearLayout layout = column(); layout.setPadding(dp(22), dp(6), dp(22), dp(4)); return layout; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(4), 1); return view; }
    private EditText field(String hint, int inputType, String value, int limit) {
        EditText field = new EditText(this); field.setHint(hint); field.setText(value); field.setTextSize(14); field.setInputType(inputType);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)}); field.setSaveEnabled(false); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return field;
    }
    private Button button(String label, boolean primary) {
        Button button = new Button(this); button.setText(label); SigninTheme.button(button, primary);
        gap(button, 8, 5); return button;
    }
    private LinearLayout card(LinearLayout body, String title) {
        LinearLayout card = column(); card.setPadding(dp(16), dp(17), dp(16), dp(16));
        card.setBackground(SigninTheme.surface(this, Color.WHITE, SigninTheme.BORDER, 12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(16); card.setLayoutParams(params);
        TextView heading = text(title, 14, INK); heading.setTypeface(null, Typeface.BOLD); gap(heading, 0, 12); card.addView(heading); body.addView(card); return card;
    }
    private void gap(View view, int top, int bottom) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); params.bottomMargin = dp(bottom); view.setLayoutParams(params); }
    @Override protected void onStart() {
        super.onStart(); foreground = true;
    }
    @Override protected void onStop() {
        foreground = false;
        if (busy) { stopRequested.set(true); BatchRunner active = runner; if (active != null) active.cancel(); }
        super.onStop();
    }
    @Override protected void onDestroy() {
        stopRequested.set(true); BatchRunner active = runner; if (active != null) active.cancel();
        preparing = false; prepareNotice.removeCallbacks(preparationTicker);
        if (activeLogin != null) activeLogin.dismiss();
        worker.shutdown(); super.onDestroy();
    }
}
