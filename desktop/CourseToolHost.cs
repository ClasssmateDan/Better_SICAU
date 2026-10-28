using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;

namespace KeJianDesktop {
    internal sealed class CourseToolHost {
        internal static readonly string[] ExtensionFiles = { "manifest.json", "background.js", "connect.js", "har_format.js", "help.html" };
        internal readonly string DataDirectory;
        internal Dictionary<string, object> LastState;
        private Process process;
        internal CourseToolHost() { DataDirectory = Path.Combine(Program.DataRoot, "course-tools"); }
        private static void WriteResource(string filename, byte[] bytes) {
            Directory.CreateDirectory(Path.GetDirectoryName(filename));
            if (File.Exists(filename)) {
                using (SHA256 hash = SHA256.Create()) {
                    if (Convert.ToBase64String(hash.ComputeHash(File.ReadAllBytes(filename))) == Convert.ToBase64String(hash.ComputeHash(bytes))) return;
                }
            }
            string pending = filename + ".tmp";
            File.WriteAllBytes(pending, bytes);
            if (File.Exists(filename)) File.Replace(pending, filename, null); else File.Move(pending, filename);
        }
        private string PrepareAssets() {
            string runtime = Path.Combine(Program.DataRoot, "runtime", "1.7.3", "course-tools");
            Directory.CreateDirectory(DataDirectory);
            string exe = Path.Combine(runtime, "KeJianCourseTools.exe");
            WriteResource(exe, Program.Resource("course-tool.exe"));
            foreach (string name in ExtensionFiles) WriteResource(Path.Combine(DataDirectory, "browser-extension", name), Program.Resource("course-tool.extension." + name));
            WriteResource(Path.Combine(DataDirectory, "课程工具使用说明.md"), Program.Resource("course-tool.README.md"));
            WriteResource(Path.Combine(DataDirectory, "课程工具第三方许可.txt"), Program.Resource("course-tool.LICENSE.txt"));
            return exe;
        }
        private static string Quote(string value) {
            // Only fixed, app-owned paths reach the process command line. No shell is involved.
            if (value.IndexOf('"') >= 0 || value.EndsWith("\\")) throw new InvalidDataException("工具目录格式不正确。");
            return "\"" + value + "\"";
        }
        internal static Dictionary<string, object> BuildContext(Dictionary<string, object> message, string timetableJson) {
            string action = message.ContainsKey("action") ? message["action"] as string : null;
            if (action != "signin" && action != "reports") throw new InvalidDataException("课程工具操作不正确。");
            string selected = message.ContainsKey("courseId") ? message["courseId"] as string : "";
            if (selected == null || selected.Length > 100) throw new InvalidDataException("课程编号不正确。");
            string classid = "", name = "";
            if (selected.Length > 0) {
                Dictionary<string, object> table = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(timetableJson);
                IEnumerable courses = table.ContainsKey("courses") ? table["courses"] as IEnumerable : null;
                bool found = false;
                if (courses != null) foreach (object entry in courses) {
                    Dictionary<string, object> course = entry as Dictionary<string, object>;
                    if (course == null || !course.ContainsKey("id") || course["id"] as string != selected) continue;
                    classid = course.ContainsKey("classid") ? course["classid"] as string : null;
                    name = course.ContainsKey("name") ? course["name"] as string : null;
                    if (classid == null || !Regex.IsMatch(classid, @"\A[0-9]{1,32}\z") || String.IsNullOrEmpty(name) || name.Length > 120 || Regex.IsMatch(name, @"[\x00-\x1f]")) throw new InvalidDataException("这门课程尚未填写有效 classid。");
                    found = true; break;
                }
                if (!found) throw new InvalidDataException("课程不在当前课表中，请刷新后重试。");
            }
            return new Dictionary<string, object> { { "version", 1 }, { "requestId", Guid.NewGuid().ToString("N") }, { "action", action }, { "classid", classid }, { "courseName", name } };
        }
        internal async Task<object> OpenAsync(Dictionary<string, object> message, string timetableJson) {
            string action = message.ContainsKey("action") ? message["action"] as string : null;
            Dictionary<string, object> context = null;
            if (action != "extension") context = BuildContext(message, timetableJson);
            string executable = await Task.Run(() => PrepareAssets());
            if (action == "extension") {
                if (!Program.SmokeTest) Process.Start(new ProcessStartInfo(Path.Combine(DataDirectory, "browser-extension")) { UseShellExecute = true });
                return new { type = "course-tools:result", status = "opened", action = action, message = "扩展文件夹已打开，页面已显示安装步骤。请选择当前位置的整个扩展文件夹；迁移或移动目录后请重新加载。" };
            }
            string requestFile = Path.Combine(DataDirectory, "request.json");
            Program.WriteAtomic(requestFile, DesktopReminders.Json.Serialize(context));
            if (process == null || process.HasExited) {
                string temporary = Path.Combine(DataDirectory, "runtime-temp"); Directory.CreateDirectory(temporary);
                ProcessStartInfo start = new ProcessStartInfo(executable, "--data-dir " + Quote(DataDirectory) + " --context-file " + Quote(requestFile) + (Program.ToolTest != null ? " --integration-smoke" : "")) {
                    UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = DataDirectory
                };
                start.EnvironmentVariables["TEMP"] = temporary; start.EnvironmentVariables["TMP"] = temporary;
                process = Process.Start(start);
                if (process == null) throw new InvalidOperationException("课程工具未能启动。");
            }
            string requestId = (string)context["requestId"];
            for (int attempt = 0; attempt < 200; attempt++) {
                string filename = Path.Combine(DataDirectory, "ready.json");
                if (File.Exists(filename)) {
                    try {
                        Dictionary<string, object> state = DesktopReminders.Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(filename, Encoding.UTF8));
                        if (state.ContainsKey("requestId") && state["requestId"] as string == requestId && state.ContainsKey("status") && state["status"] as string == "ready") {
                            LastState = state;
                            return new { type = "course-tools:result", status = "opened", action = action, message = "课程工具窗口已打开。课表只传入课程定位信息，请在工具中核对本人账号、课程和 IP，再手动发送签到或上传。" };
                        }
                    } catch (IOException) { } catch (ArgumentException) { }
                }
                if (process.HasExited && process.ExitCode != 0) throw new InvalidOperationException("课程工具启动失败，可查看工具数据目录中的 startup-error.txt。");
                await Task.Delay(100);
            }
            throw new TimeoutException("课程工具尚未准备完成，请稍后重试；若已有旧工具窗口，请先关闭旧窗口。");
        }
        internal void StopSmoke() {
            if (Program.ToolTest == null) return;
            Directory.CreateDirectory(DataDirectory);
            File.WriteAllText(Path.Combine(DataDirectory, "smoke-stop.request"), "stop");
        }
    }
}
