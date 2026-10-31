import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ConfirmDialogComponent } from './confirm-dialog.component';

// jsdom does not implement HTMLDialogElement.showModal/close.
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});

describe('ConfirmDialogComponent', () => {
  let fixture: ComponentFixture<ConfirmDialogComponent>;
  const confirmed = vi.fn();
  const cancelled = vi.fn();

  const dialog = () => fixture.nativeElement.querySelector('dialog') as HTMLDialogElement;
  const button = (selector: string) =>
    fixture.nativeElement.querySelector(selector) as HTMLButtonElement;

  beforeEach(() => {
    vi.clearAllMocks();
    fixture = TestBed.createComponent(ConfirmDialogComponent);
    fixture.componentRef.setInput('message', 'Na pewno?');
    fixture.componentInstance.confirmed.subscribe(confirmed);
    fixture.componentInstance.cancelled.subscribe(cancelled);
    fixture.detectChanges();
  });

  it('opens modally on init', () => {
    expect(dialog().hasAttribute('open')).toBe(true);
  });

  it('emits cancelled exactly once on native Esc (cancel) and closes the dialog', () => {
    const event = new Event('cancel', { cancelable: true });
    dialog().dispatchEvent(event);

    expect(event.defaultPrevented).toBe(true);
    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(confirmed).not.toHaveBeenCalled();
    expect(dialog().hasAttribute('open')).toBe(false);
  });

  it('emits cancelled when the cancel button is clicked', () => {
    button('.btn-secondary').click();

    expect(cancelled).toHaveBeenCalledTimes(1);
    expect(confirmed).not.toHaveBeenCalled();
    expect(dialog().hasAttribute('open')).toBe(false);
  });

  it('emits cancelled on a backdrop click but not on a click inside the content', () => {
    fixture.nativeElement.querySelector('.confirm-dialog__content').click();
    expect(cancelled).not.toHaveBeenCalled();

    dialog().click();
    expect(cancelled).toHaveBeenCalledTimes(1);
  });

  it('emits confirmed once when the confirm button is clicked', () => {
    button('.btn-primary').click();

    expect(confirmed).toHaveBeenCalledTimes(1);
    expect(cancelled).not.toHaveBeenCalled();
    expect(dialog().hasAttribute('open')).toBe(false);
  });
});
