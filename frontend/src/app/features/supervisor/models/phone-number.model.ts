export interface PhoneNumber {
  phoneNumberId: string;
  number: string; // E.164 format (+48...)
  displayName?: string;
  isActive: boolean;
}

export interface PhoneRoutingRule {
  ruleId: string;
  phoneNumberId: string;
  ivrTreeId?: string;
  queueId?: string;
  daysOfWeek: number[]; // 1=Mon, 7=Sun (ISO day of week)
  timeStart: string; // "HH:mm"
  timeEnd: string; // "HH:mm"
  isActive: boolean;
}

export interface CreatePhoneNumberRequest {
  number: string;
  displayName?: string;
}

export interface UpdatePhoneNumberRequest {
  displayName?: string;
  isActive?: boolean;
}

export interface CreateRoutingRuleRequest {
  ivrTreeId?: string | null;
  queueId?: string | null;
  daysOfWeek: number[];
  timeStart: string;
  timeEnd: string;
  isActive: boolean;
}

export interface UpdateRoutingRuleRequest {
  ivrTreeId?: string | null;
  queueId?: string | null;
  daysOfWeek?: number[];
  timeStart?: string;
  timeEnd?: string;
  isActive?: boolean;
}

export const DAY_LABELS: Record<number, string> = {
  1: 'agent.calendar.days.MON',
  2: 'agent.calendar.days.TUE',
  3: 'agent.calendar.days.WED',
  4: 'agent.calendar.days.THU',
  5: 'agent.calendar.days.FRI',
  6: 'agent.calendar.days.SAT',
  7: 'agent.calendar.days.SUN',
};

export const ALL_DAYS = [1, 2, 3, 4, 5, 6, 7];
