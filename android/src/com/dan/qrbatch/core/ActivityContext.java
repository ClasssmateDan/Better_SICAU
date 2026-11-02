package com.dan.qrbatch.core;

public final class ActivityContext {
    public final String activeId;
    public final String courseId;
    public final String classId;
    public final String title;

    public ActivityContext(String activeId, String courseId, String classId, String title) {
        this.activeId = value(activeId);
        this.courseId = value(courseId);
        this.classId = value(classId);
        this.title = value(title);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
