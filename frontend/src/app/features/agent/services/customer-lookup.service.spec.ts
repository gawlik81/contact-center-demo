import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import { CustomerLookupService } from './customer-lookup.service';
import { NotificationService } from '../../../core/services/notification.service';
import { CustomerProfile } from '../../../core/models/customer-profile.model';

const MOCK_PROFILE: CustomerProfile = {
  id: 'abc-123',
  firstName: 'Jan',
  lastName: 'Kowalski',
  phones: ['+48123456789'],
  emails: ['jan@example.com'],
  recentContacts: [
    {
      id: 'c1',
      channel: 'PHONE',
      date: '2026-03-01T10:00:00Z',
      disposition: 'Resolved',
      agentName: 'Anna Nowak',
    },
  ],
};

describe('CustomerLookupService', () => {
  let service: CustomerLookupService;
  let httpMock: HttpTestingController;
  let notifySpy: { error: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    notifySpy = { error: vi.fn() };

    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl: {} },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        CustomerLookupService,
        { provide: NotificationService, useValue: notifySpy },
      ],
    });

    service = TestBed.inject(CustomerLookupService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  // ── Basic lookup ──────────────────────────────────────

  it('should return CustomerProfile for a known phone number', async () => {
    const resultPromise = firstValueFrom(service.lookupByPhone('+48123456789'));

    httpMock
      .expectOne(
        (r) => r.url === '/api/customers/lookup' && r.params.get('phone') === '+48123456789',
      )
      .flush(MOCK_PROFILE);

    const result = await resultPromise;
    expect(result).toEqual(MOCK_PROFILE);
  });

  it('should return null for a 404 response (unknown number)', async () => {
    const resultPromise = firstValueFrom(service.lookupByPhone('+48000000000'));

    httpMock
      .expectOne((r) => r.params.get('phone') === '+48000000000')
      .flush({ message: 'Not found' }, { status: 404, statusText: 'Not Found' });

    const result = await resultPromise;
    expect(result).toBeNull();
  });

  // ── Cache ─────────────────────────────────────────────

  it('should return cached result without issuing a second HTTP request', async () => {
    // First call – populates cache
    const first = firstValueFrom(service.lookupByPhone('+48123456789'));
    httpMock.expectOne((r) => r.params.get('phone') === '+48123456789').flush(MOCK_PROFILE);
    await first;

    // Second call – should hit cache; no new HTTP request
    const second = firstValueFrom(service.lookupByPhone('+48123456789'));
    httpMock.expectNone((r) => r.params.get('phone') === '+48123456789');
    const result = await second;

    expect(result).toEqual(MOCK_PROFILE);
  });

  it('should cache null (unknown) results to avoid repeated 404 requests', async () => {
    const first = firstValueFrom(service.lookupByPhone('+48000000000'));
    httpMock
      .expectOne((r) => r.params.get('phone') === '+48000000000')
      .flush({}, { status: 404, statusText: 'Not Found' });
    await first;

    const second = firstValueFrom(service.lookupByPhone('+48000000000'));
    httpMock.expectNone((r) => r.params.get('phone') === '+48000000000');
    const result = await second;

    expect(result).toBeNull();
  });

  it('should evict a cached entry when evict() is called', async () => {
    // Populate cache
    const first = firstValueFrom(service.lookupByPhone('+48123456789'));
    httpMock.expectOne((r) => r.params.get('phone') === '+48123456789').flush(MOCK_PROFILE);
    await first;

    // Evict
    service.evict('+48123456789');

    // Should trigger a new HTTP request
    const second = firstValueFrom(service.lookupByPhone('+48123456789'));
    httpMock.expectOne((r) => r.params.get('phone') === '+48123456789').flush(MOCK_PROFILE);
    const result = await second;

    expect(result).toEqual(MOCK_PROFILE);
  });

  // ── TTL expiry ────────────────────────────────────────

  it('should bypass cache and re-fetch when cache timestamp is older than 5 minutes', async () => {
    // Manually inject a stale cache entry
    // Access private field via type cast to test TTL boundary
    const svc = service as unknown as {
      cache: Map<string, { data: CustomerProfile; timestamp: number }>;
    };
    svc.cache.set('+48123456789', {
      data: MOCK_PROFILE,
      timestamp: Date.now() - 300_001, // 5 min + 1 ms ago
    });

    const resultPromise = firstValueFrom(service.lookupByPhone('+48123456789'));

    // Stale cache → new HTTP request expected
    httpMock.expectOne((r) => r.params.get('phone') === '+48123456789').flush(MOCK_PROFILE);

    const result = await resultPromise;
    expect(result).toEqual(MOCK_PROFILE);
  });

  // ── Edge cases ────────────────────────────────────────

  it('should return null immediately for an empty CLI without making HTTP request', async () => {
    const result = await firstValueFrom(service.lookupByPhone(''));

    httpMock.expectNone('/api/customers/lookup');
    expect(result).toBeNull();
  });

  it('should trim whitespace from the CLI before lookup', async () => {
    const resultPromise = firstValueFrom(service.lookupByPhone('  +48123456789  '));

    httpMock.expectOne((r) => r.params.get('phone') === '+48123456789').flush(MOCK_PROFILE);

    await resultPromise;
  });

  it('should show an error toast and rethrow for non-404 HTTP errors', async () => {
    // The service calls notifications.error() and re-throws the error via throwError().
    // The caller is responsible for handling the error (e.g. switching to error state).
    const resultPromise = firstValueFrom(service.lookupByPhone('+48999999999'));

    httpMock
      .expectOne((r) => r.params.get('phone') === '+48999999999')
      .flush({}, { status: 500, statusText: 'Internal Server Error' });

    await expect(resultPromise).rejects.toThrow();
    expect(notifySpy.error).toHaveBeenCalledOnce();
  });
});
