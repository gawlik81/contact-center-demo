/** Clamps to a whole, non-negative number of seconds. NaN and Infinity become 0. */
export function toSafeSeconds(totalSeconds: number): number {
  return Number.isFinite(totalSeconds) && totalSeconds > 0 ? Math.floor(totalSeconds) : 0;
}

/** Splits a duration into whole hours and minutes (floor, never rounds up). */
export function splitHoursMinutes(totalSeconds: number): { hours: number; minutes: number } {
  const safe = toSafeSeconds(totalSeconds);
  return { hours: Math.floor(safe / 3600), minutes: Math.floor((safe % 3600) / 60) };
}

/** Splits a duration into whole minutes (not wrapped into hours) and remaining seconds. */
export function splitMinSec(totalSeconds: number): { minutes: number; seconds: number } {
  const safe = toSafeSeconds(totalSeconds);
  return { minutes: Math.floor(safe / 60), seconds: safe % 60 };
}

/** Elapsed time as `h:mm`, floored: 13556 s -> `3:45`, 3599 s -> `0:59`, 3600 s -> `1:00`. */
export function formatHoursMinutes(totalSeconds: number): string {
  const { hours, minutes } = splitHoursMinutes(totalSeconds);
  return `${hours}:${minutes.toString().padStart(2, '0')}`;
}

/** Duration as `m:ss` (minutes are not wrapped into hours): 185 s -> `3:05`. */
export function formatMinSec(totalSeconds: number): string {
  const { minutes, seconds } = splitMinSec(totalSeconds);
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}
