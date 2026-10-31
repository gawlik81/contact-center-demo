import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { WebSocketService } from '../../core/services/websocket.service';
import { AgentShellComponent } from './agent-shell.component';
import { AgentHotkeysService } from './services/agent-hotkeys.service';
import { AgentRecoveryService } from './services/agent-recovery.service';
import { AgentStatusService } from './services/agent-status.service';
import { CallLifecycleService } from './services/call-lifecycle.service';
import { ContactDraftStore } from './services/contact-draft.store';
import { ContactTabStore } from './services/contact-tab.store';
import { SoftphoneService } from './services/softphone.service';

describe('AgentShellComponent logout cleanup', () => {
  const softphone = {
    twilioDeviceReady: signal(true),
    twilioDeviceError: signal(null),
    resetForLogout: vi.fn(),
    initializeTwilioDevice: vi.fn(),
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      imports: [AgentShellComponent],
      providers: [
        {
          provide: WebSocketService,
          useValue: {
            connect: vi.fn(),
            disconnect: vi.fn(),
            onConnect: vi.fn(() => () => undefined),
          },
        },
        {
          provide: AgentStatusService,
          useValue: { currentStatus: signal('OFFLINE'), initStatus: vi.fn(), stopStatus: vi.fn() },
        },
        { provide: SoftphoneService, useValue: softphone },
        { provide: AgentRecoveryService, useValue: { recoverAfterReconnect: vi.fn() } },
        { provide: CallLifecycleService, useValue: {} },
      ],
    });
    TestBed.overrideComponent(AgentShellComponent, {
      set: {
        template: '',
        imports: [],
        providers: [{ provide: AgentHotkeysService, useValue: {} }],
      },
    });
    await TestBed.compileComponents();
  });

  it('drops drafts, accepted flags and tabs when the agent area is destroyed', () => {
    const drafts = TestBed.inject(ContactDraftStore);
    const tabs = TestBed.inject(ContactTabStore);
    tabs.openTab({
      id: 't1',
      type: 'EMAIL',
      contactId: 'c1',
      customerName: 'Jan',
      customerIdentifier: 'x',
      status: 'ACTIVE',
      startedAt: new Date(),
    });
    drafts.setSocialReply('c1', 'szkic');
    drafts.markAccepted('c1');

    const fixture = TestBed.createComponent(AgentShellComponent);
    fixture.detectChanges();
    fixture.destroy();

    expect(softphone.resetForLogout).toHaveBeenCalled();
    expect(drafts.hasUnsentDraft('c1')).toBe(false);
    expect(drafts.markAccepted('c1')).toBe(true);
    expect(tabs.tabs()).toEqual([]);
  });
});
