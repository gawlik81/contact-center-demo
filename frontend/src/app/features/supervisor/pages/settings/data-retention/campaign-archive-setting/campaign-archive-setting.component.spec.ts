import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { NEVER, Observable, of, throwError } from 'rxjs';
import de from '../../../../../../../../public/i18n/de.json';
import en from '../../../../../../../../public/i18n/en.json';
import pl from '../../../../../../../../public/i18n/pl.json';
import uk from '../../../../../../../../public/i18n/uk.json';
import { CampaignArchiveSettingComponent } from './campaign-archive-setting.component';
import { RetentionService } from '../../../../services/retention.service';
import { NotificationService } from '../../../../../../core/services/notification.service';
import {
  CampaignArchivePreviewDto,
  CampaignArchiveSettingDto,
} from '../../../../models/retention.model';

// jsdom does not implement HTMLDialogElement.showModal/close.
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});

function setting(days: number, isDefault = false): CampaignArchiveSettingDto {
  return { archiveAfterDays: days, isDefault, defaultDays: 180, minDays: 30, maxDays: 730 };
}

function preview(overrides: Partial<CampaignArchivePreviewDto> = {}): CampaignArchivePreviewDto {
  return {
    currentDays: 180,
    newDays: 90,
    campaignsToArchive: 3,
    contactsToArchive: 41,
    campaignsToArchiveAtCurrentDays: 0,
    contactsToArchiveAtCurrentDays: 0,
    additionalCampaigns: 3,
    additionalContacts: 41,
    shortensPeriod: true,
    ...overrides,
  };
}

