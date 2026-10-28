using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

[assembly: AssemblyTitle("课间课程表")]
[assembly: AssemblyProduct("课间课程表")]
[assembly: AssemblyVersion("1.7.3.0")]
[assembly: AssemblyFileVersion("1.7.3.0")]

namespace KeJianDesktop {
    internal static class Program {
        internal static string DataRoot;
        internal static bool SmokeTest;
        internal static string BackupTest;
        internal static string QrTest;
        internal static string CourseTest;
        internal static string ImportTest;
        internal static string ToolTest;
        internal static bool LayoutTest;
        internal static int Result;
        private static readonly SemaphoreSlim browserEnvironmentLock = new SemaphoreSlim(1, 1);
        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool SetDllDirectory(string path);

        internal static byte[] Resource(string name) {
            using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream("KeJian." + name)) {
                if (input == null) throw new FileNotFoundException("程序资源缺失：" + name);
                using (MemoryStream output = new MemoryStream()) { input.CopyTo(output); return output.ToArray(); }
            }
        }
        internal static void WriteAtomic(string path, string value) {
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string pending = path + ".tmp";
            File.WriteAllText(pending, value, new UTF8Encoding(false));
            if (File.Exists(path)) File.Replace(pending, path, path + ".previous");
            else File.Move(pending, path);
        }
        internal static async Task<CoreWebView2Environment> CreateBrowserEnvironment(string profile) {
            string expected = PortableData.Normalize(profile);
            if (!expected.StartsWith(PortableData.Normalize(DataRoot) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException("浏览器配置必须位于课间数据目录内。");
            await browserEnvironmentLock.WaitAsync();
            string previous = Environment.GetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER");
            try {
                // WebView2 environment overrides can otherwise ignore the explicit profile path.
                // Scope this override to creation of our own environment; preserve the caller's value.
                Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", expected, EnvironmentVariableTarget.Process);
                CoreWebView2Environment environment = await CoreWebView2Environment.CreateAsync(null, expected);
                if (!String.Equals(PortableData.Normalize(environment.UserDataFolder), expected, StringComparison.OrdinalIgnoreCase)) throw new IOException("浏览器配置目录被系统设置覆盖，未使用外部目录运行。");
                return environment;
            } finally {
                Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", previous, EnvironmentVariableTarget.Process);
                browserEnvironmentLock.Release();
            }
        }
        private static void PrepareRuntime() {
            string sdk = Path.Combine(DataRoot, "runtime", "1.7.3", "sdk");
            Directory.CreateDirectory(sdk);
            string loader = Path.Combine(sdk, "WebView2Loader.dll");
            byte[] bytes = Resource("WebView2Loader.dll");
            if (!File.Exists(loader) || !File.ReadAllBytes(loader).SequenceEqual(bytes)) File.WriteAllBytes(loader, bytes);
            if (!SetDllDirectory(sdk)) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs args) {
                string name = new AssemblyName(args.Name).Name;
                if (name == "Microsoft.Web.WebView2.Core" || name == "Microsoft.Web.WebView2.WinForms") return Assembly.Load(Resource(name + ".dll"));
                return null;
            };
        }
        [STAThread]
        private static int Main(string[] args) {
            try {
                string appDirectory = Path.GetDirectoryName(Application.ExecutablePath);
                DataRoot = PortableData.DefaultRoot(appDirectory);
                bool customDataDirectory = false;
                for (int i = 0; i < args.Length; i++) {
                    if (args[i] == "--data-dir" && i + 1 < args.Length) { DataRoot = Path.GetFullPath(args[++i]); customDataDirectory = true; }
                    else if (args[i] == "--smoke-test") SmokeTest = true;
                    else if (args[i] == "--layout-test") { LayoutTest = true; SmokeTest = true; }
                    else if (args[i] == "--backup-test" && i + 1 < args.Length) {
                        BackupTest = args[++i]; SmokeTest = true;
                        if (!new string[] { "save", "cancel", "error", "extension" }.Contains(BackupTest)) throw new ArgumentException("未知备份测试模式。");
                    }
                    else if (args[i] == "--qr-test" && i + 1 < args.Length) {
                        QrTest = args[++i]; SmokeTest = true;
                        if (!new string[] { "flow", "save", "cancel", "error", "extension", "open", "open-error" }.Contains(QrTest)) throw new ArgumentException("未知二维码测试模式。");
                    }
                    else if (args[i] == "--course-test" && i + 1 < args.Length) {
                        CourseTest = args[++i]; SmokeTest = true;
                        if (!new string[] { "flow", "open", "migration" }.Contains(CourseTest)) throw new ArgumentException("未知课程签到测试模式。");
                    }
                    else if (args[i] == "--import-test" && i + 1 < args.Length) {
                        ImportTest = args[++i]; SmokeTest = true;
                        if (!new string[] { "flow", "concurrent", "guide", "example", "guide-cancel", "guide-extension" }.Contains(ImportTest)) throw new ArgumentException("未知导入测试模式。");
                    }
                    else if (args[i] == "--tools-test" && i + 1 < args.Length) {
                        ToolTest = args[++i]; SmokeTest = true;
                        if (ToolTest != "flow") throw new ArgumentException("未知课程工具测试模式。");
                    }
                    else throw new ArgumentException("未知参数：" + args[i]);
                }
                if (customDataDirectory) { Directory.CreateDirectory(DataRoot); PortableData.EnsureWritable(DataRoot); }
                else {
                    string legacy = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KeJianTimetable");
                    PortableDataResult portable = PortableData.Prepare(appDirectory,legacy,false);
                    DataRoot = portable.Root;
                    if (!SmokeTest && !String.IsNullOrEmpty(portable.Warning)) MessageBox.Show(portable.Warning,"课间 · 数据迁移",MessageBoxButtons.OK,MessageBoxIcon.Information);
                }
                string temporary = Path.Combine(DataRoot,"temp"); Directory.CreateDirectory(temporary);
                Environment.SetEnvironmentVariable("TEMP",temporary,EnvironmentVariableTarget.Process);
                Environment.SetEnvironmentVariable("TMP",temporary,EnvironmentVariableTarget.Process);
                PrepareRuntime();
                string identity;
                using (SHA256 sha = SHA256.Create()) identity = BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(DataRoot.ToLowerInvariant()))).Replace("-", "");
                bool first;
                using (Mutex mutex = new Mutex(true, "Local\\KeJianDesktop-" + identity, out first)) {
                    if (!first) { File.WriteAllText(Path.Combine(DataRoot, "activate.request"), "show"); return 0; }
                    Application.EnableVisualStyles();
                    Application.SetCompatibleTextRenderingDefault(false);
                    using (TimetableWindow window = new TimetableWindow()) Application.Run(window);
                    mutex.ReleaseMutex();
                }
                return Result;
            } catch (Exception error) {
                Result = 1;
                if (SmokeTest && DataRoot != null) { try { if (Directory.Exists(DataRoot)) File.WriteAllText(Path.Combine(DataRoot, "smoke-result.json"), DesktopReminders.Json.Serialize(new { ok = false, error = error.ToString() })); } catch (IOException) { } }
                else MessageBox.Show("无法启动课间课程表：\n" + error.Message + "\n\n请从可写的程序文件夹运行，并确保 Microsoft Edge WebView2 Runtime 可用。", "课间课程表", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }
    }

