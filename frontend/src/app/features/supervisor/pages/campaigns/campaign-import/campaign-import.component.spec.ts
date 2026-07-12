import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { CampaignImportComponent } from './campaign-import.component';
import { CampaignService } from '../../../services/campaign.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { Campaign, ImportJobStartResponse, ImportJobStatus } from '../../../models/campaign.model';

const mockCampaign: Campaign = {
  campaignId: 'camp-1',
  tenantId: 't1',
  name: 'Kampania testowa',
  type: 'OUTBOUND_VOICE',
  dialerType: 'PROGRESSIVE',
  schedule: {},
  status: 'DRAFT',
  dispositionCodes: [],
  maxAttempts: 3,
  retryDelayMinutes: 15,
  ringTimeoutSeconds: 30,
  createdAt: '2026-01-01T08:00:00Z',
};

const mockStartResponse: ImportJobStartResponse = {
  jobId: 'job-1',
  campaignId: 'camp-1',
  status: 'QUEUED',
};

const mockCompletedStatus: ImportJobStatus = {
  jobId: 'job-1',
  campaignId: 'camp-1',
  status: 'COMPLETED',
  totalRows: 2,
  processedRows: 2,
  importedRows: 2,
  rejectedRows: 0,
  rejectedSamples: [],
  startedAt: '2026-01-01T08:00:00Z',
  completedAt: '2026-01-01T08:00:05Z',
};

function createFile(name: string, content: string, type: string): File {
  return new File([content], name, { type });
}

function fileInputEvent(file: File | null): Event {
  return { target: { files: file ? [file] : [] } } as unknown as Event;
}

