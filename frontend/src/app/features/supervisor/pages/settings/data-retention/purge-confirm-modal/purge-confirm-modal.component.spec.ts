import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import de from '../../../../../../../../public/i18n/de.json';
import en from '../../../../../../../../public/i18n/en.json';
import pl from '../../../../../../../../public/i18n/pl.json';
import uk from '../../../../../../../../public/i18n/uk.json';
import { PurgeConfirmModalComponent } from './purge-confirm-modal.component';

// jsdom does not implement HTMLDialogElement.showModal/close.
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

describe('PurgeConfirmModalComponent - per-language confirmation phrase', () => {
  let fixture: ComponentFixture<PurgeConfirmModalComponent>;
  let component: PurgeConfirmModalComponent;
  let transloco: TranslocoService;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [
        PurgeConfirmModalComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl, en, de, uk } as never,
          translocoConfig: { availableLangs: ['pl', 'en', 'de', 'uk'], defaultLang: 'pl' },
        }),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PurgeConfirmModalComponent);
    fixture.componentRef.setInput('target', {
      category: 'TRANSCRIPTS',
      categoryLabel: 'Transkrypcje',
      eligibleRowCount: 3,
      oldestEligiblePeriod: null,
      newestEligiblePeriod: null,
    });
    component = fixture.componentInstance;
    transloco = TestBed.inject(TranslocoService);
    fixture.detectChanges();
  });

  const type = (value: string) => {
    component.confirmText.set(value);
    fixture.detectChanges();
  };

  const confirmButton = () =>
    fixture.nativeElement.querySelector('.btn-purge') as HTMLButtonElement;

  it.each([
    ['pl', 'USUŃ'],
    ['en', 'DELETE'],
    ['de', 'LÖSCHEN'],
    ['uk', 'ВИДАЛИТИ'],
  ])('expects the %s phrase %s and renders it in the label', (lang, phrase) => {
    transloco.setActiveLang(lang);
    fixture.detectChanges();
    expect(component.confirmPhrase()).toBe(phrase);
    expect(fixture.nativeElement.querySelector('strong').textContent).toBe(phrase);
    type(phrase);
    expect(component.isConfirmEnabled()).toBe(true);
    expect(confirmButton().disabled).toBe(false);
  });

  it('DE: LÖSCHEN unlocks the button, the Polish USUŃ does not', () => {
    transloco.setActiveLang('de');
    fixture.detectChanges();
    type('USUŃ');
    expect(confirmButton().disabled).toBe(true);
    type('LÖSCHEN');
    expect(confirmButton().disabled).toBe(false);
  });

  it('compares case-insensitively and ignores surrounding whitespace', () => {
    transloco.setActiveLang('de');
    fixture.detectChanges();
    type('  löschen ');
    expect(component.isConfirmEnabled()).toBe(true);
  });

  it('shows the active-language phrase in the mismatch hint', () => {
    transloco.setActiveLang('en');
    fixture.detectChanges();
    type('nope');
    const hint = fixture.nativeElement.querySelector('.purge-dialog__confirm-hint');
    expect(hint.textContent).toContain('DELETE');
    expect(hint.textContent).not.toContain('USUŃ');
  });

  it('does not emit confirmed while the phrase does not match', () => {
    const spy = vi.fn();
    component.confirmed.subscribe(spy);
    type('x');
    component.onConfirm();
    expect(spy).not.toHaveBeenCalled();
    type('USUŃ');
    component.onConfirm();
    expect(spy).toHaveBeenCalledTimes(1);
  });
});
