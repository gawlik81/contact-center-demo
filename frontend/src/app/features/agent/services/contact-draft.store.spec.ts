import { TestBed } from '@angular/core/testing';

import { ContactDraftStore, EmailDraft } from './contact-draft.store';
import { ContactTabStore } from './contact-tab.store';

function emailDraft(overrides: Partial<EmailDraft> = {}): EmailDraft {
  return {
    bodyHtml: '<p>Dzien dobry</p>',
    subject: 'Re: Temat',
    subjectEdited: false,
    template: null,
    templateVariables: {},
    showVariableForm: false,
    attachments: [],
    ...overrides,
  };
}

describe('ContactDraftStore', () => {
  let store: ContactDraftStore;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    store = TestBed.inject(ContactDraftStore);
  });

  it('keeps email draft fields per contactId', () => {
    const tpl = {
      id: 't1',
      name: 'Powitanie',
      subjectTemplate: '',
      bodyHtml: '',
      variables: ['a'],
    };
    store.setEmailDraft('c1', emailDraft({ template: tpl, templateVariables: { a: 'x' } }));
    store.setEmailDraft('c2', emailDraft({ bodyHtml: '<p>inny</p>' }));

    expect(store.emailDraft('c1')?.template).toEqual(tpl);
    expect(store.emailDraft('c1')?.templateVariables).toEqual({ a: 'x' });
    expect(store.emailDraft('c2')?.bodyHtml).toBe('<p>inny</p>');
  });

  it('reports unsent drafts for email, social and phone', () => {
    expect(store.hasUnsentDraft('c1')).toBe(false);
    store.setEmailDraft('c1', emailDraft());
    store.setSocialReply('c2', 'czesc');
    store.setPhoneNote('c3', 'notatka');

    expect(store.hasUnsentDraft('c1')).toBe(true);
    expect(store.hasUnsentDraft('c2')).toBe(true);
    expect(store.hasUnsentDraft('c3')).toBe(true);
    expect([...store.draftContactIds()].sort()).toEqual(['c1', 'c2', 'c3']);
  });

  it('does not count an empty editor, whitespace or only a default subject as a draft', () => {
    store.setEmailDraft('c1', emailDraft({ bodyHtml: '<div><br></div>' }));
    store.setEmailDraft('c2', emailDraft({ bodyHtml: '', subjectEdited: true }));
    store.setSocialReply('c3', '   ');
    store.setPhoneNote('c4', '');

    expect(store.draftContactIds().size).toBe(0);
  });

  it('counts an email with only an attachment as a draft', () => {
    store.setEmailDraft(
      'c1',
      emailDraft({
        bodyHtml: '',
        attachments: [
          { s3Key: 'k', filename: 'a.pdf', contentType: 'application/pdf', sizeBytes: 1 },
        ],
      }),
    );
    expect(store.hasUnsentDraft('c1')).toBe(true);
  });

  it('removes a draft when its text is cleared', () => {
    store.setSocialReply('c1', 'abc');
    store.setSocialReply('c1', '');
    expect(store.hasUnsentDraft('c1')).toBe(false);
    expect(store.socialReply('c1')).toBe('');
  });

  it('markAccepted is true only for the first call and can be reverted', () => {
    expect(store.markAccepted('c1')).toBe(true);
    expect(store.markAccepted('c1')).toBe(false);
    store.unmarkAccepted('c1');
    expect(store.markAccepted('c1')).toBe(true);
  });

  it('clear drops all drafts and the accepted flag of one contact only', () => {
    store.setEmailDraft('c1', emailDraft());
    store.setSocialReply('c1', 'x');
    store.setPhoneNote('c1', 'n');
    store.markAccepted('c1');
    store.setSocialReply('c2', 'zostaje');

    store.clear('c1');

    expect(store.hasUnsentDraft('c1')).toBe(false);
    expect(store.markAccepted('c1')).toBe(true);
    expect(store.socialReply('c2')).toBe('zostaje');
  });

  it('rekey moves drafts to a new contactId', () => {
    store.setPhoneNote('old', 'notatka');
    store.rekey('old', 'new');
    expect(store.phoneNote('new')).toBe('notatka');
    expect(store.hasUnsentDraft('old')).toBe(false);
  });

  it('rekey merges notes and keeps the target drafts instead of overwriting them', () => {
    store.setPhoneNote('new', 'notatka glowna');
    store.setPhoneNote('old', 'notatka konsultacji');
    store.setSocialReply('new', 'docelowa');
    store.setSocialReply('old', 'stara');
    store.setEmailDraft('new', emailDraft({ bodyHtml: '<p>docelowy</p>' }));
    store.setEmailDraft('old', emailDraft({ bodyHtml: '<p>stary</p>' }));
    store.markAccepted('old');

    store.rekey('old', 'new');

    expect(store.phoneNote('new')).toBe('notatka glowna\nnotatka konsultacji');
    expect(store.socialReply('new')).toBe('docelowa');
    expect(store.emailDraft('new')?.bodyHtml).toBe('<p>docelowy</p>');
    expect(store.hasUnsentDraft('old')).toBe(false);
    expect(store.markAccepted('new')).toBe(false);
  });

  it('keeps a template-only email draft but does not call it unsent', () => {
    const tpl = { id: 't', name: 'n', subjectTemplate: '', bodyHtml: '', variables: [] };
    store.setEmailDraft('c1', emailDraft({ bodyHtml: '', template: tpl }));
    expect(store.emailDraft('c1')?.template).toEqual(tpl);
    expect(store.hasUnsentDraft('c1')).toBe(false);
  });

  it('tracks sending per contact and refuses a second send', () => {
    expect(store.markSending('c1')).toBe(true);
    expect(store.markSending('c1')).toBe(false);
    expect(store.isSending('c1')).toBe(true);
    expect(store.isSending('c2')).toBe(false);
    store.unmarkSending('c1');
    expect(store.isSending('c1')).toBe(false);
  });

  it('clearAll wipes everything', () => {
    store.setPhoneNote('c1', 'n');
    store.markAccepted('c1');
    store.clearAll();
    expect(store.draftContactIds().size).toBe(0);
    expect(store.markAccepted('c1')).toBe(true);
  });

  it('never writes to web storage', () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    store.setEmailDraft('c1', emailDraft());
    store.setSocialReply('c1', 'x');
    store.setPhoneNote('c1', 'n');
    expect(local).not.toHaveBeenCalled();
    local.mockRestore();
  });

  it('caches the offered dispositions per contact and drops them with the contact', () => {
    store.setAvailableDispositions('c1', [{ code: 'SOLD', label: 'S', tone: 'positive' }]);
    expect(store.availableDispositions('c1')).toHaveLength(1);
    expect(store.availableDispositions('c2')).toBeNull();
    store.clear('c1');
    expect(store.availableDispositions('c1')).toBeNull();
    store.setAvailableDispositions('c3', []);
    store.clearAll();
    expect(store.availableDispositions('c3')).toBeNull();
  });

  it('rekey keeps the disposition draft under the new contactId (attended transfer bridge)', () => {
    store.setDispositionDraft('old', { code: 'SOLD', notes: 'n' });
    store.rekey('old', 'new');
    expect(store.dispositionDraft('old')).toBeNull();
    expect(store.dispositionDraft('new')).toEqual({ code: 'SOLD', notes: 'n' });
  });

  it('keeps the disposition draft per contact and drops it with the single cleanup point', () => {
    store.setDispositionDraft('c1', { code: 'SOLD', notes: 'n' });
    store.setDispositionDraft('c2', { code: 'NO_ANSWER', notes: '' });
    expect(store.dispositionDraft('c1')).toEqual({ code: 'SOLD', notes: 'n' });
    // it is not an "unsent draft": ACW closes only by saving, no Draft marker / close guard
    expect(store.hasUnsentDraft('c1')).toBe(false);

    store.clear('c1');
    expect(store.dispositionDraft('c1')).toBeNull();
    expect(store.dispositionDraft('c2')?.code).toBe('NO_ANSWER');

    store.clearAll();
    expect(store.dispositionDraft('c2')).toBeNull();
  });
});

