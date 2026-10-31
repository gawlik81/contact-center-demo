import { CallSession, CallState } from './call-session.model';
import { resolveTabMarker, sessionMatchesTab } from './contact-tab-marker';
import { ContactTab, ContactTabStatus, ContactType } from './contact-tab.model';

const tab = (type: ContactType, status: ContactTabStatus = 'ACTIVE'): ContactTab => ({
  id: 't1',
  type,
  contactId: 'c1',
  customerName: 'Jan',
  customerIdentifier: 'x',
  status,
  isActive: true,
  startedAt: new Date(),
});

const session = (state: CallState, contactId = 'c1'): CallSession => ({
  contactId,
  customerName: 'Jan',
  customerPhone: '1',
  queueName: '',
  state,
  startedAt: null,
  duration: 0,
  isMuted: false,
  holdStartedAt: null,
  holdDuration: 0,
  transferTarget: null,
});

describe('resolveTabMarker (FE-133 priority ACW > Hold > In call > Draft)', () => {
  it('shows no marker when nothing applies', () => {
    expect(resolveTabMarker(tab('EMAIL'), null, false)).toBeNull();
  });

  it('shows Draft for an unsent draft', () => {
    expect(resolveTabMarker(tab('EMAIL'), null, true)).toBe('DRAFT');
  });

  it('shows In call for an answered call and Hold for a held one', () => {
    expect(resolveTabMarker(tab('PHONE'), session('ACTIVE'), false)).toBe('CALL');
    expect(resolveTabMarker(tab('PHONE'), session('TRANSFERRING'), false)).toBe('CALL');
    expect(resolveTabMarker(tab('PHONE'), session('ON_HOLD'), false)).toBe('HOLD');
  });

  it('does not call a ringing or ended call "in call"', () => {
    expect(resolveTabMarker(tab('PHONE'), session('RINGING'), false)).toBeNull();
    expect(resolveTabMarker(tab('PHONE'), session('ENDED'), false)).toBeNull();
  });

  it('ignores the session of another contact', () => {
    expect(resolveTabMarker(tab('PHONE'), session('ON_HOLD', 'other'), false)).toBeNull();
  });

  it('ignores a PHONE session for a non-phone tab', () => {
    expect(resolveTabMarker(tab('EMAIL'), session('ON_HOLD'), false)).toBeNull();
  });

  it('Hold beats In call beats Draft', () => {
    expect(resolveTabMarker(tab('PHONE'), session('ON_HOLD'), true)).toBe('HOLD');
    expect(resolveTabMarker(tab('PHONE'), session('ACTIVE'), true)).toBe('CALL');
  });

  it('ACW beats everything', () => {
    expect(resolveTabMarker(tab('PHONE', 'WRAPPING'), session('ON_HOLD'), true)).toBe('ACW');
    expect(resolveTabMarker(tab('PHONE', 'WRAPPING'), session('ACTIVE'), true)).toBe('ACW');
    expect(resolveTabMarker(tab('PHONE', 'WRAPPING'), null, true)).toBe('ACW');
  });
});

describe('sessionMatchesTab', () => {
  it('matches the tab contactId and, for consultation tabs, the originalContactId', () => {
    expect(sessionMatchesTab(session('ACTIVE', 'c1'), tab('PHONE'))).toBe(true);
    const consult = { ...tab('PHONE'), contactId: 'CA-1', originalContactId: 'uuid-1' };
    expect(sessionMatchesTab(session('ACTIVE', 'CA-1'), consult)).toBe(true);
    expect(sessionMatchesTab(session('ACTIVE', 'uuid-1'), consult)).toBe(true);
    expect(sessionMatchesTab(session('ACTIVE', 'other'), consult)).toBe(false);
  });

  it('never matches a non-phone tab or a missing session', () => {
    expect(sessionMatchesTab(session('ACTIVE'), tab('EMAIL'))).toBe(false);
    expect(sessionMatchesTab(null, tab('PHONE'))).toBe(false);
  });
});
