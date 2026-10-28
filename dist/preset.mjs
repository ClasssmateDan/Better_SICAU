import { validateData } from './model.mjs';

// Public distribution: no personal courses and no classid reference table.
export const CLASSID_REFERENCE = Object.freeze([]);

export function fillKnownClassids(input) {
  let changed = false;
  const courses = input.courses.map(course => {
    if (Object.prototype.hasOwnProperty.call(course, 'classid')) return course;
    changed = true;
    return { ...course, classid: '' };
  });
  return { data: changed ? { ...input, courses } : input, changed };
}

export function createPreset() {
  return validateData({
    version: 1,
    semester: {
      name: '新学期', startDate: '2026-09-07', weeks: 20,
      periods: [
        ['08:00', '08:45'], ['08:55', '09:40'], ['10:00', '10:45'], ['10:55', '11:40'],
        ['14:00', '14:45'], ['14:55', '15:40'], ['16:00', '16:45'], ['16:55', '17:40'],
        ['19:00', '19:45'], ['19:55', '20:40'],
      ],
    },
    courses: [],
  });
}
