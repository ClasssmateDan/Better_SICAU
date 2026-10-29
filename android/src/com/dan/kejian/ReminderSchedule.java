package com.dan.kejian;

import java.time.*;
import java.util.*;

/** Campus times are always Asia/Shanghai, including when the phone travels. */
public final class ReminderSchedule {
    public static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");
    public static final class Meeting {
        public int day, start, end, startWeek, endWeek;
        public String parity = "all", room = "";
        public Set<Integer> onlineWeeks = new HashSet<>();
    }
    public static final class Course {
        public String id, name, teacher = "", room = "";
        public List<Meeting> meetings = new ArrayList<>();
    }
    public static final class Timetable {
        public LocalDate monday;
        public int weeks;
        public List<String[]> periods = new ArrayList<>();
        public List<Course> courses = new ArrayList<>();
    }
    public static final class Event {
        public final String id, title, body;
        public final long at;
        Event(String id, long at, String title, String body) { this.id = id; this.at = at; this.title = title; this.body = body; }
    }
    public static List<Event> build(Timetable table) {
        List<Event> events = new ArrayList<>();
        for (int week = 1; week <= table.weeks; week++) {
            for (Course course : table.courses) for (Meeting meeting : course.meetings) {
                if (week < meeting.startWeek || week > meeting.endWeek) continue;
                if (meeting.parity.equals("odd") && week % 2 == 0 || meeting.parity.equals("even") && week % 2 != 0) continue;
                LocalDate date = table.monday.plusDays((week - 1L) * 7 + meeting.day - 1);
                String room = meeting.onlineWeeks.contains(week) ? "线上授课" : meeting.room.isEmpty() ? course.room : meeting.room;
                for (int period = meeting.start; period <= meeting.end; period++) for (int part = 0; part < 2; part++) {
                    String time = table.periods.get(period - 1)[part], kind = part == 0 ? "start" : "end";
                    long at = date.atTime(LocalTime.parse(time)).atZone(CAMPUS_ZONE).toInstant().toEpochMilli();
                    String id = date + "|" + course.id + "|" + period + "|" + kind + "|" + time;
                    String body = time + " · " + course.name + "\n第 " + week + " 周 · 第 " + period + " 节 · " + (room.isEmpty() ? "地点待定" : room) + (course.teacher.isEmpty() ? "" : "\n" + course.teacher);
                    events.add(new Event(id, at, "课间 · " + (part == 0 ? "上课" : "下课") + "提醒", body));
                }
            }
        }
        events.sort(Comparator.comparingLong((Event event) -> event.at).thenComparing(event -> event.id));
        return events;
    }
    public static long nextTime(List<Event> events, long now) {
        for (Event event : events) if (event.at > now) return event.at;
        return -1;
    }
    public static List<Event> due(List<Event> events, long scheduledAt, long now, long deliveredAt) {
        List<Event> result = new ArrayList<>();
        if (scheduledAt <= deliveredAt || scheduledAt > now || now - scheduledAt > 1800000) return result;
        for (Event event : events) if (event.at == scheduledAt) result.add(event);
        return result;
    }
}
