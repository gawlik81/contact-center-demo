import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { Subject, firstValueFrom } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { WebSocketService } from '../../../../core/services/websocket.service';
import { ContactDraftStore } from '../../services/contact-draft.store';
import { ContactTabStore } from '../../services/contact-tab.store';
import { CallLifecycleService } from '../../services/call-lifecycle.service';
import { SoftphoneService } from '../../services/softphone.service';
import { ContactTab } from '../../models/contact-tab.model';
import { CallSession } from '../../models/call-session.model';
import { SoftphoneComponent } from './softphone.component';

function phoneTab(contactId: string): ContactTab {
  return {
    id: 'tab-1',
    type: 'PHONE',
    contactId,
    customerName: 'Jan',
    customerIdentifier: '+48123456789',
    status: 'ACTIVE',
    isActive: true,
    startedAt: new Date(),
    direction: 'INBOUND',
  };
}

describe('SoftphoneComponent PHONE note (draft store)', () => {
  let fixture: ComponentFixture<SoftphoneComponent>;
  let drafts: ContactDraftStore;
  let tabs: ContactTabStore;

  const textarea = () =>
    fixture.nativeElement.querySelector('.softphone__note-textarea') as HTMLTextAreaElement | null;

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [
        SoftphoneComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        {
          provide: WebSocketService,
          useValue: { events$: new Subject(), connectionState: signal('CONNECTED') },
        },
        { provide: AuthService, useValue: { currentTenantId: signal('t-1') } },
        { provide: NotificationService, useValue: { info: vi.fn(), error: vi.fn() } },
        { provide: HttpClient, useValue: {} },
        { provide: CallLifecycleService, useValue: { rejectRingingCall: vi.fn() } },
      ],
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));

    drafts = TestBed.inject(ContactDraftStore);
    tabs = TestBed.inject(ContactTabStore);
    tabs.reset();
    TestBed.inject(SoftphoneService).session.set({
      contactId: 'sid-leg2',
      customerName: 'Jan',
      customerPhone: '+48123456789',
      queueName: '',
      state: 'ACTIVE',
      startedAt: new Date(),
      duration: 0,
      isMuted: false,
      holdStartedAt: null,
      holdDuration: 0,
      transferTarget: null,
    } satisfies CallSession);

    fixture = TestBed.createComponent(SoftphoneComponent);
    fixture.componentRef.setInput('tab', phoneTab('sid-leg2'));
    await settle();
    (fixture.componentInstance as unknown as { openNotePanel(): void }).openNotePanel();
    await settle();
  });

  it('shows the note stored for the tab contactId and saves typing to the store', async () => {
    drafts.setPhoneNote('sid-leg2', 'z magazynu');
    await settle();
    expect(textarea()?.value).toBe('z magazynu');

    const ta = textarea() as HTMLTextAreaElement;
    ta.value = 'nowa tresc';
    ta.dispatchEvent(new Event('input'));
    expect(drafts.phoneNote('sid-leg2')).toBe('nowa tresc');
  });

  it('follows the note after the tab contactId changes (attended transfer bridge)', async () => {
    drafts.setPhoneNote('sid-leg2', 'notatka');
    await settle();
    expect(textarea()?.value).toBe('notatka');

    // Same sequence as ContactTabStore.updateTabContactId + the desktop passing the new tab.
    tabs.openTab({ ...phoneTab('sid-leg2'), isActive: true });
    tabs.updateTabContactId('tab-1', 'uuid-1');
    fixture.componentRef.setInput('tab', phoneTab('uuid-1'));
    await settle();

    expect(drafts.phoneNote('uuid-1')).toBe('notatka');
    expect(textarea()?.value).toBe('notatka');
  });

  it('shows an empty note when the new contactId has none (input change only)', async () => {
    drafts.setPhoneNote('sid-leg2', 'notatka');
    await settle();
    fixture.componentRef.setInput('tab', phoneTab('other'));
    await settle();
    expect(textarea()?.value).toBe('');
  });
});
