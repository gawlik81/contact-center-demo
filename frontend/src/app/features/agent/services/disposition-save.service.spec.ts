import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from '../../../core/services/auth.service';
import { NotificationService } from '../../../core/services/notification.service';
import { AgentRecoveryService } from './agent-recovery.service';
import { AgentStatusService } from './agent-status.service';
import { ContactDraftStore } from './contact-draft.store';
import { ContactService } from './contact.service';
import { ContactTabStore } from './contact-tab.store';
import { DispositionSaveService } from './disposition-save.service';

describe('DispositionSaveService conflict handling (FE-163, BE-209)', () => {
  const setDisposition = vi.fn();
  const notify = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };
  const recovery = {
    reconcileContact: vi
      .fn()
      .mockResolvedValue({ verdict: 'mine', closed: false, notesCopied: false }),
  };
  let service: DispositionSaveService;
  let tabs: ContactTabStore;
  let drafts: ContactDraftStore;

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [
        { provide: ContactService, useValue: { setDisposition } },
        { provide: NotificationService, useValue: notify },
        { provide: TranslocoService, useValue: { translate: (k: string) => k } },
        { provide: AgentRecoveryService, useValue: recovery },
        { provide: AuthService, useValue: { isAuthenticated: signal(true) } },
        {
          provide: AgentStatusService,
          useValue: { isChanging: signal(false), changeStatus: () => of(undefined) },
        },
      ],
    });
    service = TestBed.inject(DispositionSaveService);
    tabs = TestBed.inject(ContactTabStore);
    drafts = TestBed.inject(ContactDraftStore);
    tabs.reset();
    tabs.openTab({
      id: 't1',
      type: 'PHONE',
      contactId: 'c1',
      customerName: 'Jan',
      customerIdentifier: '+48',
      status: 'WRAPPING',
      startedAt: new Date(),
    });
    drafts.setDispositionDraft('c1', { code: 'SOLD', notes: 'Wazne notatki' } as never);
  });

  const request = { tabId: 't1', contactId: 'c1', code: 'SOLD', notes: 'Wazne notatki' };

  const settle = async () => {
    for (let i = 0; i < 5; i++) await Promise.resolve();
  };

  it('409: one clear message, contact checked, card and typed disposition kept, retry possible', async () => {
    setDisposition.mockReturnValue(throwError(() => ({ status: 409 })));
    service.save(request);
    await settle();

    expect(notify.warning).toHaveBeenCalledTimes(1);
    expect(notify.warning).toHaveBeenCalledWith('agent.contactConflict.disposition');
    expect(notify.error).not.toHaveBeenCalled();
    expect(recovery.reconcileContact).toHaveBeenCalledWith('c1');
    expect(tabs.tabs().map((t) => t.id)).toEqual(['t1']);
    expect(drafts.dispositionDraft('c1')).toMatchObject({ notes: 'Wazne notatki' });
    expect(service.isSaving('c1')).toBe(false);

    setDisposition.mockReturnValue(of({}));
    service.save(request);
    expect(setDisposition).toHaveBeenCalledTimes(2);
    expect(notify.success).toHaveBeenCalledWith('agent.disposition.successSave');
  });

  it('other errors keep the generic message and do not refresh', () => {
    setDisposition.mockReturnValue(throwError(() => ({ status: 500 })));
    service.save(request);
    expect(notify.error).toHaveBeenCalledWith('agent.disposition.errorSave');
    expect(notify.warning).not.toHaveBeenCalled();
    expect(recovery.reconcileContact).not.toHaveBeenCalled();
    expect(tabs.tabs()).toHaveLength(1);
  });
});
