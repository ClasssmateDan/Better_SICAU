using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace KeJianDesktop {
    internal sealed class QrBrowserWindow : Form {
        internal const string UserAgentValue = "MicroMessenger";
        private readonly WebView2 browser;
        private readonly TextBox address;
        private readonly Label status;
        private readonly bool fixture;
        private Task initialization;
        private readonly List<string> observedAgents = new List<string>();
        private int blockedRequests;
        private string requestedUrl;

        internal static string ValidateUrl(string value) {
            Uri uri;
            if (String.IsNullOrEmpty(value) || value.Length > 4096 || !Uri.TryCreate(value, UriKind.Absolute, out uri)
                || uri.Scheme != "https" || uri.Host != "eol.sicau.edu.cn" || uri.Port != 443
                || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0 || uri.AbsolutePath != "/Roll/qrsign")
                throw new InvalidDataException("只能打开四川农业大学 EOL 的当前二维码链接。");
            string query = uri.Query;
            if (!query.StartsWith("?qr=", StringComparison.Ordinal) || query.IndexOf('&') >= 0) throw new InvalidDataException("二维码链接参数不正确。");
            string encoded = Uri.UnescapeDataString(query.Substring(4).Replace("+", " "));
            byte[] bytes;
            try { bytes = Convert.FromBase64String(encoded); }
            catch (FormatException) { throw new InvalidDataException("二维码编码不正确。"); }
            if (Convert.ToBase64String(bytes) != encoded || !Regex.IsMatch(Encoding.ASCII.GetString(bytes), @"\A[0-9]{1,32},[1-9][0-9]{12}\z"))
                throw new InvalidDataException("二维码必须包含有效课程编号与时间值。");
            return uri.AbsoluteUri;
        }

        internal QrBrowserWindow(bool useFixture) {
            fixture = useFixture;
            Text = "当前二维码 · 微信 UA";
            Rectangle workArea = Screen.PrimaryScreen.WorkingArea;
            ClientSize = new Size(Math.Min(1100, workArea.Width - 100), Math.Min(850, workArea.Height - 100));
            MinimumSize = new Size(640, 500);
            StartPosition = FormStartPosition.CenterParent;
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            Font = new Font("Microsoft YaHei UI", 10);
            FlowLayoutPanel tools = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 46, Padding = new Padding(8, 6, 8, 0), WrapContents = false };
            Button back = new Button { Text = "后退", AutoSize = true };
            Button reload = new Button { Text = "刷新", AutoSize = true };
            Button close = new Button { Text = "关闭网页", AutoSize = true };
            back.Click += delegate { if (browser.CoreWebView2 != null && browser.CoreWebView2.CanGoBack) browser.CoreWebView2.GoBack(); };
            reload.Click += delegate { if (browser.CoreWebView2 != null) browser.CoreWebView2.Reload(); };
            close.Click += delegate { Close(); };
            tools.Controls.Add(back); tools.Controls.Add(reload); tools.Controls.Add(close);
            tools.Controls.Add(new Label { Text = "User-Agent：MicroMessenger", AutoSize = true, Padding = new Padding(10, 6, 0, 0) });
            address = new TextBox { Dock = DockStyle.Top, ReadOnly = true, BorderStyle = BorderStyle.FixedSingle, Height = 30 };
            status = new Label { Dock = DockStyle.Bottom, Height = 42, Padding = new Padding(8), Text = "正在准备微信 UA 窗口…", AutoEllipsis = true };
            browser = new WebView2 { Dock = DockStyle.Fill };
            Controls.Add(browser); Controls.Add(address); Controls.Add(tools); Controls.Add(status);
            if (Program.SmokeTest) { Opacity = 0; ShowInTaskbar = false; }
        }

        private async Task InitializeAsync() {
            CoreWebView2Environment env = await Program.CreateBrowserEnvironment(Path.Combine(Program.DataRoot, "QrWebView2"));
            await browser.EnsureCoreWebView2Async(env);
            if (IsDisposed || Disposing) throw new ObjectDisposedException("二维码窗口");
            CoreWebView2 core = browser.CoreWebView2;
            // Apply before the first navigation, rather than only replacing navigator.userAgent.
            core.Settings.UserAgent = UserAgentValue;
            core.Settings.IsWebMessageEnabled = false;
            core.Settings.AreHostObjectsAllowed = false;
            core.Settings.IsStatusBarEnabled = false;
            core.NavigationStarting += delegate(object sender, CoreWebView2NavigationStartingEventArgs e) {
                Uri uri;
                if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out uri) || uri.Scheme != "https" || uri.UserInfo.Length != 0) {
                    e.Cancel = true; status.Text = "此地址不能在当前窗口打开。";
                } else status.Text = "正在载入…  UA：MicroMessenger";
            };
            core.SourceChanged += delegate { address.Text = core.Source; };
            core.NavigationCompleted += delegate(object sender, CoreWebView2NavigationCompletedEventArgs e) {
                status.Text = e.IsSuccess ? "UA：MicroMessenger · 如页面要求登录或微信授权，请按页面提示操作。" : "网页载入失败：" + e.WebErrorStatus + "。可检查网络后点击刷新。";
            };
            core.NewWindowRequested += delegate(object sender, CoreWebView2NewWindowRequestedEventArgs e) {
                e.Handled = true;
                Uri uri;
                if (e.IsUserInitiated && Uri.TryCreate(e.Uri, UriKind.Absolute, out uri) && uri.Scheme == "https" && uri.UserInfo.Length == 0) core.Navigate(uri.AbsoluteUri);
            };
            core.LaunchingExternalUriScheme += delegate(object sender, CoreWebView2LaunchingExternalUriSchemeEventArgs e) {
                e.Cancel = true; status.Text = "此操作需要其他应用，请在对应应用中完成。";
            };
            core.DownloadStarting += delegate(object sender, CoreWebView2DownloadStartingEventArgs e) {
                e.Cancel = true; e.Handled = true; status.Text = "此窗口用于打开二维码网页，图片请在预测页保存。";
            };
            if (fixture) {
                // A development-only response for a synthetic origin. Never contact EOL during tests.
                core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);
                core.WebResourceRequested += delegate(object sender, CoreWebView2WebResourceRequestedEventArgs e) {
                    Uri uri = new Uri(e.Request.Uri);
                    if (uri.Host == "qr-check.kejian.local") {
                        string agent = e.Request.Headers.GetHeader("User-Agent");
                        observedAgents.Add(agent);
                        string html = "<!doctype html><meta charset='utf-8'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'unsafe-inline'\"><title>微信 UA 本地验证</title><h1>本地 UA 验证</h1><pre id='header'>" + WebUtility.HtmlEncode(agent) + "</pre><pre id='script'></pre><script>document.getElementById('script').textContent=navigator.userAgent;</script>";
                        e.Response = env.CreateWebResourceResponse(new MemoryStream(Encoding.UTF8.GetBytes(html)), 200, "OK", "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store");
                    } else {
                        blockedRequests++;
                        e.Response = env.CreateWebResourceResponse(new MemoryStream(), 403, "Blocked in local test", "Content-Type: text/plain");
                    }
                };
            }
        }

        internal async Task OpenAsync(string value) {
            string url = ValidateUrl(value);
            if (initialization == null) initialization = InitializeAsync();
            await initialization;
            if (IsDisposed || Disposing) throw new ObjectDisposedException("二维码窗口");
            requestedUrl = url;
            browser.CoreWebView2.Settings.UserAgent = UserAgentValue;
            string destination = fixture ? "https://qr-check.kejian.local/current" + new Uri(url).Query : url;
            browser.CoreWebView2.Navigate(destination);
            if (!Program.SmokeTest) { Show(); WindowState = FormWindowState.Normal; Activate(); }
        }

        internal async Task<object> VerifyFixtureAsync() {
            if (!fixture) throw new InvalidOperationException("仅能验证本地测试页面。");
            for (int wait = 0; wait < 100; wait++) {
                string value = await browser.CoreWebView2.ExecuteScriptAsync("location.hostname === 'qr-check.kejian.local' && document.readyState === 'complete' && !!document.getElementById('script')");
                if (value == "true") {
                    string json = await browser.CoreWebView2.ExecuteScriptAsync("JSON.stringify({agent:navigator.userAgent,header:document.getElementById('header').textContent,url:location.href})");
                    Dictionary<string, object> page = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(DesktopReminders.Json.Deserialize<string>(json));
                    return new { page = page, firstRequestAgent = observedAgents.Count > 0 ? observedAgents[0] : null, requestCount = observedAgents.Count, blockedRequests = blockedRequests, requestedUrl = requestedUrl,
                        messageBridge = browser.CoreWebView2.Settings.IsWebMessageEnabled, hostObjects = browser.CoreWebView2.Settings.AreHostObjectsAllowed, profile = browser.CoreWebView2.Environment.UserDataFolder };
                }
                await Task.Delay(100);
            }
            throw new InvalidOperationException("UA 本地页面验证超时。");
        }

        protected override void Dispose(bool disposing) {
            if (disposing) browser.Dispose();
            base.Dispose(disposing);
        }
    }
}