describe('ContactTabStore + ContactDraftStore', () => {
  let tabs: ContactTabStore;
  let drafts: ContactDraftStore;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    tabs = TestBed.inject(ContactTabStore);
    drafts = TestBed.inject(ContactDraftStore);
  });

  const open = (id: string, contactId: string, type: 'EMAIL' | 'SOCIAL' | 'PHONE') =>
    tabs.openTab({
      id,
      type,
      contactId,
      customerName: 'Jan',
      customerIdentifier: 'x',
      status: 'ACTIVE',
      startedAt: new Date(),
    });

  it('draft survives a tab switch', () => {
    open('t1', 'c1', 'EMAIL');
    open('t2', 'c2', 'SOCIAL');
    drafts.setSocialReply('c2', 'odpowiedz');
    drafts.setPhoneNote('c1', 'notatka');

    tabs.setActiveTab('t1');
    tabs.setActiveTab('t2');

    expect(drafts.socialReply('c2')).toBe('odpowiedz');
    expect(drafts.phoneNote('c1')).toBe('notatka');
  });

  it('closing a tab clears only that contact draft', () => {
    open('t1', 'c1', 'EMAIL');
    open('t2', 'c2', 'SOCIAL');
    drafts.setSocialReply('c1', 'a');
    drafts.setSocialReply('c2', 'b');
    drafts.markAccepted('c1');

    tabs.closeTab('t1');

    expect(drafts.hasUnsentDraft('c1')).toBe(false);
    expect(drafts.markAccepted('c1')).toBe(true);
    expect(drafts.socialReply('c2')).toBe('b');
  });

  it('PHONE note is cleared on close (call ended / contact disappeared)', () => {
    open('t1', 'c1', 'PHONE');
    drafts.setPhoneNote('c1', 'notatka');
    tabs.closeTab('t1');
    expect(drafts.phoneNote('c1')).toBe('');
  });

  it('closing a consultation tab also clears drafts under originalContactId', () => {
    tabs.openTab({
      id: 't1',
      type: 'PHONE',
      contactId: 'sid',
      originalContactId: 'orig',
      customerName: 'Jan',
      customerIdentifier: 'x',
      status: 'ACTIVE',
      startedAt: new Date(),
    });
    drafts.setPhoneNote('orig', 'n');
    tabs.closeTab('t1');
    expect(drafts.phoneNote('orig')).toBe('');
  });

  it('closeByContactId is a no-op for an unknown contact', () => {
    open('t1', 'c1', 'EMAIL');
    tabs.closeByContactId('nope');
    expect(tabs.tabs().length).toBe(1);
    tabs.closeByContactId('c1');
    expect(tabs.tabs().length).toBe(0);
  });

  it('updateTabContactId carries the PHONE note over', () => {
    open('t1', 'sid-leg2', 'PHONE');
    drafts.setPhoneNote('sid-leg2', 'notatka');
    tabs.updateTabContactId('t1', 'uuid-1');
    expect(drafts.phoneNote('uuid-1')).toBe('notatka');
  });
});
