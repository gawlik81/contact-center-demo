import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AdminMetricsService } from './admin-metrics.service';
import { AuthService } from '../../../core/services/auth.service';
import {
  EtlTableStatus,
  GlobalMetrics,
  GrowthMetrics,
  SystemResourceMetrics,
  TenantMetricsDetail,
  UsageMetrics,
} from '../models/admin-metrics.model';

const mockGlobalMetrics: GlobalMetrics = {
  totalActiveTenants: 3,
  totalAgentsOnline: 12,
  totalActiveContacts: 5,
  systemAlerts: [],
  tenants: [],
  generatedAt: '2026-07-14T10:00:00Z',
};

describe('AdminMetricsService', () => {
  let service: AdminMetricsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { currentRole: signal<string | null>(null) },
        },
      ],
    });
    service = TestBed.inject(AdminMetricsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('getGlobalMetricsSnapshot – one-shot GET /api/admin/metrics', () => {
    service.getGlobalMetricsSnapshot().subscribe((res) => {
      expect(res.totalActiveTenants).toBe(3);
    });

    const req = httpMock.expectOne('/api/admin/metrics');
    expect(req.request.method).toBe('GET');
    req.flush(mockGlobalMetrics);
  });

  it('getTenantMetrics – GET /api/admin/metrics/tenants/:id', () => {
    const detail: TenantMetricsDetail = {
      id: 't1',
      name: 'Tenant 1',
      status: 'ACTIVE',
      agentsOnline: 2,
      agentsTotal: 5,
      activeContacts: 1,
    };
    service.getTenantMetrics('t1').subscribe((res) => {
      expect(res.id).toBe('t1');
      expect(res.activeContacts).toBe(1);
    });

    const req = httpMock.expectOne('/api/admin/metrics/tenants/t1');
    expect(req.request.method).toBe('GET');
    req.flush(detail);
  });

  it('getUsageMetrics – GET /api/admin/metrics/usage', () => {
    const usage: UsageMetrics = {
      contactsHandledToday: 120,
      contactsHandledDeltaPercent: 5,
      avgHandleTimeSeconds: 180,
      avgWaitTimeSeconds: 30,
      fcrPercentage: 72,
      campaignsRunning: 2,
      campaignsTotal: 4,
      generatedAt: '2026-07-14T10:00:00Z',
    };
    service.getUsageMetrics().subscribe((res) => {
      expect(res.contactsHandledToday).toBe(120);
    });

    const req = httpMock.expectOne('/api/admin/metrics/usage');
    expect(req.request.method).toBe('GET');
    req.flush(usage);
  });

  it('getGrowthMetrics – defaults weeks param to 6', () => {
    const growth: GrowthMetrics = {
      weeklyPoints: [],
      topPlugins: [],
      generatedAt: '2026-07-14T10:00:00Z',
    };
    service.getGrowthMetrics().subscribe();

    const req = httpMock.expectOne((r) => r.url.includes('/api/admin/metrics/growth'));
    expect(req.request.params.get('weeks')).toBe('6');
    req.flush(growth);
  });

  it('getGrowthMetrics – sends custom weeks param', () => {
    service.getGrowthMetrics(13).subscribe();

    const req = httpMock.expectOne((r) => r.url.includes('/api/admin/metrics/growth'));
    expect(req.request.params.get('weeks')).toBe('13');
    req.flush({ weeklyPoints: [], topPlugins: [], generatedAt: '' });
  });

  it('getResourceMetrics – GET /api/admin/metrics/resources', () => {
    const resources: SystemResourceMetrics = {
      cpuUsagePercent: 42,
      heapUsedBytes: 100,
      heapMaxBytes: 200,
      threadsLive: 24,
      uptimeSeconds: 3600,
      dbPoolActive: 2,
      dbPoolMax: 10,
      redisStatus: 'UP',
      redisAgentSessions: 5,
      rabbitStatus: 'UP',
      queueDepths: [],
      generatedAt: '2026-07-14T10:00:00Z',
    };
    service.getResourceMetrics().subscribe((res) => {
      expect(res.cpuUsagePercent).toBe(42);
    });

    const req = httpMock.expectOne('/api/admin/metrics/resources');
    expect(req.request.method).toBe('GET');
    req.flush(resources);
  });

  it('exportTenantsCsv – GET /api/admin/metrics/tenants/export as blob', () => {
    service.exportTenantsCsv().subscribe((res) => {
      expect(res instanceof Blob).toBe(true);
    });

    const req = httpMock.expectOne('/api/admin/metrics/tenants/export');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['csv content']));
  });

  it('getEtlStatus – GET /api/admin/etl/status', () => {
    const statuses: EtlTableStatus[] = [
      {
        tableName: 'contact',
        lastSyncedAt: '2026-07-14T09:55:00Z',
        lastRunAt: '2026-07-14T09:55:00Z',
        lastRowCount: 100,
        status: 'DONE',
        lagMinutes: 5,
        errorMessage: null,
      },
    ];
    service.getEtlStatus().subscribe((res) => {
      expect(res.length).toBe(1);
      expect(res[0].tableName).toBe('contact');
    });

    const req = httpMock.expectOne('/api/admin/etl/status');
    expect(req.request.method).toBe('GET');
    req.flush(statuses);
  });
});
