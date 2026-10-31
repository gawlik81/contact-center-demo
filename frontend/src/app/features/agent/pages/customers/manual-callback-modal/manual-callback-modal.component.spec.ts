import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import pl from '../../../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../../../core/services/notification.service';
import { ManualCallbackService } from '../../../services/manual-callback.service';
import { ManualCallbackModalComponent } from './manual-callback-modal.component';

// jsdom does not implement HTMLDialogElement.showModal/close.
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});

describe('ManualCallbackModalComponent draft protection', () => {
  let fixture: ComponentFixture<ManualCallbackModalComponent>;
  const cancelled = vi.fn();

  const el = (selector: string) =>
    fixture.nativeElement.querySelector(selector) as HTMLElement | null;
  const mainDialog = () => el('dialog.callback-dialog') as HTMLDialogElement;
  const pressEsc = () => mainDialog().dispatchEvent(new Event('cancel', { cancelable: true }));
  const type = (value: string) => {
    const notes = el('#cb-notes') as HTMLTextAreaElement;
    notes.value = value;
    notes.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: ManualCallbackService, useValue: {} },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn() } },
      ],
    });
    fixture = TestBed.createComponent(ManualCallbackModalComponent);
    fixture.componentRef.setInput('customer', {
      customerId: 'cu-1',
      firstName: 'Jan',
      phone: ['+48123456789'],
      email: [],
    });
    fixture.componentInstance.cancelled.subscribe(cancelled);
    fixture.detectChanges();
  });

  it('closes at once when nothing was edited', () => {
    pressEsc();
    fixture.detectChanges();

    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(mainDialog().hasAttribute('open')).toBe(false);
    expect(el('app-confirm-dialog')).toBeNull();
  });

  it('asks for confirmation when closing with unsaved edits and keeps the form on "back"', () => {
    type('Klient prosi o kontakt po 16:00');

    pressEsc();
    fixture.detectChanges();

    expect(el('app-confirm-dialog')?.textContent).toContain(
      pl.agent.manualCallbackModal.discardMessage,
    );
    expect(cancelled).not.toHaveBeenCalled();
    expect(mainDialog().hasAttribute('open')).toBe(true);

    const keep = el('app-confirm-dialog .btn-secondary') as HTMLButtonElement;
    expect(keep.textContent?.trim()).toBe(pl.agent.manualCallbackModal.discardKeep);
    keep.click();
    fixture.detectChanges();

    expect(el('app-confirm-dialog')).toBeNull();
    expect(cancelled).not.toHaveBeenCalled();
    expect((el('#cb-notes') as HTMLTextAreaElement).value).toBe('Klient prosi o kontakt po 16:00');
  });

  it('Esc inside the confirmation returns to the form without closing or losing data', () => {
    type('szkic');
    pressEsc();
    fixture.detectChanges();

    const confirmDialog = el('app-confirm-dialog dialog') as HTMLDialogElement;
    confirmDialog.dispatchEvent(new Event('cancel', { cancelable: true }));
    fixture.detectChanges();

    expect(el('app-confirm-dialog')).toBeNull();
    expect(cancelled).not.toHaveBeenCalled();
    expect(mainDialog().hasAttribute('open')).toBe(true);
    expect((el('#cb-notes') as HTMLTextAreaElement).value).toBe('szkic');
  });

  it('discards the draft and closes after confirming', () => {
    type('szkic');
    (el('.btn-cancel') as HTMLButtonElement).click();
    fixture.detectChanges();

    (el('app-confirm-dialog .btn-danger') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(mainDialog().hasAttribute('open')).toBe(false);
  });
});
