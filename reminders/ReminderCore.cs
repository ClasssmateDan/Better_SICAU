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

internal sealed class ReminderEvent {
    public string id { get; set; }
    public long at { get; set; }
    public string title { get; set; }
    public string body { get; set; }
}
internal sealed class ReminderPlan {
    public string kind { get; set; }
    public int version { get; set; }
    public string semesterName { get; set; }
    public List<ReminderEvent> events { get; set; }
}
internal sealed class ReminderState {
    public bool enabled { get; set; }
    public string planPath { get; set; }
    public Dictionary<string, long> sent { get; set; }
}

internal static partial class DesktopReminders {
    internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 32000000 };
    internal static long Now() { return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds; }
    internal static ReminderPlan ReadPlan(string path) {
        return ParsePlan(File.ReadAllText(path, Encoding.UTF8));
    }
    internal static ReminderPlan ParsePlan(string json) {
        ReminderPlan plan = Json.Deserialize<ReminderPlan>(json);
        if (plan == null || plan.kind != "kejian-reminder-plan" || plan.version != 1 || plan.events == null || plan.events.Count > 200000)
            throw new InvalidDataException("请选择从课表“桌面提醒”中导出的提醒计划 JSON。");
        HashSet<string> ids = new HashSet<string>();
        foreach (ReminderEvent ev in plan.events) {
            if (ev == null || String.IsNullOrWhiteSpace(ev.id) || ev.id.Length > 500 || !ids.Add(ev.id)
                || ev.at < 946684800000L || ev.at > 4133980800000L || String.IsNullOrWhiteSpace(ev.title)
                || ev.title.Length > 100 || ev.body == null || ev.body.Length > 2000)
                throw new InvalidDataException("提醒计划中存在无效或重复事件。");
        }
        plan.events = plan.events.OrderBy(ev => ev.at).ThenBy(ev => ev.id).ToList();
        return plan;
    }
    internal static IEnumerable<ReminderEvent> Due(ReminderPlan plan, long after, long now) {
        if (now < after) return Enumerable.Empty<ReminderEvent>();
        long lower = Math.Max(after, now - 60000L);
        return plan.events.Where(ev => ev.at > lower && ev.at <= now);
    }

}
