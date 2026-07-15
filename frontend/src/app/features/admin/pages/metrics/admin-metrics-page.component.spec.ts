import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of, Subject, throwError } from 'rxjs';
import { AdminMetricsPageComponent } from './admin-metrics-page.component';
import { AdminMetricsService } from '../../services/admin-metrics.service';
import { NotificationService } from '../../../../core/services/notification.service';
import {
  ContactChannelMatrix,
  EtlTableStatus,
  GlobalMetrics,
  GrowthMetrics,
  SystemResourceMetrics,
  TenantMetricsSummary,
  UsageMetrics,
} from '../../models/admin-metrics.model';

function buildTenant(overrides: Partial<TenantMetricsSummary> = {}): TenantMetricsSummary {
  return {
    id: 't1',
    name: 'Tenant 1',
    status: 'ACTIVE',
    agentsOnline: 3,
    agentsTotal: 10,
    contactsToday: 42,
    avgHandleTimeSeconds: 200,
    fcrPercentage: 75,
    agentLimitUtilizationPercent: 40,
    ...overrides,
  };
}

const mockOverview: GlobalMetrics = {
  totalActiveTenants: 2,
  totalAgentsOnline: 8,
  totalActiveContacts: 5,
  systemAlerts: [],
  tenants: [
    buildTenant({ id: 't1', name: 'Alpha', contactsToday: 10, fcrPercentage: 80 }),
    buildTenant({ id: 't2', name: 'Beta', contactsToday: 50, fcrPercentage: 40 }),
  ],
  generatedAt: '2026-07-14T10:00:00Z',
};

const mockUsage: UsageMetrics = {
  contactsHandledToday: 120,
  contactsHandledDeltaPercent: 5,
  avgHandleTimeSeconds: 185,
  avgWaitTimeSeconds: 25,
  fcrPercentage: 68,
  campaignsRunning: 2,
  campaignsTotal: 5,
  generatedAt: '2026-07-14T10:00:00Z',
};

const mockGrowth: GrowthMetrics = {
  weeklyPoints: [
    { weekStart: '2026-06-15', newTenants: 2, newUsers: 10 },
    { weekStart: '2026-06-22', newTenants: 4, newUsers: 20 },
  ],
  topPlugins: [
    { pluginName: 'sms-gateway', installCount: 12 },
    { pluginName: 'crm-sync', installCount: 6 },
  ],
  generatedAt: '2026-07-14T10:00:00Z',
};

const mockResources: SystemResourceMetrics = {
  cpuUsagePercent: 45,
  heapUsedBytes: 512 * 1024 * 1024,
  heapMaxBytes: 1024 * 1024 * 1024,
  threadsLive: 32,
  uptimeSeconds: 90000,
  dbPoolActive: 3,
  dbPoolMax: 10,
  redisStatus: 'UP',
  redisAgentSessions: 7,
  rabbitStatus: 'UP',
  queueDepths: [
    { queueName: 'contact.events', messageCount: 0 },
    { queueName: 'contact.events.dlq', messageCount: 3 },
  ],
  generatedAt: '2026-07-14T10:00:00Z',
};

const mockChannelMatrix: ContactChannelMatrix = {
  channels: ['PHONE', 'EMAIL', 'SOCIAL_FACEBOOK', 'SOCIAL_INSTAGRAM', 'SOCIAL_WHATSAPP'],
  tenants: [
    {
      tenantId: 't1',
      tenantName: 'Alpha',
      countsByChannel: {
        PHONE: 7,
        EMAIL: 3,
        SOCIAL_FACEBOOK: 0,
        SOCIAL_INSTAGRAM: 0,
        SOCIAL_WHATSAPP: 0,
      },
      total: 10,
    },
    {
      tenantId: 't2',
      tenantName: 'Beta',
      countsByChannel: {
        PHONE: 40,
        EMAIL: 5,
        SOCIAL_FACEBOOK: 4,
        SOCIAL_INSTAGRAM: 1,
        SOCIAL_WHATSAPP: 0,
      },
      total: 50,
    },
  ],
  fromDate: '2026-06-14',
  toDate: '2026-07-14',
  generatedAt: '2026-07-14T10:00:00Z',
};

