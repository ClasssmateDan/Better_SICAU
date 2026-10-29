package com.dan.kejian;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.*;

public final class ReminderReceiver extends BroadcastReceiver {
    static final String ACTION = "com.dan.kejian.CLASS_REMINDER", CHANNEL = "class-times-v1";
    static SharedPreferences preferences(Context context) { return context.getSharedPreferences("reminders", Context.MODE_PRIVATE); }
    static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }
    static void setEnabled(Context context, boolean value) throws Exception { preferences(context).edit().putBoolean("enabled", value).commit(); schedule(context); }
    static boolean canNotify(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static boolean exactAllowed(Context context) { return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms(); }
    private static PendingIntent alarmIntent(Context context, long at) {
        return PendingIntent.getBroadcast(context, 1, new Intent(context, ReminderReceiver.class).setAction(ACTION).putExtra("at", at), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static synchronized void schedule(Context context) throws Exception {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        PendingIntent intent = alarmIntent(context, 0);
        manager.cancel(intent);
        if (!enabled(context) || !canNotify(context)) return;
        List<ReminderSchedule.Event> events = ReminderSchedule.build(TimetableStore.parse(new TimetableStore(context).load()));
        long at = ReminderSchedule.nextTime(events, System.currentTimeMillis());
        if (at < 0) return;
        intent = alarmIntent(context, at);
        if (exactAllowed(context)) {
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent); return; }
            catch (SecurityException changedPermission) { /* Permission may change between the check and call. */ }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent);
    }
    static void notify(Context context, String title, String body, int id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "上下课提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("按课表在每节上课和下课时提醒"); manager.createNotificationChannel(channel);
        if (!canNotify(context)) return;
        PendingIntent open = PendingIntent.getActivity(context, 2, new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build();
        manager.notify(id, notice);
    }
    @Override public void onReceive(Context context, Intent intent) {
        try {
            if (ACTION.equals(intent.getAction()) && enabled(context) && canNotify(context)) {
                long at = intent.getLongExtra("at", -1), now = System.currentTimeMillis();
                List<ReminderSchedule.Event> events = ReminderSchedule.build(TimetableStore.parse(new TimetableStore(context).load()));
                List<ReminderSchedule.Event> due = ReminderSchedule.due(events, at, now, preferences(context).getLong("deliveredAt", -1));
                if (!due.isEmpty()) {
                    preferences(context).edit().putLong("deliveredAt", at).commit();
                    StringBuilder body = new StringBuilder();
                    for (ReminderSchedule.Event event : due) { if (body.length() > 0) body.append("\n\n"); body.append(event.body); }
                    notify(context, due.size() == 1 ? due.get(0).title : "课间 · 课程提醒", body.toString(), (int) (at / 1000));
                }
            }
            schedule(context);
        } catch (Exception error) { preferences(context).edit().putString("error", "提醒未能安排，请打开课间检查课表与通知设置。").apply(); }
    }
}
