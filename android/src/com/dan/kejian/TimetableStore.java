package com.dan.kejian;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class TimetableStore {
    private final AtomicFile file;
    private final Context context;
    TimetableStore(Context context) { this.context = context; file = new AtomicFile(new File(context.getFilesDir(), "timetable.json")); }
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (result.size() + count > limit) throw new IOException("文件过大");
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }
    synchronized JSONObject load() throws Exception {
        byte[] bytes = file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists()
            ? readBounded(file.openRead(), 4000000) : readBounded(context.getAssets().open("timetable.json"), 4000000);
        JSONObject data = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        parse(data); return data;
    }
    synchronized void save(JSONObject data) throws Exception {
        parse(data);
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 4000000) throw new IOException("课表文件超过 4 MB");
        // Keep an independent last snapshot as well as AtomicFile's interrupted-write backup.
        if (file.getBaseFile().exists()) {
            AtomicFile previous = new AtomicFile(new File(context.getFilesDir(), "timetable.previous.json"));
            FileOutputStream before = null;
            try { before = previous.startWrite(); before.write(readBounded(file.openRead(), 4000000)); previous.finishWrite(before); }
            catch (Exception error) { if (before != null) previous.failWrite(before); throw error; }
        }
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(bytes); file.finishWrite(stream); }
        catch (Exception error) { if (stream != null) file.failWrite(stream); throw error; }
    }
    static ReminderSchedule.Timetable parse(JSONObject data) throws Exception {
        if (data.getInt("version") != 1) throw new IOException("不支持此课表版本");
        JSONObject semester = data.getJSONObject("semester");
        ReminderSchedule.Timetable table = new ReminderSchedule.Timetable();
        table.monday = LocalDate.parse(semester.getString("startDate"));
        table.weeks = number(semester, "weeks", 1, 30);
        if (table.monday.getDayOfWeek() != DayOfWeek.MONDAY) throw new IOException("第一周应从星期一开始");
        JSONArray periods = semester.getJSONArray("periods");
        if (periods.length() < 1 || periods.length() > 16) throw new IOException("作息节数不正确");
        LocalTime previous = null;
        for (int i = 0; i < periods.length(); i++) {
            JSONArray period = periods.getJSONArray(i);
            if (period.length() != 2) throw new IOException("作息格式不正确");
            String start = period.getString(0), end = period.getString(1);
            if (!start.matches("[0-2][0-9]:[0-5][0-9]") || !end.matches("[0-2][0-9]:[0-5][0-9]")) throw new IOException("作息时间不正确");
            LocalTime first = LocalTime.parse(start), last = LocalTime.parse(end);
            if (!last.isAfter(first) || previous != null && first.isBefore(previous)) throw new IOException("作息时间重叠");
            previous = last; table.periods.add(new String[] { start, end });
        }
        JSONArray courses = data.getJSONArray("courses");
        if (courses.length() > 500) throw new IOException("课程超过 500 门");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < courses.length(); i++) {
            JSONObject item = courses.getJSONObject(i);
            ReminderSchedule.Course course = new ReminderSchedule.Course();
            course.id = item.getString("id"); course.name = item.getString("name");
            course.teacher = item.optString("teacher", ""); course.room = item.optString("room", "");
            if (course.id.isEmpty() || course.id.length() > 100 || !ids.add(course.id) || course.name.isEmpty() || course.name.length() > 60) throw new IOException("课程编号或名称不正确");
            JSONArray meetings = item.getJSONArray("meetings");
            if (meetings.length() > 20 || item.optBoolean("unscheduled") && meetings.length() != 0) throw new IOException("上课安排不正确");
            for (int j = 0; j < meetings.length(); j++) {
                JSONObject source = meetings.getJSONObject(j);
                ReminderSchedule.Meeting meeting = new ReminderSchedule.Meeting();
                meeting.day = number(source, "day", 1, 7); meeting.start = number(source, "start", 1, periods.length());
                meeting.end = number(source, "end", meeting.start, periods.length());
                meeting.startWeek = number(source, "startWeek", 1, table.weeks); meeting.endWeek = number(source, "endWeek", meeting.startWeek, table.weeks);
                meeting.parity = source.getString("parity"); meeting.room = source.optString("room", "");
                if (!Arrays.asList("all", "odd", "even").contains(meeting.parity)) throw new IOException("单双周格式不正确");
                JSONArray online = source.optJSONArray("onlineWeeks");
                if (online != null) for (int k = 0; k < online.length(); k++) {
                    int week = online.getInt(k);
                    if (week < meeting.startWeek || week > meeting.endWeek) throw new IOException("线上周次超出范围");
                    meeting.onlineWeeks.add(week);
                }
                course.meetings.add(meeting);
            }
            table.courses.add(course);
        }
        return table;
    }
    private static int number(JSONObject object, String key, int min, int max) throws Exception {
        double raw = object.getDouble(key); int value = object.getInt(key);
        if (raw != value || value < min || value > max) throw new IOException("课表数值超出范围：" + key);
        return value;
    }
}
