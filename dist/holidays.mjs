// Mainland China holiday arrangements and festival dates. Data stays offline.
// 2026 arrangements: State Council General Office, 2025-11-04.
// 2027 lunar dates: Hong Kong Observatory Gregorian/Lunar calendar (dates only).
export const HOLIDAY_YEARS = [2026, 2027];
export const HOLIDAY_SOURCES = [
  { name: '2026 年国务院放假调休安排', url: 'https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm' },
  { name: '2026 年公历农历对照', url: 'https://data.weather.gov.hk/weatherAPI/hko_data/calendar/nongli_calendar_2026.csv' },
  { name: '2027 年公历农历对照', url: 'https://data.weather.gov.hk/weatherAPI/hko_data/calendar/nongli_calendar_2027.csv' },
];
export const HOLIDAY_PERIODS = [
  { name: '元旦', start: '2026-01-01', end: '2026-01-03' },
  { name: '春节', start: '2026-02-15', end: '2026-02-23' },
  { name: '清明节', start: '2026-04-04', end: '2026-04-06' },
  { name: '劳动节', start: '2026-05-01', end: '2026-05-05' },
  { name: '端午节', start: '2026-06-19', end: '2026-06-21' },
  { name: '中秋节', start: '2026-09-25', end: '2026-09-27' },
  { name: '国庆节', start: '2026-10-01', end: '2026-10-07' },
];
export const HOLIDAY_WORKDAYS = [
  { date: '2026-01-04', name: '元旦' },
  { date: '2026-02-14', name: '春节' },
  { date: '2026-02-28', name: '春节' },
  { date: '2026-05-09', name: '劳动节' },
  { date: '2026-09-20', name: '国庆节' },
  { date: '2026-10-10', name: '国庆节' },
];
export const FESTIVAL_DATES = [
  ['2026-01-01', '元旦'], ['2026-02-16', '除夕'], ['2026-02-17', '春节'], ['2026-04-05', '清明节'],
  ['2026-05-01', '劳动节'], ['2026-06-19', '端午节'], ['2026-09-25', '中秋节'], ['2026-10-01', '国庆节'],
  ['2027-01-01', '元旦'], ['2027-02-05', '除夕'], ['2027-02-06', '春节'], ['2027-04-05', '清明节'],
  ['2027-05-01', '劳动节'], ['2027-06-09', '端午节'], ['2027-09-15', '中秋节'], ['2027-10-01', '国庆节'],
];

export function holidayForDate(date) {
  const work = HOLIDAY_WORKDAYS.find(item => item.date === date);
  if (work) return { kind: 'work', name: '调休上班', description: `${work.name}调休上班；课程以课表和学校通知为准。` };
  const period = HOLIDAY_PERIODS.find(item => date >= item.start && date <= item.end);
  const festival = FESTIVAL_DATES.find(item => item[0] === date);
  if (period) return { kind: 'rest', name: festival?.[1] ?? `${period.name}假期`, description: `${period.name}假期：${period.start} 至 ${period.end}。课程以课表和学校通知为准。` };
  return festival ? { kind: 'festival', name: festival[1], description: `${festival[1]}。此年份仅内置节日日期，未内置放假调休安排。` } : null;
}

export function holidayYearNote(year) {
  if (year === 2026) return '2026 年按国务院公布的放假调休安排显示。';
  if (year === 2027) return '2027 年仅显示节日日期，放假调休安排待更新。';
  return '此年份未内置节假日数据。';
}

export function createHolidayEvents() {
  const make = (date, kind, body) => ({ id: `holiday|${date}|${kind}`, at: Date.parse(`${date}T09:00:00+08:00`), title: '课间 · 节假日提醒', body });
  const events = HOLIDAY_PERIODS.map(item => make(item.start, 'rest', `${item.name}假期开始 · ${item.start} 至 ${item.end}\n课程以课表和学校通知为准。`));
  for (const item of HOLIDAY_WORKDAYS) events.push(make(item.date, 'work', `${item.date} · ${item.name}调休上班\n课程以课表和学校通知为准。`));
  for (const [date, name] of FESTIVAL_DATES) {
    if (date.startsWith('2027-')) events.push(make(date, 'festival', `${date} · ${name}\n仅提示节日日期，放假调休安排待更新。`));
  }
  return events.sort((a, b) => a.at - b.at || a.id.localeCompare(b.id));
}