describe('CampaignArchiveSettingComponent (FE-113)', () => {
  let fixture: ComponentFixture<CampaignArchiveSettingComponent>;
  let component: CampaignArchiveSettingComponent;
  let service: {
    getCampaignArchiveSetting: ReturnType<typeof vi.fn>;
    updateCampaignArchiveSetting: ReturnType<typeof vi.fn>;
    previewCampaignArchive: ReturnType<typeof vi.fn>;
  };
  let notifications: { success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };

  async function setup(
    initial: Observable<CampaignArchiveSettingDto> = of(setting(180, true)),
  ): Promise<void> {
    service = {
      getCampaignArchiveSetting: vi.fn().mockReturnValue(initial),
      updateCampaignArchiveSetting: vi.fn(),
      previewCampaignArchive: vi.fn(),
    };
    notifications = { success: vi.fn(), error: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [
        CampaignArchiveSettingComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: RetentionService, useValue: service },
        { provide: NotificationService, useValue: notifications },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(CampaignArchiveSettingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function type(value: string): void {
    const input = el().querySelector<HTMLInputElement>('#cas-input')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function saveButton(): HTMLButtonElement {
    return el().querySelector<HTMLButtonElement>('.cas-btn--primary')!;
  }

  it('shows the effective default (180) with the "default" badge', async () => {
    await setup();
    expect(el().querySelector<HTMLInputElement>('#cas-input')!.value).toBe('180');
    expect(el().querySelector('[data-testid="cas-default-badge"]')).not.toBeNull();
    expect(saveButton().disabled).toBe(true);
  });

  it('shows a load error with retry that reloads', async () => {
    await setup(throwError(() => new HttpErrorResponse({ status: 500 })));
    expect(el().querySelector('.cas-alert--error')).not.toBeNull();
    service.getCampaignArchiveSetting.mockReturnValue(of(setting(90)));
    el().querySelector<HTMLButtonElement>('.cas-alert .cas-btn')!.click();
    fixture.detectChanges();
    expect(el().querySelector<HTMLInputElement>('#cas-input')!.value).toBe('90');
    expect(el().querySelector('[data-testid="cas-default-badge"]')).toBeNull();
  });

  it.each(['29', '731', '', '90.5', 'abc', '-30'])(
    'blocks saving for invalid value "%s"',
    async (v) => {
      await setup();
      type(v);
      expect(component.canSave()).toBe(false);
      expect(saveButton().disabled).toBe(true);
      expect(el().querySelector('[data-testid="cas-validation-error"]')).not.toBeNull();
      component.save();
      expect(service.updateCampaignArchiveSetting).not.toHaveBeenCalled();
      expect(service.previewCampaignArchive).not.toHaveBeenCalled();
    },
  );

  it.each([30, 200, 730])(
    'saves boundary/valid lengthening value %i directly without dialog',
    async (n) => {
      await setup(of(setting(30)));
      service.updateCampaignArchiveSetting.mockReturnValue(of(setting(n)));
      type(String(n === 30 ? 31 : n));
      expect(component.canSave()).toBe(true);
      component.save();
      expect(service.previewCampaignArchive).not.toHaveBeenCalled();
      expect(service.updateCampaignArchiveSetting).toHaveBeenCalledWith(n === 30 ? 31 : n);
      expect(notifications.success).toHaveBeenCalledTimes(1);
    },
  );

  it('accepts the lower boundary 30 when shortening (dialog flow)', async () => {
    await setup();
    service.previewCampaignArchive.mockReturnValue(of(preview({ newDays: 30 })));
    type('30');
    expect(component.canSave()).toBe(true);
    component.save();
    expect(service.previewCampaignArchive).toHaveBeenCalledWith(30);
  });

  it('shortening opens a confirmation dialog with preview counts and saves only after confirm', async () => {
    await setup();
    service.previewCampaignArchive.mockReturnValue(of(preview()));
    service.updateCampaignArchiveSetting.mockReturnValue(of(setting(90)));
    type('90');
    component.save();
    fixture.detectChanges();

    expect(service.previewCampaignArchive).toHaveBeenCalledWith(90);
    expect(service.updateCampaignArchiveSetting).not.toHaveBeenCalled();
    const dialog = el().querySelector<HTMLDialogElement>('dialog.cas-dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    const result = el().querySelector('[data-testid="cas-preview-result"]')!.textContent!;
    expect(result).toContain('3 kampanii');
    expect(result).toContain('41 kontaktów');

    el().querySelector<HTMLButtonElement>('[data-testid="cas-confirm-button"]')!.click();
    fixture.detectChanges();
    expect(service.updateCampaignArchiveSetting).toHaveBeenCalledWith(90);
    expect(el().querySelector('dialog')).toBeNull();
    expect(notifications.success).toHaveBeenCalledTimes(1);
    expect(el().querySelector<HTMLInputElement>('#cas-input')!.value).toBe('90');
  });

  it('shows "nothing will be archived" for 0/0 preview', async () => {
    await setup();
    service.previewCampaignArchive.mockReturnValue(
      of(preview({ campaignsToArchive: 0, contactsToArchive: 0 })),
    );
    type('150');
    component.save();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="cas-preview-nothing"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="cas-preview-result"]')).toBeNull();
  });

  it('shows a loading state while the preview is pending', async () => {
    await setup();
    service.previewCampaignArchive.mockReturnValue(NEVER);
    type('90');
    component.save();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="cas-preview-loading"]')).not.toBeNull();
  });

  it('preview error is visible but does not block confirmation', async () => {
    await setup();
    service.previewCampaignArchive.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    service.updateCampaignArchiveSetting.mockReturnValue(of(setting(90)));
    type('90');
    component.save();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="cas-preview-error"]')).not.toBeNull();
    const confirm = el().querySelector<HTMLButtonElement>('[data-testid="cas-confirm-button"]')!;
    expect(confirm.disabled).toBe(false);
    confirm.click();
    expect(service.updateCampaignArchiveSetting).toHaveBeenCalledWith(90);
  });

  it('cancelling the dialog does not save and keeps the typed value', async () => {
    await setup();
    service.previewCampaignArchive.mockReturnValue(of(preview()));
    type('90');
    component.save();
    fixture.detectChanges();
    component.cancelConfirm();
    fixture.detectChanges();
    expect(el().querySelector('dialog')).toBeNull();
    expect(service.updateCampaignArchiveSetting).not.toHaveBeenCalled();
    expect(el().querySelector<HTMLInputElement>('#cas-input')!.value).toBe('90');
  });

  it('422 from PUT shows one inline error and no toast', async () => {
    await setup(of(setting(100)));
    service.updateCampaignArchiveSetting.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 422 })),
    );
    type('200');
    component.save();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="cas-save-error"]')!.textContent).toContain('30–730');
    expect(notifications.success).not.toHaveBeenCalled();
    expect(notifications.error).not.toHaveBeenCalled();
    expect(component.saving()).toBe(false);
  });

  it('403 from PUT shows the permission message', async () => {
    await setup(of(setting(100)));
    service.updateCampaignArchiveSetting.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 403 })),
    );
    type('200');
    component.save();
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="cas-save-error"]')!.textContent).toContain(
      'Brak uprawnień',
    );
  });

  it('renders optional updatedAt only when present', async () => {
    await setup(of({ ...setting(100), updatedAt: '2026-10-12T10:00:00Z' }));
    expect(el().textContent).toContain('Ostatnia zmiana');
  });
});

describe('FE-113 i18n keys parity', () => {
  const collect = (obj: unknown, prefix = ''): string[] =>
    Object.entries(obj as Record<string, unknown>).flatMap(([k, v]) =>
      v && typeof v === 'object' ? collect(v, `${prefix}${k}.`) : [`${prefix}${k}`],
    );
  const keysOf = (json: unknown) =>
    collect(
      (json as { supervisor: { settings: { dataRetention: { campaignArchive: unknown } } } })
        .supervisor.settings.dataRetention.campaignArchive,
    ).sort();

  it('has identical campaignArchive keys in pl/en/de/uk', () => {
    const plKeys = keysOf(pl);
    expect(plKeys.length).toBeGreaterThan(15);
    expect(keysOf(en)).toEqual(plKeys);
    expect(keysOf(de)).toEqual(plKeys);
    expect(keysOf(uk)).toEqual(plKeys);
  });

  it('label contains "days after campaign end" semantics in every language', () => {
    const label = (j: unknown) =>
      (j as { supervisor: { settings: { dataRetention: { campaignArchive: { label: string } } } } })
        .supervisor.settings.dataRetention.campaignArchive.label;
    expect(label(pl)).toContain('dni od zakończenia kampanii');
    expect(label(en)).toContain('days after campaign end');
    expect(label(de)).toContain('Tage nach Kampagnenende');
    expect(label(uk)).toContain('днів після завершення кампанії');
  });
});
