import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';
import { GdprAnonymizeModalComponent } from './gdpr-anonymize-modal.component';
import { GdprService } from '../services/gdpr.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { AnonymizePreviewResponse } from '../gdpr.model';

// jsdom (v28, bundled with this project) does not implement HTMLDialogElement.showModal/close —
// polyfill both so ngAfterViewInit's dialog.showModal() call does not throw during CD.
beforeAll(() => {
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

const mockPreview: AnonymizePreviewResponse = {
  dryRun: true,
  counts: {
    customer: 1,
    contact: 4,
    email_message: 2,
    scheduled_callback: 1,
    unknown_future_table: 9,
  },
  matchedByLink: 5,
  matchedByIdentifier: 0,
  s3ObjectsToDelete: 3,
};

describe('GdprAnonymizeModalComponent', () => {
  let fixture: ComponentFixture<GdprAnonymizeModalComponent>;
  let component: GdprAnonymizeModalComponent;

  let previewAnonymizeSpy: ReturnType<typeof vi.fn>;
  let anonymizeSpy: ReturnType<typeof vi.fn>;
  let successSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(GdprAnonymizeModalComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('customerId', 'c1');
    fixture.componentRef.setInput('customerName', 'Anna Nowak');
    fixture.detectChanges();
  }

  beforeEach(async () => {
    previewAnonymizeSpy = vi.fn().mockReturnValue(of(mockPreview));
    anonymizeSpy = vi.fn().mockReturnValue(of(undefined));
    successSpy = vi.fn();
    errorSpy = vi.fn();

    const gdprServiceMock = {
      previewAnonymize: previewAnonymizeSpy,
      anonymize: anonymizeSpy,
    } as unknown as GdprService;

    const notificationServiceMock = {
      success: successSpy,
      error: errorSpy,
      warning: vi.fn(),
      info: vi.fn(),
    } as unknown as NotificationService;

    await TestBed.configureTestingModule({
      imports: [
        GdprAnonymizeModalComponent,
        TranslocoTestingModule.forRoot({
          langs: {
            pl: {
              supervisor: {
                gdprAnonymize: {
                  confirmPhrase: 'ANONIMIZUJ',
                  successAnonymize: 'Dane klienta zostały zanonimizowane.',
                  errorAnonymize: 'Nie udało się zanonimizować danych klienta. Spróbuj ponownie.',
                },
              },
            },
            de: {
              supervisor: {
                gdprAnonymize: {
                  confirmPhrase: 'ANONYMISIEREN',
                  confirmHint: 'Hint {{ phrase }}',
                },
              },
            },
          },
          translocoConfig: { availableLangs: ['pl', 'de'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: GdprService, useValue: gdprServiceMock },
        { provide: NotificationService, useValue: notificationServiceMock },
      ],
    }).compileComponents();
  });

  it('should create and load the preview on init', async () => {
    await createComponent();
    expect(component).toBeTruthy();
    expect(previewAnonymizeSpy).toHaveBeenCalledWith('c1');
    expect(component.previewState()).toBe('loaded');
    expect(component.preview()).toEqual(mockPreview);
  });

  describe('previewRows', () => {
    it('sorts known keys per GDPR_PREVIEW_COUNT_ORDER and appends unknown keys alphabetically', async () => {
      await createComponent();
      const keys = component.previewRows().map((r) => r.key);
      // contact, email_message, scheduled_callback, customer are known (in that relative order);
      // unknown_future_table is not in GDPR_PREVIEW_COUNT_ORDER, so it must sort last.
      expect(keys).toEqual([
        'contact',
        'email_message',
        'scheduled_callback',
        'customer',
        'unknown_future_table',
      ]);
    });

    it('maps a known key to its i18n label key and an unknown key to the raw key', async () => {
      await createComponent();
      const rows = component.previewRows();
      expect(rows.find((r) => r.key === 'contact')?.labelKey).toBe(
        'supervisor.gdprAnonymize.previewCount.contact',
      );
      expect(rows.find((r) => r.key === 'unknown_future_table')?.labelKey).toBe(
        'unknown_future_table',
      );
    });
  });

  describe('hasIdentifierMatches', () => {
    it('is false when matchedByIdentifier is 0', async () => {
      await createComponent();
      expect(component.hasIdentifierMatches()).toBe(false);
    });

    it('is true when matchedByIdentifier > 0', async () => {
      previewAnonymizeSpy.mockReturnValue(of({ ...mockPreview, matchedByIdentifier: 2 }));
      await createComponent();
      expect(component.hasIdentifierMatches()).toBe(true);
    });
  });

  describe('isConfirmEnabled – gated on preview state (FE-112)', () => {
    it('is false while the preview is still loading, even with the correct phrase typed', async () => {
      const pending$ = new Subject<AnonymizePreviewResponse>();
      previewAnonymizeSpy.mockReturnValue(pending$);
      await createComponent();

      expect(component.previewState()).toBe('loading');
      component.confirmText.set('ANONIMIZUJ');
      expect(component.isConfirmEnabled()).toBe(false);
    });

    it('is false when the preview failed, even with the correct phrase typed', async () => {
      previewAnonymizeSpy.mockReturnValue(throwError(() => new Error('boom')));
      await createComponent();

      expect(component.previewState()).toBe('error');
      component.confirmText.set('ANONIMIZUJ');
      expect(component.isConfirmEnabled()).toBe(false);
    });

    it('is true once the preview has loaded and the exact phrase is typed', async () => {
      await createComponent();
      expect(component.previewState()).toBe('loaded');
      component.confirmText.set('ANONIMIZUJ');
      expect(component.isConfirmEnabled()).toBe(true);
    });

    it('is false when the phrase is wrong, even after the preview has loaded', async () => {
      await createComponent();
      component.confirmText.set('anonimizuje');
      expect(component.isConfirmEnabled()).toBe(false);
    });
  });

  describe('preview timeout', () => {
    it('a hanging preview request (never emits) is treated as an error and blocks confirmation', async () => {
      vi.useFakeTimers();
      try {
        previewAnonymizeSpy.mockReturnValue(new Subject<AnonymizePreviewResponse>());
        await createComponent();
        expect(component.previewState()).toBe('loading');

        await vi.advanceTimersByTimeAsync(15_001);

        expect(component.previewState()).toBe('error');
        component.confirmText.set('ANONIMIZUJ');
        expect(component.isConfirmEnabled()).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('loadPreview – retry after error', () => {
    it('re-fetches and recovers to loaded state', async () => {
      previewAnonymizeSpy.mockReturnValueOnce(throwError(() => new Error('boom')));
      await createComponent();
      expect(component.previewState()).toBe('error');

      previewAnonymizeSpy.mockReturnValue(of(mockPreview));
      component.loadPreview();

      expect(component.previewState()).toBe('loaded');
      expect(component.preview()).toEqual(mockPreview);
    });
  });

  describe('onConfirm', () => {
    it('does nothing when not enabled (preview not loaded yet)', async () => {
      const pending$ = new Subject<AnonymizePreviewResponse>();
      previewAnonymizeSpy.mockReturnValue(pending$);
      await createComponent();

      component.confirmText.set('ANONIMIZUJ');
      component.onConfirm();
      expect(anonymizeSpy).not.toHaveBeenCalled();
    });

    it('calls GdprService#anonymize, shows success toast and emits confirmed', async () => {
      await createComponent();
      component.confirmText.set('ANONIMIZUJ');

      let confirmedEmitted = false;
      component.confirmed.subscribe(() => (confirmedEmitted = true));

      component.onConfirm();

      expect(anonymizeSpy).toHaveBeenCalledWith('c1');
      expect(successSpy).toHaveBeenCalledWith('Dane klienta zostały zanonimizowane.');
      expect(confirmedEmitted).toBe(true);
      expect(component.isLoading()).toBe(false);
    });

    it('shows an error toast and does not emit confirmed on failure', async () => {
      anonymizeSpy.mockReturnValue(throwError(() => new Error('Server error')));
      await createComponent();
      component.confirmText.set('ANONIMIZUJ');

      let confirmedEmitted = false;
      component.confirmed.subscribe(() => (confirmedEmitted = true));

      component.onConfirm();

      expect(errorSpy).toHaveBeenCalledWith(
        'Nie udało się zanonimizować danych klienta. Spróbuj ponownie.',
      );
      expect(confirmedEmitted).toBe(false);
      expect(component.isLoading()).toBe(false);
    });
  });

  describe('onCancel / onEscapeKey', () => {
    it('onCancel emits cancelled when not loading', async () => {
      await createComponent();
      let cancelledEmitted = false;
      component.cancelled.subscribe(() => (cancelledEmitted = true));
      component.onCancel();
      expect(cancelledEmitted).toBe(true);
    });

    it('onCancel does nothing while isLoading is true', async () => {
      await createComponent();
      component.isLoading.set(true);
      let cancelledEmitted = false;
      component.cancelled.subscribe(() => (cancelledEmitted = true));
      component.onCancel();
      expect(cancelledEmitted).toBe(false);
    });

    it('onEscapeKey prevents default and cancels when not loading', async () => {
      await createComponent();
      const event = new KeyboardEvent('keydown', { key: 'Escape' });
      const preventDefaultSpy = vi.spyOn(event, 'preventDefault');
      let cancelledEmitted = false;
      component.cancelled.subscribe(() => (cancelledEmitted = true));

      component.onEscapeKey(event);

      expect(preventDefaultSpy).toHaveBeenCalled();
      expect(cancelledEmitted).toBe(true);
    });

    it('onEscapeKey blocks close while isLoading is true', async () => {
      await createComponent();
      component.isLoading.set(true);
      const event = new KeyboardEvent('keydown', { key: 'Escape' });
      const preventDefaultSpy = vi.spyOn(event, 'preventDefault');
      let cancelledEmitted = false;
      component.cancelled.subscribe(() => (cancelledEmitted = true));

      component.onEscapeKey(event);

      expect(preventDefaultSpy).toHaveBeenCalled();
      expect(cancelledEmitted).toBe(false);
    });
  });

  describe('confirmation phrase per language', () => {
    const setLang = (lang: string) => {
      TestBed.inject(TranslocoService).setActiveLang(lang);
      fixture.detectChanges();
    };

    beforeEach(() => {
      const transloco = TestBed.inject(TranslocoService);
      transloco.setTranslation(
        {
          supervisor: {
            gdprAnonymize: { confirmPhrase: 'ANONYMISIEREN', confirmHint: 'Hint {{ phrase }}' },
          },
        },
        'de',
      );
    });

    it('DE: ANONYMISIEREN enables confirm, the Polish phrase does not', async () => {
      await createComponent();
      setLang('de');
      expect(component.confirmPhrase()).toBe('ANONYMISIEREN');
      component.confirmText.set('ANONIMIZUJ');
      expect(component.isConfirmEnabled()).toBe(false);
      component.confirmText.set('ANONYMISIEREN');
      expect(component.isConfirmEnabled()).toBe(true);
    });

    it('is case-insensitive and trims whitespace', async () => {
      await createComponent();
      setLang('de');
      component.confirmText.set('  anonymisieren ');
      expect(component.isConfirmEnabled()).toBe(true);
    });

    it('renders the active phrase in the label and mismatch hint', async () => {
      await createComponent();
      setLang('de');
      component.confirmText.set('x');
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('.anonymize-dialog__confirm-label strong').textContent,
      ).toBe('ANONYMISIEREN');
      expect(
        fixture.nativeElement.querySelector('.anonymize-dialog__confirm-hint').textContent,
      ).toContain('Hint ANONYMISIEREN');
    });
  });
});