    internal sealed class NativeSettings {
        public bool remindersEnabled { get; set; }
        public Dictionary<string, long> sent { get; set; }
    }

    internal sealed class TimetableWindow : Form {
        private const string Origin = "https://kejian.local";
        private readonly WebView2 browser;
        private readonly NotifyIcon tray;
        private readonly ToolStripMenuItem reminderMenu;
        private readonly System.Windows.Forms.Timer timer;
        private readonly Label loading;
        private readonly string settingsPath;
        private readonly string timetablePath;
        private readonly string planPath;
        private NativeSettings settings;
        private ReminderPlan plan;
        private bool ready;
        private bool pageLoaded;
        private bool quitting;
        private bool checkingSmoke;
        private long lastCheck;
        private readonly long startedAt;
        private object backupTestResult;
        private int browserDownloads;
        private object qrTestResult;
        private int qrNetworkRequests;
        private QrBrowserWindow qrBrowser;
        private object qrOpenResult;
        private object transferExportResult;
        private readonly CourseToolHost courseTools = new CourseToolHost();
        private object courseToolResult;

        internal TimetableWindow() {
            Text = "课间 · 课程表";
            Rectangle workArea = Screen.PrimaryScreen.WorkingArea;
            ClientSize = new Size(Math.Min(1400, workArea.Width - 80), Math.Min(920, workArea.Height - 80));
            MinimumSize = new Size(1000, 680);
            StartPosition = FormStartPosition.CenterScreen;
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            settingsPath = Path.Combine(Program.DataRoot, "settings.json");
            timetablePath = Path.Combine(Program.DataRoot, "timetable.json");
            planPath = Path.Combine(Program.DataRoot, "reminder-plan.json");
            settings = new NativeSettings { remindersEnabled = false, sent = new Dictionary<string, long>() };
            if (File.Exists(settingsPath)) {
                try { settings = DesktopReminders.Json.Deserialize<NativeSettings>(File.ReadAllText(settingsPath, Encoding.UTF8)) ?? settings; }
                catch { settings.remindersEnabled = false; }
            }
            if (settings.sent == null) settings.sent = new Dictionary<string, long>();
            if (!File.Exists(settingsPath)) SaveSettings();
            plan = DesktopReminders.ParsePlan(Encoding.UTF8.GetString(Program.Resource("reminder-plan.json")));
            if (File.Exists(planPath)) {
                try { plan = DesktopReminders.ReadPlan(planPath); }
                catch { settings.remindersEnabled = false; }
            }
            loading = new Label { Text = "正在打开课间课程表…", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Microsoft YaHei UI", 16) };
            browser = new WebView2 { Dock = DockStyle.Fill };
            Controls.Add(browser); Controls.Add(loading);
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("打开课程表", null, delegate { ShowTimetable(); });
            reminderMenu = new ToolStripMenuItem("上课／下课提醒") { CheckOnClick = true, Checked = settings.remindersEnabled };
            reminderMenu.Click += delegate { SetReminders(reminderMenu.Checked); };
            menu.Items.Add(reminderMenu);
            menu.Items.Add("测试通知", null, delegate { Notify("课间 · 测试通知", "上课、下课时将在桌面右下角显示提醒。"); });
            menu.Items.Add("关于课间", null, delegate {
                using (Form about = new Form { Text = "关于课间课程表 1.7.3", ClientSize = new Size(640, 460), StartPosition = FormStartPosition.CenterParent }) {
                    about.Controls.Add(new TextBox { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, Font = new Font("Microsoft YaHei UI", 10), Text = "课间课程表 1.7.3\r\n离线课表管理、二维码预测与上下课提醒\r\n\r\nMicrosoft WebView2 SDK 1.0.4258.31\r\n\r\n" + Encoding.UTF8.GetString(Program.Resource("WebView2.LICENSE.txt")) + "\r\n\r\n" + Encoding.UTF8.GetString(Program.Resource("QR-LICENSES.txt")) });
                    about.ShowDialog(this);
                }
            });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出课间", null, delegate { quitting = true; Close(); });
            tray = new NotifyIcon { Icon = Icon, Text = "课间课程表", Visible = true, ContextMenuStrip = menu };
            tray.DoubleClick += delegate { ShowTimetable(); };
            tray.BalloonTipClicked += delegate { ShowTimetable(); };
            lastCheck = DesktopReminders.Now() - 1;
            startedAt = DesktopReminders.Now();
            timer = new System.Windows.Forms.Timer { Interval = 1000 };
            timer.Tick += delegate { Tick(); };
            timer.Start();
            Shown += async delegate { await InitializeBrowser(); };
            if (Program.SmokeTest) { Opacity = 0; ShowInTaskbar = false; }
        }

        private async Task InitializeBrowser() {
            try {
                string assets = Path.Combine(Program.DataRoot, "runtime", "1.7.3", "assets");
                Directory.CreateDirectory(assets);
                File.WriteAllBytes(Path.Combine(assets, "index.html"), Program.Resource("index.html"));
                CoreWebView2Environment env = await Program.CreateBrowserEnvironment(Path.Combine(Program.DataRoot, "WebView2"));
                await browser.EnsureCoreWebView2Async(env);
                if (IsDisposed || Disposing) return;
                CoreWebView2 core = browser.CoreWebView2;
                core.SetVirtualHostNameToFolderMapping("kejian.local", assets, CoreWebView2HostResourceAccessKind.DenyCors);
                core.Settings.IsStatusBarEnabled = false;
                core.NavigationStarting += delegate(object sender, CoreWebView2NavigationStartingEventArgs e) {
                    Uri uri;
                    if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out uri) || uri.Scheme != "https" || uri.Host != "kejian.local") e.Cancel = true;
                };
                core.NewWindowRequested += delegate(object sender, CoreWebView2NewWindowRequestedEventArgs e) {
                    e.Handled = true;
                    Uri uri;
                    if (e.IsUserInitiated && Uri.TryCreate(e.Uri, UriKind.Absolute, out uri) && (uri.Scheme == "https" || uri.Scheme == "http"))
                        Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
                };
                core.WebMessageReceived += OnWebMessage;
                core.DownloadStarting += OnDownload;
                if (Program.QrTest != null || Program.CourseTest != null) {
                    core.AddWebResourceRequestedFilter("https://eol.sicau.edu.cn/*", CoreWebView2WebResourceContext.All);
                    core.WebResourceRequested += delegate { qrNetworkRequests++; };
                }
                core.NavigationCompleted += delegate(object sender, CoreWebView2NavigationCompletedEventArgs e) {
                    if (e.IsSuccess) { pageLoaded = true; loading.Visible = false; }
                    else Fail("页面加载失败：" + e.WebErrorStatus);
                };
                object recovered = null;
                if (File.Exists(timetablePath)) {
                    try { recovered = DesktopReminders.Json.DeserializeObject(File.ReadAllText(timetablePath, Encoding.UTF8)); } catch { }
                }
                string bootstrap = DesktopReminders.Json.Serialize(new { desktop = true, courseTools = true, timetable = recovered, remindersEnabled = settings.remindersEnabled });
                await core.AddScriptToExecuteOnDocumentCreatedAsync("if (window === window.top) window.__KEJIAN_DESKTOP__ = JSON.parse(" + DesktopReminders.Json.Serialize(bootstrap) + ");");
                if (Program.CourseTest == "migration") {
                    if (recovered == null) throw new InvalidDataException("旧版迁移测试缺少课表快照。");
                    string legacy = DesktopReminders.Json.Serialize(recovered);
                    await core.AddScriptToExecuteOnDocumentCreatedAsync("if (window === window.top && location.hostname === 'kejian.local') localStorage.setItem('kejian.timetable.public.v1', " + DesktopReminders.Json.Serialize(legacy) + ");");
                }
                if (IsDisposed || Disposing) return;
                core.Navigate(Origin + "/index.html");
            } catch (Exception error) { Fail(error.Message); }
        }