describe('CampaignImportComponent', () => {
  let fixture: ComponentFixture<CampaignImportComponent>;
  let component: CampaignImportComponent;

  let importContactsSpy: ReturnType<typeof vi.fn>;
  let importContactsJsonSpy: ReturnType<typeof vi.fn>;
  let getImportStatusSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  beforeAll(() => {
    // jsdom's <dialog> element does not implement showModal()/close() – stub them
    // so ngAfterViewInit (which calls dialog.showModal()) does not throw.
    if (!HTMLDialogElement.prototype.showModal) {
      HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
        this.setAttribute('open', '');
      };
    }
    if (!HTMLDialogElement.prototype.close) {
      HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
        this.removeAttribute('open');
      };
    }
  });

  beforeEach(async () => {
    importContactsSpy = vi.fn().mockReturnValue(of(mockStartResponse));
    importContactsJsonSpy = vi.fn().mockReturnValue(of(mockStartResponse));
    getImportStatusSpy = vi.fn().mockReturnValue(of(mockCompletedStatus));
    errorSpy = vi.fn();

    const campaignServiceMock = {
      importContacts: importContactsSpy,
      importContactsJson: importContactsJsonSpy,
      getImportStatus: getImportStatusSpy,
    } as unknown as CampaignService;

    const notificationServiceMock = {
      success: vi.fn(),
      error: errorSpy,
      warning: vi.fn(),
      info: vi.fn(),
    } as unknown as NotificationService;

    await TestBed.configureTestingModule({
      imports: [
        CampaignImportComponent,
        TranslocoTestingModule.forRoot({
          langs: {
            pl: {
              supervisor: {
                campaignImport: {
                  errors: {
                    csvOnly: 'Dozwolone są tylko pliki CSV (.csv) lub JSON (.json).',
                    phoneMissing: 'Pole "Telefon" musi być zmapowane na jedną z kolumn CSV.',
                    startFailed: 'Nie udało się rozpocząć importu. Spróbuj ponownie.',
                  },
                  fieldPhone: 'Telefon (wymagany)',
                  fieldFirstName: 'Imię',
                  fieldLastName: 'Nazwisko',
                  fieldCustom1: 'Pole niestandardowe 1',
                  fieldCustom2: 'Pole niestandardowe 2',
                  fieldIgnore: 'Ignoruj kolumnę',
                },
                customerImport: {
                  sending: 'Wysyłanie...',
                },
              },
              common: { close: 'Zamknij', cancel: 'Anuluj', back: 'Wstecz', status: 'Status' },
            },
          },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: CampaignService, useValue: campaignServiceMock },
        { provide: NotificationService, useValue: notificationServiceMock },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CampaignImportComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('campaign', mockCampaign);
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('selecting a .csv file sets importFormat to csv and keeps the mapping step', () => {
    const file = createFile('contacts.csv', 'phone,firstName\n+48501234567,Jan', 'text/csv');
    component.onFileInputChange(fileInputEvent(file));

    expect(component.importFormat()).toBe('csv');
    expect(component.allSteps()).toContain('mapping');
    expect(component.fileError()).toBeNull();
  });

  it('selecting a .json file sets importFormat to json and skips the mapping step', () => {
    const file = createFile('contacts.json', '[{"phone":"+48501234567"}]', 'application/json');
    component.onFileInputChange(fileInputEvent(file));

    expect(component.importFormat()).toBe('json');
    expect(component.allSteps()).not.toContain('mapping');
    expect(component.fileError()).toBeNull();
  });

  it('rejects a file with an unsupported extension', () => {
    const file = createFile('contacts.txt', 'phone\n+48501234567', 'text/plain');
    component.onFileInputChange(fileInputEvent(file));

    expect(component.fileError()).toBeTruthy();
    expect(component.selectedFile()).toBeNull();
  });

  describe('goToImportOrMapping', () => {
    it('CSV: moves to the mapping step without calling the service', () => {
      component.selectedFile.set(createFile('contacts.csv', 'phone\n+48501234567', 'text/csv'));
      component.importFormat.set('csv');

      component.goToImportOrMapping();

      expect(component.currentStep()).toBe('mapping');
      expect(importContactsJsonSpy).not.toHaveBeenCalled();
    });

    it('JSON: imports immediately and moves to the progress step', () => {
      component.selectedFile.set(
        createFile('contacts.json', '[{"phone":"+48501234567"}]', 'application/json'),
      );
      component.importFormat.set('json');
      component.skipDuplicates.set(false);

      component.goToImportOrMapping();

      expect(importContactsJsonSpy).toHaveBeenCalledWith('camp-1', expect.any(File), false);
      expect(component.currentStep()).toBe('progress');
    });
  });

  it('CSV without a mapped phone column sets mappingError and does not call the service', () => {
    component.selectedFile.set(createFile('contacts.csv', 'notes\nfoo', 'text/csv'));
    component.importFormat.set('csv');
    component.columnMappings.set([{ csvHeader: 'notes', systemField: 'skip' }]);
    component.currentStep.set('mapping');

    component.onImport();

    expect(component.mappingError()).toBeTruthy();
    expect(importContactsSpy).not.toHaveBeenCalled();
    expect(component.currentStep()).toBe('mapping');
  });

  it('shows an error notification and returns to upload when the JSON import fails to start', () => {
    importContactsJsonSpy.mockReturnValue(throwError(() => new Error('network error')));
    component.selectedFile.set(
      createFile('contacts.json', '[{"phone":"+48501234567"}]', 'application/json'),
    );
    component.importFormat.set('json');

    component.goToImportOrMapping();

    expect(errorSpy).toHaveBeenCalled();
    expect(component.currentStep()).toBe('upload');
    expect(component.submitting()).toBe(false);
  });

  it('a successful JSON import completes polling and reaches the report step', async () => {
    vi.useFakeTimers();
    try {
      getImportStatusSpy.mockReturnValue(of(mockCompletedStatus));
      component.selectedFile.set(
        createFile('contacts.json', '[{"phone":"+48501234567"}]', 'application/json'),
      );
      component.importFormat.set('json');

      component.goToImportOrMapping();
      expect(component.currentStep()).toBe('progress');

      await vi.advanceTimersByTimeAsync(3000);

      expect(getImportStatusSpy).toHaveBeenCalledWith('camp-1', 'job-1');
      expect(component.jobStatus()).toEqual(mockCompletedStatus);
      expect(component.currentStep()).toBe('report');
    } finally {
      vi.useRealTimers();
    }
  });
});
