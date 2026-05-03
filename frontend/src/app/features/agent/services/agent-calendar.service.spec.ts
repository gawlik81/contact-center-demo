import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AgentCalendarService } from './agent-calendar.service';
import {
  AgentBreakRequest,
  AgentBreakResponse,
  AgentCalendarResponse,
} from '../models/agent-calendar.model';

const MOCK_CALENDAR: AgentCalendarResponse = {
  callbacks: [
    {
      id: 'cb-1',
      customerName: 'Jan Kowalski',
      scheduledAt: '2026-04-26T10:00:00Z',
      sourceType: 'AGENT_MANUAL',
      status: 'PENDING',
    },
  ],
  campaigns: [
    {
      id: 'camp-1',
      name: 'Kampania wiosenna',
      startDate: '2026-04-01',
      endDate: '2026-04-30',
      status: 'ACTIVE',
      activeDays: null,
      activeHoursFrom: null,
      activeHoursTo: null,
      timezone: null,
    },
  ],
  breaks: [
    {
      id: 'brk-1',
      startTime: '2026-04-26T12:00:00Z',
      endTime: '2026-04-26T12:30:00Z',
      breakType: 'LUNCH',
      notes: null,
      status: 'PLANNED',
    },
  ],
};

const MOCK_BREAK_RESPONSE: AgentBreakResponse = {
  id: 'brk-new',
  agentId: 'agent-1',
  startTime: '2026-04-26T14:00:00Z',
  endTime: '2026-04-26T14:15:00Z',
  breakType: 'SHORT_BREAK',
  notes: null,
  status: 'PLANNED',
  createdAt: '2026-04-25T08:00:00Z',
  updatedAt: '2026-04-25T08:00:00Z',
};

const MOCK_BREAK_REQUEST: AgentBreakRequest = {
  startTime: '2026-04-26T14:00:00Z',
  endTime: '2026-04-26T14:15:00Z',
  breakType: 'SHORT_BREAK',
};

describe('AgentCalendarService', () => {
  let service: AgentCalendarService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), AgentCalendarService],
    });

    service = TestBed.inject(AgentCalendarService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ── getCalendar ───────────────────────────────────────

  it('should call GET /api/agent/calendar without query params when no dates provided', async () => {
    const resultPromise = firstValueFrom(service.getCalendar());

    const req = httpMock.expectOne((r) => r.url === '/api/agent/calendar');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('from')).toBe(false);
    expect(req.request.params.has('to')).toBe(false);
    req.flush(MOCK_CALENDAR);

    const result = await resultPromise;
    expect(result).toEqual(MOCK_CALENDAR);
  });

  it('should call GET /api/agent/calendar with from and to ISO-8601 params when dates provided', async () => {
    const from = new Date('2026-04-01T00:00:00Z');
    const to = new Date('2026-04-30T23:59:59Z');

    const resultPromise = firstValueFrom(service.getCalendar(from, to));

    const req = httpMock.expectOne(
      (r) =>
        r.url === '/api/agent/calendar' &&
        r.params.get('from') === from.toISOString() &&
        r.params.get('to') === to.toISOString(),
    );
    expect(req.request.method).toBe('GET');
    req.flush(MOCK_CALENDAR);

    const result = await resultPromise;
    expect(result).toEqual(MOCK_CALENDAR);
  });

  // ── addBreak ──────────────────────────────────────────

  it('should call POST /api/agent/breaks with correct body', async () => {
    const resultPromise = firstValueFrom(service.addBreak(MOCK_BREAK_REQUEST));

    const req = httpMock.expectOne('/api/agent/breaks');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(MOCK_BREAK_REQUEST);
    req.flush(MOCK_BREAK_RESPONSE);

    const result = await resultPromise;
    expect(result).toEqual(MOCK_BREAK_RESPONSE);
  });

  // ── updateBreak ───────────────────────────────────────

  it('should call PUT /api/agent/breaks/{id} with correct body', async () => {
    const id = 'brk-1';
    const resultPromise = firstValueFrom(service.updateBreak(id, MOCK_BREAK_REQUEST));

    const req = httpMock.expectOne(`/api/agent/breaks/${id}`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual(MOCK_BREAK_REQUEST);
    req.flush(MOCK_BREAK_RESPONSE);

    const result = await resultPromise;
    expect(result).toEqual(MOCK_BREAK_RESPONSE);
  });

  // ── cancelBreak ───────────────────────────────────────

  it('should call DELETE /api/agent/breaks/{id} and complete with void', async () => {
    const id = 'brk-1';
    const resultPromise = firstValueFrom(service.cancelBreak(id));

    const req = httpMock.expectOne(`/api/agent/breaks/${id}`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });

    await expect(resultPromise).resolves.toBeNull();
  });
});