        private void Send(object message) {
            if (browser.CoreWebView2 != null) browser.CoreWebView2.PostWebMessageAsJson(DesktopReminders.Json.Serialize(message));
        }
        private void SendState() { Send(new { type = "desktop:state", enabled = settings.remindersEnabled, ready = ready }); }
        private void SaveSettings() {
            long cutoff = DesktopReminders.Now() - 172800000L;
            settings.sent = settings.sent.Where(pair => pair.Value > cutoff).ToDictionary(pair => pair.Key, pair => pair.Value);
            Program.WriteAtomic(settingsPath, DesktopReminders.Json.Serialize(settings));
        }
        private void SetReminders(bool enabled) {
            bool previous = settings.remindersEnabled;
            try {
                if (enabled && !ready) throw new InvalidOperationException("课表仍在载入，请稍后再开启提醒。");
                settings.remindersEnabled = enabled;
                SaveSettings();
                lastCheck = DesktopReminders.Now() - 1;
            } catch (Exception error) {
                settings.remindersEnabled = previous;
                Send(new { type = "desktop:error", message = "提醒开关未保存：" + error.Message });
            }
            reminderMenu.Checked = settings.remindersEnabled;
            tray.Text = settings.remindersEnabled ? "课间 · 上下课提醒已开启" : "课间 · 上下课提醒已关闭";
            SendState();
        }
        private void OnWebMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e) {
            Uri source;
            if (!Uri.TryCreate(e.Source, UriKind.Absolute, out source) || source.Scheme != "https" || source.Host != "kejian.local" || source.AbsolutePath != "/index.html") return;
            try {
                string json = e.WebMessageAsJson;
                if (json.Length > 4000000) throw new InvalidDataException("课表数据过大。");
                Dictionary<string, object> message = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(json);
                string type = message.ContainsKey("type") ? message["type"] as string : null;
                if (type == "timetable:sync") {
                    Dictionary<string, object> data = message["data"] as Dictionary<string, object>;
                    if (data == null || !data.ContainsKey("version") || Convert.ToInt32(data["version"]) != 1 || !data.ContainsKey("courses") || !data.ContainsKey("semester")) throw new InvalidDataException("课表格式不正确。");
                    ReminderPlan updated = DesktopReminders.ParsePlan(DesktopReminders.Json.Serialize(message["plan"]));
                    Program.WriteAtomic(timetablePath, DesktopReminders.Json.Serialize(data));
                    Program.WriteAtomic(planPath, DesktopReminders.Json.Serialize(updated));
                    plan = updated; ready = true;
                    SendState();
                } else if (type == "reminders:setEnabled") SetReminders(message.ContainsKey("enabled") && message["enabled"] is bool && (bool)message["enabled"]);
                else if (type == "reminders:test") Notify("课间 · 测试通知", "每节上课和下课时会在这里提醒。\n课程修改后，提醒计划自动同步，无需额外运行其他工具。");
                else if (type == "backup:export") ExportBackup(message);
                else if (type == "qr:image-export") ExportQrImage(message);
                else if (type == "qr:open") OpenQrPage(message);
                else if (type == "course-tools:open") OpenCourseTools(message);
                else if (type == "app:ready") SendState();
            } catch (Exception error) {
                settings.remindersEnabled = false;
                reminderMenu.Checked = false;
                try { SaveSettings(); } catch { }
                Send(new { type = "desktop:error", message = "本机数据同步失败，提醒已暂停。请先导出备份：" + error.Message });
                SendState();
            }
        }
        private void OnDownload(object sender, CoreWebView2DownloadStartingEventArgs e) {
            browserDownloads++;
            e.Cancel = true;
            e.Handled = true;
            Send(new { type = "backup:result", status = "error", message = "请使用程序中的“导出备份”保存数据。" });
        }

        private async void OpenCourseTools(Dictionary<string, object> message) {
            object result;
            try {
                if (!ready || !File.Exists(timetablePath)) throw new InvalidOperationException("课表仍在载入，请稍后再试。");
                result = await courseTools.OpenAsync(message, File.ReadAllText(timetablePath, Encoding.UTF8));
            } catch (Exception error) {
                result = new { type = "course-tools:result", status = "error", message = "课程工具未打开：" + error.Message };
            }
            courseToolResult = result;
            if (!IsDisposed && !Disposing) Send(result);
        }
        private void ExportBackup(Dictionary<string, object> message) {
            object result;
            string kind = message.ContainsKey("kind") ? message["kind"] as string : "backup";
            try {
                if (kind != "backup" && kind != "guide" && kind != "example") throw new InvalidDataException("导出类型不正确。");
                bool guide = kind == "guide";
                string extension = guide ? ".md" : ".json";
                string text = message.ContainsKey("text") ? message["text"] as string : null;
                string suggested = message.ContainsKey("filename") ? message["filename"] as string : null;
                if (text == null || Encoding.UTF8.GetByteCount(text) > 4000000) throw new InvalidDataException("备份内容为空或过大。");
                suggested = Path.GetFileName(suggested ?? "课间备份.json");
                foreach (char invalid in Path.GetInvalidFileNameChars()) suggested = suggested.Replace(invalid, '_');
                if (!suggested.EndsWith(extension, StringComparison.OrdinalIgnoreCase)) suggested += extension;
                string path = null;
                if (Program.ImportTest != null && (guide || kind == "example")) {
                    if (Program.ImportTest != "guide-cancel") path = Path.Combine(Program.DataRoot, Program.ImportTest == "guide-extension" ? "unsafe.exe" : guide ? "课表数据格式说明.md" : "课表导入示例.json");
                } else if (Program.BackupTest != null) {
                    if (Program.BackupTest != "cancel") path = Path.Combine(Program.DataRoot, Program.BackupTest == "error" ? "blocked.json" : Program.BackupTest == "extension" ? "unsafe.exe" : "导出验证.json");
                } else {
                    using (SaveFileDialog save = new SaveFileDialog { Title = guide ? "保存课表数据格式说明" : "保存课表 JSON（使用时请在课程表内导入）", FileName = suggested, InitialDirectory = ExportDirectory(), RestoreDirectory = true, Filter = guide ? "Markdown 格式说明 (*.md)|*.md" : "课间 JSON 课表 (*.json)|*.json", DefaultExt = guide ? "md" : "json", AddExtension = true, OverwritePrompt = true }) {
                        if (save.ShowDialog(this) == DialogResult.OK) path = save.FileName;
                    }
                }
                if (path == null) result = new { type = "backup:result", status = "cancelled", kind = kind };
                else {
                    if (guide) {
                        if (!String.Equals(Path.GetExtension(path), ".md", StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("请将格式说明保存为 .md 文件。");
                        File.WriteAllText(path, text, new UTF8Encoding(false));
                    } else SaveBackupFile(path, text);
                    result = new { type = "backup:result", status = "saved", kind = kind, filename = Path.GetFileName(path) };
                }
            } catch (Exception error) { result = new { type = "backup:result", status = "error", kind = kind, message = error.Message }; }
            if (Program.BackupTest != null) backupTestResult = result;
            if (Program.ImportTest != null) transferExportResult = result;
            Send(result);
        }
        private static string ExportDirectory() { string folder = Path.Combine(Program.DataRoot, "exports"); Directory.CreateDirectory(folder); return folder; }
        private static void SaveBackupFile(string path, string text) {
            if (!String.Equals(Path.GetExtension(path), ".json", StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("请将备份保存为 .json 数据文件。");
            // Save user data only. Never shell-open it or change Windows security settings.
            using (FileStream output = new FileStream(path, FileMode.Create, FileAccess.Write))
            using (StreamWriter writer = new StreamWriter(output, new UTF8Encoding(false))) {
                writer.Write(text);
            }
        }
        private void ExportQrImage(Dictionary<string, object> message) {
            object result;
            try {
                string dataUrl = message.ContainsKey("dataUrl") ? message["dataUrl"] as string : null;
                const string prefix = "data:image/png;base64,";
                if (dataUrl == null || !dataUrl.StartsWith(prefix, StringComparison.Ordinal) || dataUrl.Length > 3000000) throw new InvalidDataException("二维码图片格式不正确或过大。");
                byte[] bytes = Convert.FromBase64String(dataUrl.Substring(prefix.Length));
                byte[] signature = new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 };
                if (bytes.Length < 24 || !bytes.Take(8).SequenceEqual(signature)) throw new InvalidDataException("请保存有效的 PNG 图片。");
                using (MemoryStream input = new MemoryStream(bytes))
                using (Image image = Image.FromStream(input, true, true)) {
                    if (image.Width > 4096 || image.Height > 4096) throw new InvalidDataException("二维码图片尺寸过大。");
                }
                string suggested = Path.GetFileName(message.ContainsKey("filename") ? message["filename"] as string : null) ?? "课间二维码.png";
                foreach (char invalid in Path.GetInvalidFileNameChars()) suggested = suggested.Replace(invalid, '_');
                if (!suggested.EndsWith(".png", StringComparison.OrdinalIgnoreCase)) suggested += ".png";
                string path = null;
                if (Program.QrTest != null) {
                    if (Program.QrTest != "cancel") path = Path.Combine(Program.DataRoot, Program.QrTest == "error" ? "blocked.png" : Program.QrTest == "extension" ? "unsafe.exe" : "二维码验证.png");
                } else {
                    using (SaveFileDialog save = new SaveFileDialog { Title = "保存当前二维码图片", FileName = suggested, InitialDirectory = ExportDirectory(), RestoreDirectory = true, Filter = "PNG 图片 (*.png)|*.png", DefaultExt = "png", AddExtension = true, OverwritePrompt = true }) {
                        if (save.ShowDialog(this) == DialogResult.OK) path = save.FileName;
                    }
                }
                if (path == null) result = new { type = "qr:image-result", status = "cancelled" };
                else {
                    if (!String.Equals(Path.GetExtension(path), ".png", StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("请将二维码保存为 .png 图片文件。");
                    File.WriteAllBytes(path, bytes);
                    result = new { type = "qr:image-result", status = "saved" };
                }
            } catch (Exception error) { result = new { type = "qr:image-result", status = "error", message = error.Message }; }
            if (Program.QrTest != null) qrTestResult = result;
            Send(result);
        }
        private void Notify(string title, string body) { tray.ShowBalloonTip(10000, title, body.Length > 255 ? body.Substring(0, 252) + "…" : body, ToolTipIcon.Info); }
        private async void OpenQrPage(Dictionary<string, object> message) {
            object result;
            try {
                string url = QrBrowserWindow.ValidateUrl(message.ContainsKey("url") ? message["url"] as string : null);
                if (qrBrowser == null || qrBrowser.IsDisposed) {
                    qrBrowser = new QrBrowserWindow(Program.SmokeTest);
                    qrBrowser.Show(this);
                }
                await qrBrowser.OpenAsync(url);
                result = new { type = "qr:open-result", status = "opened" };
            } catch (Exception error) { result = new { type = "qr:open-result", status = "error", message = error.Message }; }
            if (Program.QrTest == "open" || Program.QrTest == "open-error" || Program.CourseTest == "open") qrOpenResult = result;
            if (!IsDisposed && !Disposing) Send(result);
        }
        private void ShowTimetable() { Show(); WindowState = FormWindowState.Normal; Activate(); }
        private async void Tick() {
            long now = DesktopReminders.Now();
            long after = lastCheck; lastCheck = now;
            try {
                string activation = Path.Combine(Program.DataRoot, "activate.request");
                if (File.Exists(activation)) { File.Delete(activation); ShowTimetable(); }
                if (!Program.SmokeTest && ready && settings.remindersEnabled) foreach (ReminderEvent ev in DesktopReminders.Due(plan, after, now)) {
                    if (settings.sent.ContainsKey(ev.id)) continue;
                    settings.sent[ev.id] = now; SaveSettings(); Notify(ev.title, ev.body);
                }
                if (Program.SmokeTest && ready && pageLoaded && !checkingSmoke) {
                    checkingSmoke = true;
                    object qrReport = null;
                    object qrOpenReport = null;
                    object courseReport = null;
                    object courseOpenReport = null;
                    object importReport = null;
                    object toolReport = null;
                    object layoutReport = null;
                    if (Program.LayoutTest) {
                        await browser.CoreWebView2.ExecuteScriptAsync(Encoding.UTF8.GetString(Program.Resource("LayoutSmoke.js")));
                        string layoutJson = null;
                        for (int wait=0;wait<80;wait++) {
                            string value=await browser.CoreWebView2.ExecuteScriptAsync("window.__layoutSmoke?JSON.stringify(window.__layoutSmoke):null");
                            if(value!="null") { layoutJson=DesktopReminders.Json.Deserialize<string>(value); break; }
                            await Task.Delay(100);
                        }
                        if(layoutJson==null)throw new InvalidOperationException("电脑版首页布局验证超时。");
                        Dictionary<string,object> report=DesktopReminders.Json.Deserialize<Dictionary<string,object>>(layoutJson);
                        if(!(bool)report["ok"])throw new InvalidOperationException(layoutJson);
                        layoutReport=report;
                        using(FileStream preview=File.Create(Path.Combine(Program.DataRoot,"homepage-preview.png")))await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png,preview);
                    }
                    if (Program.ToolTest != null) {
                        await browser.CoreWebView2.ExecuteScriptAsync(Encoding.UTF8.GetString(Program.Resource("ToolSmoke.js")));
                        string toolJson = null;
                        for (int wait = 0; wait < 250; wait++) {
                            string value = await browser.CoreWebView2.ExecuteScriptAsync("window.__toolSmoke ? JSON.stringify(window.__toolSmoke) : null");
                            if (value != "null") { toolJson = DesktopReminders.Json.Deserialize<string>(value); break; }
                            await Task.Delay(100);
                        }
                        if (toolJson == null) throw new InvalidOperationException("课程工具验证超时。");
                        Dictionary<string, object> report = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(toolJson);
                        if (!(bool)report["ok"]) throw new InvalidOperationException(toolJson);
                        toolReport = report;
                        using (FileStream preview = File.Create(Path.Combine(Program.DataRoot, "course-tools-preview.png"))) await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
                    }
                    if (Program.ImportTest != null) {
                        await browser.CoreWebView2.ExecuteScriptAsync("window.__IMPORT_TEST_MODE__ = " + DesktopReminders.Json.Serialize(Program.ImportTest) + "; window.__IMPORT_TEST_EXAMPLE__ = JSON.parse(" + DesktopReminders.Json.Serialize(Encoding.UTF8.GetString(Program.Resource("import-example.json"))) + ");");
                        await browser.CoreWebView2.ExecuteScriptAsync(Encoding.UTF8.GetString(Program.Resource("ImportSmoke.js")));
                        string importJson = null;
                        bool previewCaptured = false;
                        for (int wait = 0; wait < 180; wait++) {
                            if (!previewCaptured && await browser.CoreWebView2.ExecuteScriptAsync("window.__importPreviewReady === true") == "true") {
                                using (FileStream preview = File.Create(Path.Combine(Program.DataRoot, "import-validation-preview.png"))) await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
                                await browser.CoreWebView2.ExecuteScriptAsync("window.__importPreviewCaptured = true");
                                previewCaptured = true;
                            }
                            string value = await browser.CoreWebView2.ExecuteScriptAsync("window.__importSmoke ? JSON.stringify(window.__importSmoke) : null");
                            if (value != "null") { importJson = DesktopReminders.Json.Deserialize<string>(value); break; }
                            await Task.Delay(100);
                        }
                        if (importJson == null) throw new InvalidOperationException("导入界面验证超时。");
                        Dictionary<string, object> report = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(importJson);
                        if (!(bool)report["ok"]) throw new InvalidOperationException(importJson);
                        importReport = report;
                        if (new string[] { "guide", "example", "guide-cancel", "guide-extension" }.Contains(Program.ImportTest)) {
                            string button = Program.ImportTest == "example" ? "download-import-example" : "download-import-guide";
                            await browser.CoreWebView2.ExecuteScriptAsync("document.getElementById('" + button + "').click()");
                            for (int wait = 0; wait < 50 && transferExportResult == null; wait++) await Task.Delay(100);
                            if (transferExportResult == null) throw new InvalidOperationException("格式说明／示例未到达原生导出。");
                            await Task.Delay(100);
                        }
                        if (Program.ImportTest == "flow") {
                            for (int wait = 0; wait < 30 && plan.events.Count != 96; wait++) await Task.Delay(100);
                            if (plan.events.Count != 96) throw new InvalidOperationException("导入课表未同步到原生提醒计划。");
                        }
                        using (FileStream preview = File.Create(Path.Combine(Program.DataRoot, "import-preview.png"))) await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
                    }
                    if (Program.CourseTest == "flow" || Program.CourseTest == "open") {
                        await browser.CoreWebView2.ExecuteScriptAsync(Encoding.UTF8.GetString(Program.Resource("CourseSmoke.js")));
                        string courseJson = null;
                        for (int wait = 0; wait < 180; wait++) {
                            string value = await browser.CoreWebView2.ExecuteScriptAsync("window.__courseSmoke ? JSON.stringify(window.__courseSmoke) : null");
                            if (value != "null") { courseJson = DesktopReminders.Json.Deserialize<string>(value); break; }
                            await Task.Delay(100);
                        }
                        if (courseJson == null) throw new InvalidOperationException("课程签到验证超时。");
                        Dictionary<string, object> report = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(courseJson);
                        if (!(bool)report["ok"]) throw new InvalidOperationException(courseJson);
                        courseReport = report;
                        using (FileStream preview = File.Create(Path.Combine(Program.DataRoot, "course-preview.png"))) await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
                        if (Program.CourseTest == "open") {
                            await browser.CoreWebView2.ExecuteScriptAsync("document.querySelector('button[data-view=\"courses\"]').click()");
                            await Task.Delay(100);
                            using (FileStream preview = File.Create(Path.Combine(Program.DataRoot, "course-list-preview.png"))) await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
                            await browser.CoreWebView2.ExecuteScriptAsync("document.querySelector('[data-signin-course-id=\"900000007\"]').click()");
                            for (int wait = 0; wait < 100 && qrOpenResult == null; wait++) await Task.Delay(100);
                            if (qrOpenResult == null) throw new InvalidOperationException("课程卡片没有打开签到窗口。");
                            object card = await qrBrowser.VerifyFixtureAsync();
                            qrOpenResult = null;
                            await browser.CoreWebView2.ExecuteScriptAsync("document.querySelector('.course-card-edit[data-course-id=\"900000007\"]').click()");
                            await Task.Delay(100);
                            using (FileStream preview = File.Create(Path.Combine(Program.DataRoot, "course-editor-preview.png"))) await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
                            await browser.CoreWebView2.ExecuteScriptAsync("document.getElementById('course-signin-button').click()");
                            for (int wait = 0; wait < 100 && qrOpenResult == null; wait++) await Task.Delay(100);
                            if (qrOpenResult == null) throw new InvalidOperationException("编辑窗口没有打开签到页。");
                            await Task.Delay(150);
                            object editor = await qrBrowser.VerifyFixtureAsync();
                            courseOpenReport = new { card = card, editor = editor };
                        }
                    }
                    if (Program.QrTest != null) {
                        await browser.CoreWebView2.ExecuteScriptAsync(Encoding.UTF8.GetString(Program.Resource("QrSmoke.js")));
                        string qrJson = null;
                        for (int wait = 0; wait < 180; wait++) {
                            string value = await browser.CoreWebView2.ExecuteScriptAsync("window.__qrSmoke ? JSON.stringify(window.__qrSmoke) : null");
                            if (value != "null") { qrJson = DesktopReminders.Json.Deserialize<string>(value); break; }
                            await Task.Delay(100);
                        }
                        if (qrJson == null) throw new InvalidOperationException("二维码集成验证超时。");
                        Dictionary<string, object> qr = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(qrJson);
                        if (!(bool)qr["ok"]) throw new InvalidOperationException(qrJson);
                        qrReport = qr;
                        if (Program.QrTest == "open" || Program.QrTest == "open-error") {
                            string operation = Program.QrTest == "open" ? "document.getElementById('qr-frame').contentDocument.getElementById('open-button').click()" : "window.chrome.webview.postMessage({type:'qr:open',url:'https://example.invalid/Roll/qrsign?qr=bad'})";
                            await browser.CoreWebView2.ExecuteScriptAsync(operation);
                            for (int wait = 0; wait < 100 && qrOpenResult == null; wait++) await Task.Delay(100);
                            if (qrOpenResult == null) throw new InvalidOperationException("打开二维码未经过原生消息桥。");
                            if (Program.QrTest == "open") {
                                object first = await qrBrowser.VerifyFixtureAsync();
                                await browser.CoreWebView2.ExecuteScriptAsync("document.getElementById('qr-frame').contentDocument.getElementById('refresh-button').click()");
                                await Task.Delay(100);
                                qrOpenResult = null;
                                await browser.CoreWebView2.ExecuteScriptAsync(operation);
                                for (int wait = 0; wait < 100 && qrOpenResult == null; wait++) await Task.Delay(100);
                                await Task.Delay(200);
                                object second = await qrBrowser.VerifyFixtureAsync();
                                int rejected = 0;
                                foreach (string invalid in new string[] { "javascript:alert(1)", "file:///C:/test", "http://eol.sicau.edu.cn/Roll/qrsign?qr=x", "https://eol.sicau.edu.cn.evil.invalid/Roll/qrsign?qr=x", "https://user@eol.sicau.edu.cn/Roll/qrsign?qr=x", "https://eol.sicau.edu.cn:8443/Roll/qrsign?qr=x", "https://eol.sicau.edu.cn/other?qr=x", "https://eol.sicau.edu.cn/Roll/qrsign?qr=x&qr=y", "https://eol.sicau.edu.cn/Roll/qrsign?qr=YWJj", "https://eol.sicau.edu.cn/Roll/qrsign?qr=x#fragment" }) {
                                    try { QrBrowserWindow.ValidateUrl(invalid); } catch (InvalidDataException) { rejected++; }
                                }
                                string mainAgent = DesktopReminders.Json.Deserialize<string>(await browser.CoreWebView2.ExecuteScriptAsync("navigator.userAgent"));
                                qrOpenReport = new { first = first, second = second, rejected = rejected, mainAgent = mainAgent };
                            }
                            await Task.Delay(150);
                        } else if (Program.QrTest != "flow") {
                            await browser.CoreWebView2.ExecuteScriptAsync("document.getElementById('qr-frame').contentDocument.getElementById('download-button').click()");
                            for (int wait = 0; wait < 50 && qrTestResult == null; wait++) await Task.Delay(100);
                            if (qrTestResult == null) throw new InvalidOperationException("二维码下载没有到达原生保存流程。");
                            await Task.Delay(100);
                        }
                        using (FileStream preview = File.Create(Path.Combine(Program.DataRoot, "qr-preview.png"))) await browser.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, preview);
                    }
                    if (Program.BackupTest != null) {
                        await browser.CoreWebView2.ExecuteScriptAsync("document.getElementById('export-button').click()");
                        for (int wait = 0; wait < 50 && backupTestResult == null; wait++) await Task.Delay(100);
                        if (backupTestResult == null) throw new InvalidOperationException("导出按钮没有到达原生保存流程。");
                        await Task.Delay(100);
                    }
                    string snapshot = await browser.CoreWebView2.ExecuteScriptAsync("JSON.stringify({courses:document.getElementById('stat-courses').textContent,credits:document.getElementById('stat-credits').textContent,nativeVisible:!document.getElementById('native-reminder-section').hidden,nativeEnabled:document.getElementById('native-reminders').checked,firstCourse:document.querySelector('.course-card h3')?.textContent||'',events:document.querySelectorAll('.course-event').length,title:document.title,toast:document.getElementById('toast').textContent})");
                    string decoded = DesktopReminders.Json.Deserialize<string>(snapshot);
                    Dictionary<string, object> dom = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(decoded);
                    Dictionary<string, object> persisted = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(timetablePath, Encoding.UTF8));
                    int persistedCount = 0;
                    foreach (object item in (IEnumerable)persisted["courses"]) persistedCount++;
                    bool ok = (string)dom["courses"] == persistedCount.ToString() && (bool)dom["nativeVisible"];
                    File.WriteAllText(Path.Combine(Program.DataRoot, "smoke-result.json"), DesktopReminders.Json.Serialize(new { ok = ok, dataRoot = Program.DataRoot, layout = layoutReport, dom = dom, tools = toolReport, toolResult = courseToolResult, backup = backupTestResult, imports = importReport, transferExport = transferExportResult, qr = qrReport, courses = courseReport, courseOpen = courseOpenReport, qrExport = qrTestResult, qrOpen = qrOpenReport, qrOpenResult = qrOpenResult, qrNetworkRequests = qrNetworkRequests, browserDownloads = browserDownloads, planEvents = plan.events.Count, webviewVersion = browser.CoreWebView2.Environment.BrowserVersionString, saved = File.Exists(timetablePath) }), Encoding.UTF8);
                    Program.Result = ok ? 0 : 1; quitting = true; Close();
                }
                if (Program.SmokeTest && now - startedAt > (Program.ToolTest != null ? 45000 : 30000)) Fail("桌面启动验证超时。");
            } catch (Exception error) { Fail(error.ToString()); }
        }
        private void Fail(string message) {
            if (IsDisposed || Disposing) return;
            if (Program.SmokeTest) {
                File.WriteAllText(Path.Combine(Program.DataRoot, "smoke-result.json"), DesktopReminders.Json.Serialize(new { ok = false, error = message }), Encoding.UTF8);
                Program.Result = 1; quitting = true; Close();
            } else {
                settings.remindersEnabled = false; reminderMenu.Checked = false;
                loading.Text = "无法打开课程表：\n" + message + "\n请确认 Microsoft Edge WebView2 Runtime 可用。";
                loading.Visible = true;
            }
        }
        protected override void OnFormClosing(FormClosingEventArgs e) {
            if (!quitting && e.CloseReason == CloseReason.UserClosing && settings.remindersEnabled) {
                e.Cancel = true; Hide(); Notify("课间 · 已收起到托盘", "上课和下课提醒继续运行。右键右下角课间图标可退出程序。");
            }
            base.OnFormClosing(e);
        }
        protected override void Dispose(bool disposing) {
            if (disposing) { courseTools.StopSmoke(); timer.Stop(); timer.Dispose(); tray.Visible = false; tray.Dispose(); if (qrBrowser != null) qrBrowser.Dispose(); browser.Dispose(); }
            base.Dispose(disposing);
        }
    }
}
