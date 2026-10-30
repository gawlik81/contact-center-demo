import {
  formatHoursMinutes,
  formatMinSec,
  splitHoursMinutes,
  splitMinSec,
  toSafeSeconds,
} from './duration-format.util';

describe('duration-format.util', () => {
  it.each([
    [0, '0:00'],
    [3599, '0:59'],
    [3600, '1:00'],
    [13556, '3:45'], // 225:56 as m:ss; floor, not rounded up to 3:46
    [14400, '4:00'],
    [-5, '0:00'],
    [NaN, '0:00'],
    [Infinity, '0:00'],
    [3599.9, '0:59'],
    [86400, '24:00'],
  ])('formatHoursMinutes(%s) = %s', (s, expected) => {
    expect(formatHoursMinutes(s)).toBe(expected);
  });

  it.each([
    [0, '0:00'],
    [185, '3:05'],
    [3599, '59:59'],
    [3600, '60:00'],
    [13556, '225:56'],
    [-5, '0:00'],
    [NaN, '0:00'],
    [Infinity, '0:00'],
    [185.9, '3:05'],
  ])('formatMinSec(%s) = %s', (s, expected) => {
    expect(formatMinSec(s)).toBe(expected);
  });

  it('splitHoursMinutes floors both parts', () => {
    expect(splitHoursMinutes(13556)).toEqual({ hours: 3, minutes: 45 });
  });

  it('splitHoursMinutes handles zero, negative, NaN and a full day', () => {
    expect(splitHoursMinutes(0)).toEqual({ hours: 0, minutes: 0 });
    expect(splitHoursMinutes(-1)).toEqual({ hours: 0, minutes: 0 });
    expect(splitHoursMinutes(NaN)).toEqual({ hours: 0, minutes: 0 });
    expect(splitHoursMinutes(86400)).toEqual({ hours: 24, minutes: 0 });
  });

  it('splitMinSec does not wrap minutes into hours', () => {
    expect(splitMinSec(13556)).toEqual({ minutes: 225, seconds: 56 });
  });

  it('toSafeSeconds clamps invalid input to 0 and floors fractions', () => {
    expect(toSafeSeconds(-3)).toBe(0);
    expect(toSafeSeconds(Infinity)).toBe(0);
    expect(toSafeSeconds(NaN)).toBe(0);
    expect(toSafeSeconds(7.9)).toBe(7);
  });
});
