import { validateData } from './model.mjs';

export const TIMETABLE_FILE_LIMIT = 4000000;

export function parseTimetableFile(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > TIMETABLE_FILE_LIMIT) throw new Error('课表文件不能超过 4 MB。');
  let raw;
  try { raw = JSON.parse(text.replace(/^\uFEFF/, '')); }
  catch { throw new Error('文件不是有效的 JSON。请让 AI 按格式说明生成 .json 文件，不要包含解释文字或代码围栏。'); }
  const clean = validateData(raw);
  // Imports may belong to any student. Never infer their classids from this
  // project's personal preset references, including on subsequent restarts.
  return { ...clean, courses: clean.courses.map(course => ({ ...course, classid: course.classid ?? '' })) };
}

export function exportTimetableFile(data) {
  const text = JSON.stringify(validateData(data), null, 2) + '\n';
  if (new TextEncoder().encode(text).length > TIMETABLE_FILE_LIMIT) throw new Error('课表文件超过 4 MB，请精简过长的课程备注后再导出。');
  return text;
}

export function summarizeTimetable(data) {
  return {
    courses: data.courses.length,
    unscheduled: data.courses.filter(course => course.unscheduled).length,
    credits: Number(data.courses.reduce((total, course) => total + course.credits, 0).toFixed(2)),
    classids: data.courses.filter(course => course.classid).length,
  };
}
