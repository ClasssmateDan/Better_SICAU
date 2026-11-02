import { createHolidayEvents } from '../dist/holidays.mjs';

// Generate Android's built-in events from the same data used by the web/PC app.
export function holidayJavaSource() {
  const lines = createHolidayEvents().map(event => `events.add(new ReminderSchedule.Event(${JSON.stringify(event.id)},${event.at}L,${JSON.stringify(event.title)},${JSON.stringify(event.body)}));`);
  return `package com.dan.kejian;
public final class BuiltinHolidays {
  public static java.util.List<ReminderSchedule.Event> events() {
    java.util.List<ReminderSchedule.Event> events=new java.util.ArrayList<>();
    ${lines.join('\n    ')}
    return events;
  }
}
`;
}