const mockEtlStatuses: EtlTableStatus[] = [
  {
    tableName: 'contact',
    lastSyncedAt: '2026-07-14T09:58:00Z',
    lastRunAt: '2026-07-14T09:58:00Z',
    lastRowCount: 100,
    status: 'DONE',
    lagMinutes: 2,
    errorMessage: null,
  },
];

describe('AdminMetricsPageComponent', () => {
  let fixture: ComponentFixture<AdminMetricsPageComponent>;
  let component: AdminMetricsPageComponent;

  let getGlobalMetricsSnapshotSpy: ReturnType<typeof vi.fn>;
  let getUsageMetricsSpy: ReturnType<typeof vi.fn>;
  let getContactChannelMatrixSpy: ReturnType<typeof vi.fn>;
  let getGrowthMetricsSpy: ReturnType<typeof vi.fn>;
  let getResourceMetricsSpy: ReturnType<typeof vi.fn>;
  let getEtlStatusSpy: ReturnType<typeof vi.fn>;
  let exportTenantsCsvSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  /**
   * Builds the component against the given service mocks and flushes the
   * initial `timer(0, METRICS_POLL_INTERVAL_MS)` tick shared by every polled
   * section (overview/usage/growth/etl/resources) — mirrors how the
   * pre-existing resources polling test already had to flush its first tick.
   * Fake timers are left ACTIVE on return so callers can advance further
   * (e.g. `await vi.advanceTimersByTimeAsync(30_000)`); the shared afterEach()
   * below restores real timers.
   */
  async function createFixture(
    metricsServiceMock: unknown,
    notificationServiceMock: unknown = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn(),
    },
    langs: Record<string, Record<string, unknown>> = { pl: { admin: { metrics: {} }, common: {} } },
  ): Promise<void> {
    vi.useFakeTimers();

    await TestBed.configureTestingModule({
      imports: [
        AdminMetricsPageComponent,
        TranslocoTestingModule.forRoot({
          langs,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: AdminMetricsService, useValue: metricsServiceMock },
        { provide: NotificationService, useValue: notificationServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminMetricsPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
  }

  async function setup(): Promise<void> {
    getGlobalMetricsSnapshotSpy = vi.fn().mockReturnValue(of(mockOverview));
    getUsageMetricsSpy = vi.fn().mockReturnValue(of(mockUsage));
    getContactChannelMatrixSpy = vi.fn().mockReturnValue(of(mockChannelMatrix));
    getGrowthMetricsSpy = vi.fn().mockReturnValue(of(mockGrowth));
    getResourceMetricsSpy = vi.fn().mockReturnValue(of(mockResources));
    getEtlStatusSpy = vi.fn().mockReturnValue(of(mockEtlStatuses));
    exportTenantsCsvSpy = vi.fn().mockReturnValue(of(new Blob(['csv'])));
    errorSpy = vi.fn();

    const metricsServiceMock = {
      getGlobalMetricsSnapshot: getGlobalMetricsSnapshotSpy,
      getUsageMetrics: getUsageMetricsSpy,
      getContactChannelMatrix: getContactChannelMatrixSpy,
      getGrowthMetrics: getGrowthMetricsSpy,
      getResourceMetrics: getResourceMetricsSpy,
      getEtlStatus: getEtlStatusSpy,
      exportTenantsCsv: exportTenantsCsvSpy,
    } as unknown as AdminMetricsService;

    const notificationServiceMock = {
      success: vi.fn(),
      error: errorSpy,
      warning: vi.fn(),
      info: vi.fn(),
    } as unknown as NotificationService;

    await createFixture(metricsServiceMock, notificationServiceMock, {
      pl: {
        admin: {
          metrics: {
            exportError: 'Nie udalo sie wyeksportowac danych.',
            channelPhone: 'Telefon',
            channelEmail: 'Email',
            channelFacebook: 'Facebook',
            channelInstagram: 'Instagram',
            channelWhatsapp: 'WhatsApp',
          },
        },
        common: { refresh: 'Odśwież' },
      },
    });
  }

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('should create and load every section on init', async () => {
    await setup();

    expect(getGlobalMetricsSnapshotSpy).toHaveBeenCalledTimes(1);
    expect(getUsageMetricsSpy).toHaveBeenCalledTimes(1);
    expect(getContactChannelMatrixSpy).toHaveBeenCalledWith(30); // default range '30' => 30 days
    expect(getGrowthMetricsSpy).toHaveBeenCalledWith(4); // default range '30' => 4 weeks
    expect(getEtlStatusSpy).toHaveBeenCalledTimes(1);
    expect(getResourceMetricsSpy).toHaveBeenCalledTimes(1);

    expect(component.overview()).toEqual(mockOverview);
    expect(component.usage()).toEqual(mockUsage);
    expect(component.channelMatrix()).toEqual(mockChannelMatrix);
    expect(component.growth()).toEqual(mockGrowth);
    expect(component.etlStatuses()).toEqual(mockEtlStatuses);
    expect(component.resources()).toEqual(mockResources);

    expect(component.overviewLoading()).toBe(false);
    expect(component.usageLoading()).toBe(false);
    expect(component.channelMatrixLoading()).toBe(false);
    expect(component.growthLoading()).toBe(false);
    expect(component.etlLoading()).toBe(false);
    expect(component.resourcesLoading()).toBe(false);
  });

  it('sets error flags independently when a section fails', async () => {
    getUsageMetricsSpy = vi.fn().mockReturnValue(throwError(() => new Error('boom')));
    getGlobalMetricsSnapshotSpy = vi.fn().mockReturnValue(of(mockOverview));
    getContactChannelMatrixSpy = vi.fn().mockReturnValue(of(mockChannelMatrix));
    getGrowthMetricsSpy = vi.fn().mockReturnValue(of(mockGrowth));
    getResourceMetricsSpy = vi.fn().mockReturnValue(of(mockResources));
    getEtlStatusSpy = vi.fn().mockReturnValue(of(mockEtlStatuses));

    const metricsServiceMock = {
      getGlobalMetricsSnapshot: getGlobalMetricsSnapshotSpy,
      getUsageMetrics: getUsageMetricsSpy,
      getContactChannelMatrix: getContactChannelMatrixSpy,
      getGrowthMetrics: getGrowthMetricsSpy,
      getResourceMetrics: getResourceMetricsSpy,
      getEtlStatus: getEtlStatusSpy,
      exportTenantsCsv: vi.fn(),
    } as unknown as AdminMetricsService;

    await createFixture(metricsServiceMock);

    expect(component.usageError()).toBe(true);
    expect(component.usageLoading()).toBe(false);
    expect(component.overviewError()).toBe(false);
    expect(component.overview()).toEqual(mockOverview);
  });

  describe('auto-polling (every 30s)', () => {
    it('overview polls again after 30 seconds', async () => {
      await setup();
      getGlobalMetricsSnapshotSpy.mockClear();

      await vi.advanceTimersByTimeAsync(30_000);
      expect(getGlobalMetricsSnapshotSpy).toHaveBeenCalledTimes(1);
    });

    it('usage polls again after 30 seconds', async () => {
      await setup();
      getUsageMetricsSpy.mockClear();

      await vi.advanceTimersByTimeAsync(30_000);
      expect(getUsageMetricsSpy).toHaveBeenCalledTimes(1);
    });

    it('channel matrix polls again after 30 seconds using the current range', async () => {
      await setup();
      component.onRangeChange('7');
      getContactChannelMatrixSpy.mockClear();

      await vi.advanceTimersByTimeAsync(30_000);
      expect(getContactChannelMatrixSpy).toHaveBeenCalledWith(7); // '7' => 7 days, not the stale default
    });

    it('growth polls again after 30 seconds using the current range', async () => {
      await setup();
      component.onRangeChange('7');
      getGrowthMetricsSpy.mockClear();

      await vi.advanceTimersByTimeAsync(30_000);
      expect(getGrowthMetricsSpy).toHaveBeenCalledWith(1); // '7' => 1 week, not the stale default
    });

    it('etl polls again after 30 seconds', async () => {
      await setup();
      getEtlStatusSpy.mockClear();

      await vi.advanceTimersByTimeAsync(30_000);
      expect(getEtlStatusSpy).toHaveBeenCalledTimes(1);
    });

    it('resources poll again after 30 seconds while the page is active', async () => {
      await setup();
      getResourceMetricsSpy.mockClear();

      await vi.advanceTimersByTimeAsync(30_000);
      expect(getResourceMetricsSpy).toHaveBeenCalledTimes(1);
    });

    it('does not flip loading flags back to true on subsequent ticks (no skeleton flicker)', async () => {
      await setup();
      expect(component.overviewLoading()).toBe(false);
      expect(component.usageLoading()).toBe(false);
      expect(component.channelMatrixLoading()).toBe(false);
      expect(component.growthLoading()).toBe(false);
      expect(component.etlLoading()).toBe(false);
      expect(component.resourcesLoading()).toBe(false);

      await vi.advanceTimersByTimeAsync(30_000);

      expect(component.overviewLoading()).toBe(false);
      expect(component.usageLoading()).toBe(false);
      expect(component.channelMatrixLoading()).toBe(false);
      expect(component.growthLoading()).toBe(false);
      expect(component.etlLoading()).toBe(false);
      expect(component.resourcesLoading()).toBe(false);
    });
  });

  it('onRefresh triggers an immediate tick for overview/usage/channelMatrix/growth/etl (not resources)', async () => {
    await setup();
    getGlobalMetricsSnapshotSpy.mockClear();
    getUsageMetricsSpy.mockClear();
    getContactChannelMatrixSpy.mockClear();
    getGrowthMetricsSpy.mockClear();
    getEtlStatusSpy.mockClear();
    getResourceMetricsSpy.mockClear();

    component.onRefresh();

    expect(getGlobalMetricsSnapshotSpy).toHaveBeenCalledTimes(1);
    expect(getUsageMetricsSpy).toHaveBeenCalledTimes(1);
    expect(getContactChannelMatrixSpy).toHaveBeenCalledTimes(1);
    expect(getGrowthMetricsSpy).toHaveBeenCalledTimes(1);
    expect(getEtlStatusSpy).toHaveBeenCalledTimes(1);
    expect(getResourceMetricsSpy).not.toHaveBeenCalled();
  });

  it('onRefresh is a no-op while a section is still loading', async () => {
    const pendingUsage$ = new Subject<UsageMetrics>();
    getGlobalMetricsSnapshotSpy = vi.fn().mockReturnValue(of(mockOverview));
    getUsageMetricsSpy = vi.fn().mockReturnValue(pendingUsage$.asObservable());
    getContactChannelMatrixSpy = vi.fn().mockReturnValue(of(mockChannelMatrix));
    getGrowthMetricsSpy = vi.fn().mockReturnValue(of(mockGrowth));
    getResourceMetricsSpy = vi.fn().mockReturnValue(of(mockResources));
    getEtlStatusSpy = vi.fn().mockReturnValue(of(mockEtlStatuses));

    const metricsServiceMock = {
      getGlobalMetricsSnapshot: getGlobalMetricsSnapshotSpy,
      getUsageMetrics: getUsageMetricsSpy,
      getContactChannelMatrix: getContactChannelMatrixSpy,
      getGrowthMetrics: getGrowthMetricsSpy,
      getResourceMetrics: getResourceMetricsSpy,
      getEtlStatus: getEtlStatusSpy,
      exportTenantsCsv: vi.fn(),
    } as unknown as AdminMetricsService;

    await createFixture(metricsServiceMock);

    // usage$ never emitted, so usageLoading() is still true — isRefreshing() should be true too.
    expect(component.usageLoading()).toBe(true);
    expect(component.isRefreshing()).toBe(true);

    getGlobalMetricsSnapshotSpy.mockClear();
    component.onRefresh();
    expect(getGlobalMetricsSnapshotSpy).not.toHaveBeenCalled();
  });

  it('onRangeChange re-fetches growth immediately with the mapped weeks value', async () => {
    await setup();
    getGrowthMetricsSpy.mockClear();

    component.onRangeChange('7');
    expect(getGrowthMetricsSpy).toHaveBeenCalledWith(1);

    getGrowthMetricsSpy.mockClear();
    component.onRangeChange('90');
    expect(getGrowthMetricsSpy).toHaveBeenCalledWith(13);
  });

  it('onRangeChange re-fetches the channel matrix immediately with the raw days value', async () => {
    await setup();
    getContactChannelMatrixSpy.mockClear();

    component.onRangeChange('7');
    expect(getContactChannelMatrixSpy).toHaveBeenCalledWith(7);

    getContactChannelMatrixSpy.mockClear();
    component.onRangeChange('90');
    expect(getContactChannelMatrixSpy).toHaveBeenCalledWith(90);
  });

  it('onRangeChange refreshes both growth and the channel matrix together, not just growth', async () => {
    await setup();
    getGrowthMetricsSpy.mockClear();
    getContactChannelMatrixSpy.mockClear();

    component.onRangeChange('7');

    expect(getGrowthMetricsSpy).toHaveBeenCalledWith(1);
    expect(getContactChannelMatrixSpy).toHaveBeenCalledWith(7);
  });

  it('onRangeChange does nothing when the range is unchanged', async () => {
    await setup();
    getGrowthMetricsSpy.mockClear();
    getContactChannelMatrixSpy.mockClear();

    component.onRangeChange('30');
    expect(getGrowthMetricsSpy).not.toHaveBeenCalled();
    expect(getContactChannelMatrixSpy).not.toHaveBeenCalled();
  });

  it('changing the range mid-poll does not race the timer tick (switchMap cancels the stale request)', async () => {
    await setup();
    getGrowthMetricsSpy.mockClear();

    component.onRangeChange('7');
    expect(getGrowthMetricsSpy).toHaveBeenCalledTimes(1);
    expect(getGrowthMetricsSpy).toHaveBeenLastCalledWith(1);
    expect(component.growth()).toEqual(mockGrowth);
  });

  it('onSort toggles direction on repeated clicks and sorts the tenant list', async () => {
    await setup();

    component.onSort('contactsToday');
    expect(component.sortField()).toBe('contactsToday');
    expect(component.sortDir()).toBe('desc');
    expect(component.sortedTenants().map((t) => t.id)).toEqual(['t2', 't1']); // 50 desc before 10

    component.onSort('contactsToday');
    expect(component.sortDir()).toBe('asc');
    expect(component.sortedTenants().map((t) => t.id)).toEqual(['t1', 't2']);
  });

  it('onSort resets direction to desc when switching to a different field', async () => {
    await setup();
    component.sortField.set('contactsToday');
    component.sortDir.set('asc');

    component.onSort('fcrPercentage');
    expect(component.sortField()).toBe('fcrPercentage');
    expect(component.sortDir()).toBe('desc');
  });

  it('onExportCsv downloads the CSV blob returned by the service', async () => {
    await setup();
    const clickSpy = vi.fn();
    const anchor = { click: clickSpy, href: '', download: '' } as unknown as HTMLAnchorElement;
    vi.spyOn(document, 'createElement').mockReturnValue(anchor);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock');
    vi.spyOn(URL, 'revokeObjectURL').mockReturnValue(undefined);

    component.onExportCsv();

    expect(exportTenantsCsvSpy).toHaveBeenCalled();
    expect(clickSpy).toHaveBeenCalled();
    expect(anchor.download).toMatch(/^tenants-metrics-\d{4}-\d{2}-\d{2}\.csv$/);
    expect(component.exportingCsv()).toBe(false);
  });

  it('onExportCsv shows an error notification when the export fails', async () => {
    await setup();
    exportTenantsCsvSpy.mockReturnValue(throwError(() => new Error('network error')));

    component.onExportCsv();

    expect(errorSpy).toHaveBeenCalled();
    expect(component.exportingCsv()).toBe(false);
  });

  describe('presentation helpers', () => {
    beforeEach(async () => {
      await setup();
    });

    it('fcrTier – classifies good/warn/bad', () => {
      expect(component.fcrTier(75)).toBe('good');
      expect(component.fcrTier(60)).toBe('warn');
      expect(component.fcrTier(30)).toBe('bad');
    });

    it('utilizationTier – classifies low/mid/high', () => {
      expect(component.utilizationTier(30)).toBe('low');
      expect(component.utilizationTier(60)).toBe('mid');
      expect(component.utilizationTier(90)).toBe('high');
    });

    it('resourceTier – uses 60/85 thresholds distinct from utilizationTier', () => {
      expect(component.resourceTier(59)).toBe('low');
      expect(component.resourceTier(70)).toBe('mid');
      expect(component.resourceTier(90)).toBe('high');
    });

    it('isDlqWarning – true only for DLQ-like queues with pending messages', () => {
      expect(component.isDlqWarning({ queueName: 'contact.events.dlq', messageCount: 3 })).toBe(
        true,
      );
      expect(component.isDlqWarning({ queueName: 'contact.events.dlq', messageCount: 0 })).toBe(
        false,
      );
      expect(component.isDlqWarning({ queueName: 'contact.events', messageCount: 5 })).toBe(false);
    });

    it('etlVisualStatus – DONE beyond lag threshold becomes a warning', () => {
      expect(
        component.etlVisualStatus({
          tableName: 'contact',
          lastSyncedAt: null,
          lastRunAt: null,
          lastRowCount: 0,
          status: 'DONE',
          lagMinutes: 45,
          errorMessage: null,
        }),
      ).toBe('warning');

      expect(
        component.etlVisualStatus({
          tableName: 'contact',
          lastSyncedAt: null,
          lastRunAt: null,
          lastRowCount: 0,
          status: 'DONE',
          lagMinutes: 5,
          errorMessage: null,
        }),
      ).toBe('done');

      expect(
        component.etlVisualStatus({
          tableName: 'contact',
          lastSyncedAt: null,
          lastRunAt: null,
          lastRowCount: 0,
          status: 'ERROR',
          lagMinutes: null,
          errorMessage: 'boom',
        }),
      ).toBe('error');
    });

    it('formatDuration – formats seconds as m:ss, dash for null/undefined', () => {
      expect(component.formatDuration(185)).toBe('3:05');
      expect(component.formatDuration(null)).toBe('—');
      expect(component.formatDuration(undefined)).toBe('—');
    });

    it('formatBytes – renders MB below 1024, GB above', () => {
      expect(component.formatBytes(512 * 1024 * 1024)).toBe('512 MB');
      expect(component.formatBytes(2 * 1024 * 1024 * 1024)).toBe('2.0 GB');
    });

    it('formatRelativePast – returns dash for null input', () => {
      expect(component.formatRelativePast(null)).toBe('—');
    });

    it('channelLabel – translates known backend channel keys', () => {
      expect(component.channelLabel('PHONE')).toBe('Telefon');
      expect(component.channelLabel('EMAIL')).toBe('Email');
      expect(component.channelLabel('SOCIAL_FACEBOOK')).toBe('Facebook');
      expect(component.channelLabel('SOCIAL_INSTAGRAM')).toBe('Instagram');
      expect(component.channelLabel('SOCIAL_WHATSAPP')).toBe('WhatsApp');
    });

    it('channelLabel – falls back to the raw key for an unknown channel', () => {
      expect(component.channelLabel('SMS')).toBe('SMS');
    });

    it('countFor – reads the count for a channel present in the row, 0 when absent', () => {
      const row = mockChannelMatrix.tenants[1]; // Beta: PHONE 40, SOCIAL_WHATSAPP 0
      expect(component.countFor(row, 'PHONE')).toBe(40);
      expect(component.countFor(row, 'SOCIAL_WHATSAPP')).toBe(0);
      expect(component.countFor(row, 'UNKNOWN_CHANNEL')).toBe(0);
    });
  });

  describe('channel matrix computed signals', () => {
    it('hasChannelMatrixTenants / channelMatrixChannels / channelMatrixTenants reflect loaded data', async () => {
      await setup();

      expect(component.hasChannelMatrixTenants()).toBe(true);
      expect(component.channelMatrixChannels()).toEqual(mockChannelMatrix.channels);
      expect(component.channelMatrixTenants()).toEqual(mockChannelMatrix.tenants);
    });

    it('hasChannelMatrixTenants is false when the matrix has no tenants', async () => {
      getGlobalMetricsSnapshotSpy = vi.fn().mockReturnValue(of(mockOverview));
      getUsageMetricsSpy = vi.fn().mockReturnValue(of(mockUsage));
      getContactChannelMatrixSpy = vi
        .fn()
        .mockReturnValue(of({ ...mockChannelMatrix, tenants: [] }));
      getGrowthMetricsSpy = vi.fn().mockReturnValue(of(mockGrowth));
      getResourceMetricsSpy = vi.fn().mockReturnValue(of(mockResources));
      getEtlStatusSpy = vi.fn().mockReturnValue(of(mockEtlStatuses));

      const metricsServiceMock = {
        getGlobalMetricsSnapshot: getGlobalMetricsSnapshotSpy,
        getUsageMetrics: getUsageMetricsSpy,
        getContactChannelMatrix: getContactChannelMatrixSpy,
        getGrowthMetrics: getGrowthMetricsSpy,
        getResourceMetrics: getResourceMetricsSpy,
        getEtlStatus: getEtlStatusSpy,
        exportTenantsCsv: vi.fn(),
      } as unknown as AdminMetricsService;

      await createFixture(metricsServiceMock);

      expect(component.hasChannelMatrixTenants()).toBe(false);
    });

    it('sets channelMatrixError independently when the endpoint fails', async () => {
      getGlobalMetricsSnapshotSpy = vi.fn().mockReturnValue(of(mockOverview));
      getUsageMetricsSpy = vi.fn().mockReturnValue(of(mockUsage));
      getContactChannelMatrixSpy = vi.fn().mockReturnValue(throwError(() => new Error('boom')));
      getGrowthMetricsSpy = vi.fn().mockReturnValue(of(mockGrowth));
      getResourceMetricsSpy = vi.fn().mockReturnValue(of(mockResources));
      getEtlStatusSpy = vi.fn().mockReturnValue(of(mockEtlStatuses));

      const metricsServiceMock = {
        getGlobalMetricsSnapshot: getGlobalMetricsSnapshotSpy,
        getUsageMetrics: getUsageMetricsSpy,
        getContactChannelMatrix: getContactChannelMatrixSpy,
        getGrowthMetrics: getGrowthMetricsSpy,
        getResourceMetrics: getResourceMetricsSpy,
        getEtlStatus: getEtlStatusSpy,
        exportTenantsCsv: vi.fn(),
      } as unknown as AdminMetricsService;

      await createFixture(metricsServiceMock);

      expect(component.channelMatrixError()).toBe(true);
      expect(component.channelMatrixLoading()).toBe(false);
      expect(component.usageError()).toBe(false);
      expect(component.usage()).toEqual(mockUsage);
    });
  });
});
