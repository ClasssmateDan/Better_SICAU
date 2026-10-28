using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

internal static partial class DesktopReminders {
    [STAThread]
    private static int Main(string[] args) {
        try {
            if (args.Length == 2 && args[0] == "--check-plan") { ReadPlan(args[1]); return 0; }
            if (args.Length == 1 && args[0] == "--self-test") {
                long now = 2000000000000L;
                ReminderPlan plan = new ReminderPlan { events = new List<ReminderEvent> {
                    new ReminderEvent { at = now - 61000 }, new ReminderEvent { at = now - 1000 },
                    new ReminderEvent { at = now }, new ReminderEvent { at = now + 1000 } } };
                if (Due(plan, now - 100000, now).Count() != 2 || Due(plan, now + 1, now).Any()
                    || Due(plan, now, now).Any()) return 2;
                return 0;
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            string root = AppDomain.CurrentDomain.BaseDirectory;
            string identity;
            using (SHA256 hash = SHA256.Create()) identity = BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(root.ToLowerInvariant()))).Replace("-", "");
            bool created;
            using (Mutex mutex = new Mutex(true, "Local\\KeJianReminders-" + identity, out created)) {
                if (!created) { MessageBox.Show("提醒工具已在运行，请在右下角托盘中打开课间图标。", "课间提醒"); return 0; }
                using (ReminderContext context = new ReminderContext(root)) {
                    if (args.Length == 1 && args[0] == "--test-notification") context.RunNotificationTest();
                    else context.ShowSettings();
                    Application.Run(context);
                }
                mutex.ReleaseMutex();
            }
            return 0;
        } catch (Exception error) {
            if (args.Length > 0 && args[0].StartsWith("--check")) return 1;
            MessageBox.Show(error.Message, "课间提醒启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}

internal sealed class ReminderContext : ApplicationContext {
    private readonly string root;
    private readonly string statePath;
    private ReminderState state;
    private ReminderPlan plan;
    private DateTime planModified;
    private long lastCheck;
    private int refreshCount;
    private readonly NotifyIcon tray;
    private readonly ToolStripMenuItem enableMenu;
    private readonly System.Windows.Forms.Timer timer;
    private readonly Form form;
    private readonly CheckBox enableCheck;
    private readonly Label details;
    private bool changing;

    internal ReminderContext(string rootPath) {
        root = rootPath;
        statePath = Path.Combine(root, "reminders", "state.json");
        state = new ReminderState { enabled = false, planPath = Path.Combine(root, "reminder-plan.json"), sent = new Dictionary<string, long>() };
        if (File.Exists(statePath)) {
            try { state = DesktopReminders.Json.Deserialize<ReminderState>(File.ReadAllText(statePath, Encoding.UTF8)) ?? state; }
            catch { state.enabled = false; }
        }
        if (state.sent == null) state.sent = new Dictionary<string, long>();
        if (String.IsNullOrEmpty(state.planPath)) state.planPath = Path.Combine(root, "reminder-plan.json");
        try { plan = DesktopReminders.ReadPlan(state.planPath); }
        catch {
            state.enabled = false;
            state.planPath = Path.Combine(root, "reminder-plan.json");
            plan = DesktopReminders.ReadPlan(state.planPath);
            MessageBox.Show("上次使用的提醒计划无法读取，已载入内置课表并关闭提醒。请核对或重新载入更新课表后开启。", "课间提醒");
        }
        planModified = File.GetLastWriteTimeUtc(state.planPath);
        lastCheck = DesktopReminders.Now() - 1;

        ContextMenuStrip menu = new ContextMenuStrip();
        menu.Items.Add("打开提醒设置", null, delegate { ShowSettings(); });
        enableMenu = new ToolStripMenuItem("开启上课／下课提醒") { CheckOnClick = true, Checked = state.enabled };
        enableMenu.CheckedChanged += delegate { if (!changing) ChangeEnabled(enableMenu.Checked); };
        menu.Items.Add(enableMenu);
        menu.Items.Add("测试通知", null, delegate { ShowTest(); });
        menu.Items.Add("载入更新后的提醒计划…", null, delegate { ChoosePlan(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出提醒", null, delegate { ExitThread(); });
        tray = new NotifyIcon { Icon = SystemIcons.Information, ContextMenuStrip = menu, Visible = true, Text = "课间 · 上下课提醒" };
        tray.DoubleClick += delegate { ShowSettings(); };

        form = new Form { Text = "课间 · 桌面提醒", ClientSize = new Size(520, 320), Font = new Font("Microsoft YaHei UI", 11),
            FormBorderStyle = FormBorderStyle.FixedDialog, MaximizeBox = false, StartPosition = FormStartPosition.CenterScreen };
        enableCheck = new CheckBox { Text = "开启上课、下课桌面提醒", AutoSize = true, Location = new Point(24, 24), Checked = state.enabled };
        enableCheck.CheckedChanged += delegate { if (!changing) ChangeEnabled(enableCheck.Checked); };
        details = new Label { Location = new Point(24, 67), Size = new Size(472, 130), AutoEllipsis = true };
        Button test = new Button { Text = "测试通知", Location = new Point(24, 209), Size = new Size(120, 38) };
        Button load = new Button { Text = "载入更新课表", Location = new Point(158, 209), Size = new Size(156, 38) };
        Button hide = new Button { Text = "收起到托盘", Location = new Point(328, 209), Size = new Size(168, 38) };
        Label hint = new Label { Text = "关闭此窗口后继续在托盘运行；右键托盘图标可关闭或退出。", Location = new Point(24, 268), Size = new Size(472, 44), ForeColor = Color.DimGray };
        test.Click += delegate { ShowTest(); };
        load.Click += delegate { ChoosePlan(); };
        hide.Click += delegate { form.Hide(); };
        form.FormClosing += delegate(object sender, FormClosingEventArgs e) { if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; form.Hide(); } };
        form.Controls.AddRange(new Control[] { enableCheck, details, test, load, hide, hint });
        timer = new System.Windows.Forms.Timer { Interval = 1000 };
        timer.Tick += delegate { Tick(); };
        timer.Start();
        UpdateStatus();
    }

    internal void ShowSettings() { form.Show(); if (form.WindowState == FormWindowState.Minimized) form.WindowState = FormWindowState.Normal; form.Activate(); }
    private void Save() {
        Directory.CreateDirectory(Path.GetDirectoryName(statePath));
        long cutoff = DesktopReminders.Now() - 172800000L;
        state.sent = state.sent.Where(pair => pair.Value > cutoff).ToDictionary(pair => pair.Key, pair => pair.Value);
        string pending = statePath + ".tmp";
        File.WriteAllText(pending, DesktopReminders.Json.Serialize(state), Encoding.UTF8);
        if (File.Exists(statePath)) File.Replace(pending, statePath, null);
        else File.Move(pending, statePath);
    }
    private void ChangeEnabled(bool enabled) {
        bool previous = state.enabled;
        state.enabled = enabled;
        try { Save(); lastCheck = DesktopReminders.Now() - 1; }
        catch (Exception error) { state.enabled = previous; MessageBox.Show("开关未保存：" + error.Message, "课间提醒"); }
        changing = true;
        enableMenu.Checked = enableCheck.Checked = state.enabled;
        changing = false;
        UpdateStatus();
    }
    private void UpdateStatus() {
        tray.Text = state.enabled ? "课间 · 上下课提醒已开启" : "课间 · 上下课提醒已关闭";
        ReminderEvent next = plan.events.FirstOrDefault(ev => ev.at > DesktopReminders.Now());
        string nextText = next == null ? "本学期没有后续固定节次提醒。" : "下次：" + new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(next.at).ToLocalTime().ToString("MM-dd HH:mm") + " " + next.title;
        details.Text = plan.semesterName + "\n每节上课和下课各提醒一次，按北京时间作息。\n" + nextText + "\n课表修改后，在网页导出提醒计划，再用本窗口载入。\n系统勿扰模式可能阻止弹出，请先点击“测试通知”确认。";
    }
    private void Notify(string title, string body) {
        tray.ShowBalloonTip(10000, title, body.Length > 255 ? body.Substring(0, 252) + "…" : body, ToolTipIcon.Info);
    }
    private void ShowTest() { Notify("课间 · 测试通知", "你正在测试 Windows 桌面通知。\n开启开关后，每节上课、下课会在桌面右下角提醒。"); }
    internal void RunNotificationTest() {
        System.Windows.Forms.Timer testTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        int count = 0;
        testTimer.Tick += delegate { if (++count == 1) ShowTest(); if (count >= 8) { testTimer.Stop(); testTimer.Dispose(); ExitThread(); } };
        testTimer.Start();
    }
    private void ChoosePlan() {
        using (OpenFileDialog picker = new OpenFileDialog { Title = "选择网页导出的课间提醒计划", Filter = "课间提醒计划 (*.json)|*.json", InitialDirectory = root }) {
            if (picker.ShowDialog(form) != DialogResult.OK) return;
            try {
                ReminderPlan replacement = DesktopReminders.ReadPlan(picker.FileName);
                string previous = state.planPath;
                state.planPath = picker.FileName;
                try { Save(); } catch { state.planPath = previous; throw; }
                plan = replacement;
                planModified = File.GetLastWriteTimeUtc(state.planPath);
                lastCheck = DesktopReminders.Now() - 1;
                UpdateStatus();
            } catch (Exception error) { MessageBox.Show(error.Message, "载入失败"); }
        }
    }
    private void Tick() {
        long now = DesktopReminders.Now();
        long after = lastCheck;
        lastCheck = now;
        try {
            if (++refreshCount % 5 == 0 && File.GetLastWriteTimeUtc(state.planPath) != planModified) {
                plan = DesktopReminders.ReadPlan(state.planPath);
                planModified = File.GetLastWriteTimeUtc(state.planPath);
            }
            if (state.enabled) foreach (ReminderEvent ev in DesktopReminders.Due(plan, after, now)) {
                if (state.sent.ContainsKey(ev.id)) continue;
                state.sent[ev.id] = now;
                Save();
                Notify(ev.title, ev.body);
            }
            if (refreshCount % 30 == 0) UpdateStatus();
        } catch (Exception error) {
            state.enabled = false;
            try { planModified = File.GetLastWriteTimeUtc(state.planPath); } catch { }
            try { Save(); } catch { }
            changing = true; enableCheck.Checked = enableMenu.Checked = false; changing = false;
            UpdateStatus();
            Notify("课间 · 提醒已暂停", error.Message);
        }
    }
    protected override void ExitThreadCore() {
        timer.Stop(); timer.Dispose(); tray.Visible = false; tray.Dispose(); form.Dispose();
        base.ExitThreadCore();
    }
}
