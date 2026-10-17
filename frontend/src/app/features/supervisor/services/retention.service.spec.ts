import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { AuthService } from '../../../core/services/auth.service';
import { RetentionService } from './retention.service';

describe('RetentionService - campaign archive (FE-113)', () => {
  let service: RetentionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            currentTenantId: signal<string | null>('tenant-1'),
            currentRole: signal('ADMIN'),
          },
        },
      ],
    });
    service = TestBed.inject(RetentionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GET campaign-archive uses tenantId from auth context and skips the global error toast', () => {
    let result: unknown;
    service.getCampaignArchiveSetting().subscribe((r) => (result = r));
    const req = httpMock.expectOne((r) =>
      r.url.endsWith('/tenants/tenant-1/retention/campaign-archive'),
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({
      archiveAfterDays: 180,
      isDefault: true,
      defaultDays: 180,
      minDays: 30,
      maxDays: 730,
    });
    expect(result).toEqual(expect.objectContaining({ archiveAfterDays: 180, isDefault: true }));
  });

  it('PUT campaign-archive sends { archiveAfterDays } and skips the global error toast', () => {
    service.updateCampaignArchiveSetting(90).subscribe();
    const req = httpMock.expectOne((r) =>
      r.url.endsWith('/tenants/tenant-1/retention/campaign-archive'),
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ archiveAfterDays: 90 });
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({
      archiveAfterDays: 90,
      isDefault: false,
      defaultDays: 180,
      minDays: 30,
      maxDays: 730,
    });
  });

  it('GET preview passes archiveAfterDays as a query param and skips the global error toast', () => {
    service.previewCampaignArchive(60).subscribe();
    const req = httpMock.expectOne((r) =>
      r.url.endsWith('/tenants/tenant-1/retention/campaign-archive/preview'),
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('archiveAfterDays')).toBe('60');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({});
  });
});
