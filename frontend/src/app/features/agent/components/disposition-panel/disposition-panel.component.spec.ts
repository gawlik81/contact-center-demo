import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, of, throwError } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { NotificationService } from '../../../../core/services/notification.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { CustomDispositionService } from '../../../dispositions/services/custom-disposition.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { ContactService } from '../../services/contact.service';
import { DispositionPanelComponent } from './disposition-panel.component';

// jsdom does not implement HTMLDialogElement.showModal/close.
beforeAll(() => {
  HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
});

describe('DispositionPanelComponent save', () => {
  const success = vi.fn();
  const error = vi.fn();
  const setDisposition = vi.fn();
  const changeStatus = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    setDisposition.mockReturnValue(of(undefined));
    changeStatus.mockReturnValue(of(undefined));
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: NotificationService, useValue: { success, error, warning: vi.fn() } },
        {
          provide: ContactService,
          useValue: { getContact: () => of({ recordingUrl: null }), setDisposition },
        },
        {
          provide: CustomDispositionService,
          useValue: { getAvailableDispositions: () => of([]) },
        },
        { provide: AgentStatusService, useValue: { changeStatus } },
        { provide: WebSocketService, useValue: { events$: new Subject() } },
      ],
    });
  });

  it('shows exactly one success toast and emits saved after the status change', () => {
    const fixture = TestBed.createComponent(DispositionPanelComponent);
    fixture.componentRef.setInput('contactId', 'c-1');
    fixture.detectChanges();
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    const panel = fixture.componentInstance as unknown as {
      selectedCode: { set(v: string): void };
      save(): void;
    };
    panel.selectedCode.set('SOLD');

    panel.save();

    expect(setDisposition).toHaveBeenCalledTimes(1);
    expect(changeStatus).toHaveBeenCalledWith('AVAILABLE');
    expect(success).toHaveBeenCalledTimes(1);
    expect(saved).toHaveBeenCalledTimes(1);
  });

  it('shows a separate error toast, still emits saved, when the status change fails', () => {
    changeStatus.mockReturnValue(throwError(() => new Error('boom')));
    const fixture = TestBed.createComponent(DispositionPanelComponent);
    fixture.componentRef.setInput('contactId', 'c-1');
    fixture.detectChanges();
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    const panel = fixture.componentInstance as unknown as {
      selectedCode: { set(v: string): void };
      save(): void;
    };
    panel.selectedCode.set('SOLD');

    panel.save();

    expect(success).toHaveBeenCalledTimes(1);
    expect(error).toHaveBeenCalledTimes(1);
    expect(saved).toHaveBeenCalledTimes(1);
  });
});
