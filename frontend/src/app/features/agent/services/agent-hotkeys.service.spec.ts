import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { AgentHotkeysService } from './agent-hotkeys.service';
import { CallLifecycleService } from './call-lifecycle.service';
import { CallSession, CallState } from '../models/call-session.model';

function session(state: CallState): CallSession {
  return {
    contactId: 'c-1',
    customerName: 'Jan Kowalski',
    customerPhone: '+48123456789',
    queueName: 'Sprzedaz',
    state,
    startedAt: null,
    duration: 0,
    isMuted: false,
    holdStartedAt: null,
    holdDuration: 0,
    transferTarget: null,
  };
}

function press(init: KeyboardEventInit, target: EventTarget = document.body): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...init });
  target.dispatchEvent(event);
  return event;
}

const ctrlAlt = { ctrlKey: true, altKey: true };

describe('AgentHotkeysService', () => {
  const sessionSignal = signal<CallSession | null>(null);
  const ringingNow = () => sessionSignal()?.state === 'RINGING';
  const liveNow = () => ['ACTIVE', 'ON_HOLD'].includes(sessionSignal()?.state ?? '');
  const calls = {
    answerRingingCall: vi.fn(ringingNow),
    hangupOrReject: vi.fn(() => ringingNow() || liveNow()),
    toggleMute: vi.fn(liveNow),
  };

  beforeEach(() => {
    vi.clearAllMocks();
    sessionSignal.set(null);
    TestBed.configureTestingModule({
      providers: [AgentHotkeysService, { provide: CallLifecycleService, useValue: calls }],
    });
    TestBed.inject(AgentHotkeysService);
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  describe('Ctrl+Alt+Enter (answer)', () => {
    it('answers a ringing call and prevents the default action', () => {
      sessionSignal.set(session('RINGING'));
      const event = press({ ...ctrlAlt, code: 'Enter', key: 'Enter' });
      expect(calls.answerRingingCall).toHaveBeenCalledTimes(1);
      expect(event.defaultPrevented).toBe(true);
    });

    it('also reacts to NumpadEnter', () => {
      sessionSignal.set(session('RINGING'));
      press({ ...ctrlAlt, code: 'NumpadEnter', key: 'Enter' });
      expect(calls.answerRingingCall).toHaveBeenCalledTimes(1);
    });

    it('is a no-op without a ringing call and does not swallow the key', () => {
      const event = press({ ...ctrlAlt, code: 'Enter', key: 'Enter' });
      expect(event.defaultPrevented).toBe(false);
    });

    it('needs both Ctrl and Alt', () => {
      sessionSignal.set(session('RINGING'));
      press({ ctrlKey: true, code: 'Enter', key: 'Enter' });
      press({ altKey: true, code: 'Enter', key: 'Enter' });
      expect(calls.answerRingingCall).not.toHaveBeenCalled();
    });
  });

  describe('Ctrl+Alt+Backspace (hang up / reject)', () => {
    it.each<CallState>(['RINGING', 'ACTIVE', 'ON_HOLD'])('acts on a %s call', (state) => {
      sessionSignal.set(session(state));
      const event = press({ ...ctrlAlt, code: 'Backspace', key: 'Backspace' });
      expect(calls.hangupOrReject).toHaveBeenCalledTimes(1);
      expect(event.defaultPrevented).toBe(true);
    });

    it('is a no-op without a call and does not swallow the key', () => {
      const event = press({ ...ctrlAlt, code: 'Backspace', key: 'Backspace' });
      expect(event.defaultPrevented).toBe(false);
    });

    it('plain Backspace in a textarea is never affected', () => {
      sessionSignal.set(session('ACTIVE'));
      const textarea = document.createElement('textarea');
      document.body.appendChild(textarea);
      textarea.focus();
      const event = press({ code: 'Backspace', key: 'Backspace' }, textarea);
      expect(calls.hangupOrReject).not.toHaveBeenCalled();
      expect(event.defaultPrevented).toBe(false);
    });

    it('Ctrl+Backspace and Alt+Backspace alone do nothing (word delete stays intact)', () => {
      sessionSignal.set(session('ACTIVE'));
      press({ ctrlKey: true, code: 'Backspace', key: 'Backspace' });
      press({ altKey: true, code: 'Backspace', key: 'Backspace' });
      expect(calls.hangupOrReject).not.toHaveBeenCalled();
    });

    it('the old Ctrl+Alt+End is no longer bound', () => {
      sessionSignal.set(session('ACTIVE'));
      press({ ...ctrlAlt, code: 'End', key: 'End' });
      expect(calls.hangupOrReject).not.toHaveBeenCalled();
    });
  });

  describe('Ctrl+Alt+M (mute)', () => {
    it('toggles mute during an active call', () => {
      sessionSignal.set(session('ACTIVE'));
      const event = press({ ...ctrlAlt, code: 'KeyM', key: 'm' });
      expect(calls.toggleMute).toHaveBeenCalledTimes(1);
      expect(event.defaultPrevented).toBe(true);
    });

    it('matches by code, so a non-latin layout still works', () => {
      sessionSignal.set(session('ACTIVE'));
      press({ ...ctrlAlt, code: 'KeyM', key: 'ь' });
      expect(calls.toggleMute).toHaveBeenCalledTimes(1);
    });

    it('does not swallow the key when there is no call to mute', () => {
      const idle = press({ ...ctrlAlt, code: 'KeyM', key: 'm' });
      expect(idle.defaultPrevented).toBe(false);
    });
  });

  describe('AltGr (reported as Ctrl+Alt) must keep typing characters', () => {
    const altGraph = { ...ctrlAlt, modifierAltGraph: true };

    it.each([
      ['Ctrl+Alt+KeyM', { code: 'KeyM', key: 'µ' }],
      ['Ctrl+Alt+Enter', { code: 'Enter', key: 'Enter' }],
      ['Ctrl+Alt+Backspace', { code: 'Backspace', key: 'Backspace' }],
    ])('ignores %s while AltGraph is active', (_name, key) => {
      sessionSignal.set(session('RINGING'));
      const event = press({ ...altGraph, ...key });
      expect(event.getModifierState('AltGraph')).toBe(true);
      expect(event.defaultPrevented).toBe(false);
      expect(calls.answerRingingCall).not.toHaveBeenCalled();
      expect(calls.hangupOrReject).not.toHaveBeenCalled();
      expect(calls.toggleMute).not.toHaveBeenCalled();
    });

    it('leaves unbound Polish letters (Ctrl+Alt+KeyA) alone', () => {
      sessionSignal.set(session('ACTIVE'));
      const event = press({ ...ctrlAlt, code: 'KeyA', key: 'ą' });
      expect(event.defaultPrevented).toBe(false);
      expect(calls.toggleMute).not.toHaveBeenCalled();
    });
  });

  describe('auto-repeat and IME composition', () => {
    it('ignores a held-down shortcut (repeat)', () => {
      sessionSignal.set(session('ACTIVE'));
      const event = press({ ...ctrlAlt, code: 'KeyM', key: 'm', repeat: true });
      expect(calls.toggleMute).not.toHaveBeenCalled();
      expect(event.defaultPrevented).toBe(false);
    });

    it('ignores keys while an IME composition is in progress', () => {
      sessionSignal.set(session('RINGING'));
      press({ ...ctrlAlt, code: 'Enter', key: 'Enter', isComposing: true });
      expect(calls.answerRingingCall).not.toHaveBeenCalled();
    });
  });

  describe('while typing', () => {
    it('call shortcuts work from a textarea', () => {
      sessionSignal.set(session('RINGING'));
      const textarea = document.createElement('textarea');
      document.body.appendChild(textarea);
      textarea.focus();
      const event = press({ ...ctrlAlt, code: 'Enter', key: 'Enter' }, textarea);
      expect(calls.answerRingingCall).toHaveBeenCalledTimes(1);
      expect(event.defaultPrevented).toBe(true);
    });
  });

  describe('? (cheat sheet)', () => {
    it('toggles the cheat sheet when focus is not editable', () => {
      const service = TestBed.inject(AgentHotkeysService);
      const event = press({ key: '?', shiftKey: true, code: 'Slash' });
      expect(service.cheatSheetOpen()).toBe(true);
      expect(event.defaultPrevented).toBe(true);
      press({ key: '?', shiftKey: true, code: 'Slash' });
      expect(service.cheatSheetOpen()).toBe(false);
    });

    it.each(['textarea', 'input'])('is ignored inside a %s', (tag) => {
      const service = TestBed.inject(AgentHotkeysService);
      const field = document.createElement(tag);
      document.body.appendChild(field);
      field.focus();
      const event = press({ key: '?', shiftKey: true, code: 'Slash' }, field);
      expect(service.cheatSheetOpen()).toBe(false);
      expect(event.defaultPrevented).toBe(false);
    });

    it('is ignored inside a contenteditable element', () => {
      const service = TestBed.inject(AgentHotkeysService);
      const editor = document.createElement('div');
      editor.contentEditable = 'true';
      Object.defineProperty(editor, 'isContentEditable', { value: true });
      document.body.appendChild(editor);
      press({ key: '?', code: 'Slash' }, editor);
      expect(service.cheatSheetOpen()).toBe(false);
    });

    it('closes on Esc and returns focus to the element that had it', () => {
      const service = TestBed.inject(AgentHotkeysService);
      const trigger = document.createElement('button');
      document.body.appendChild(trigger);
      trigger.focus();

      service.toggleCheatSheet();
      const other = document.createElement('button');
      document.body.appendChild(other);
      other.focus();

      const event = press({ key: 'Escape', code: 'Escape' }, other);
      expect(service.cheatSheetOpen()).toBe(false);
      expect(event.defaultPrevented).toBe(true);
      expect(document.activeElement).toBe(trigger);
    });

    it('does not open while a modal dialog is open', () => {
      const service = TestBed.inject(AgentHotkeysService);
      const dialog = document.createElement('dialog');
      dialog.setAttribute('open', '');
      document.body.appendChild(dialog);
      press({ key: '?', code: 'Slash' });
      expect(service.cheatSheetOpen()).toBe(false);
    });

    it('leaves Esc to another layer that already used it (status menu)', () => {
      const service = TestBed.inject(AgentHotkeysService);
      service.toggleCheatSheet();
      const item = document.createElement('button');
      document.body.appendChild(item);
      item.addEventListener('keydown', (e) => e.preventDefault());
      item.focus();
      press({ key: 'Escape', code: 'Escape' }, item);
      expect(service.cheatSheetOpen()).toBe(true);
    });

    it('leaves Esc to an open dialog', () => {
      const service = TestBed.inject(AgentHotkeysService);
      service.toggleCheatSheet();
      const dialog = document.createElement('dialog');
      dialog.setAttribute('open', '');
      document.body.appendChild(dialog);
      press({ key: 'Escape', code: 'Escape' });
      expect(service.cheatSheetOpen()).toBe(true);
    });

    it('closes on Esc when focus is inside the sheet even with a dialog open', () => {
      const service = TestBed.inject(AgentHotkeysService);
      service.toggleCheatSheet();
      const sheet = document.createElement('section');
      sheet.setAttribute('data-cc-cheat-sheet', '');
      sheet.tabIndex = -1;
      document.body.appendChild(sheet);
      sheet.focus();
      const dialog = document.createElement('dialog');
      dialog.setAttribute('open', '');
      document.body.appendChild(dialog);
      press({ key: 'Escape', code: 'Escape' }, sheet);
      expect(service.cheatSheetOpen()).toBe(false);
    });

    it('does not swallow Esc while the cheat sheet is closed', () => {
      const event = press({ key: 'Escape', code: 'Escape' });
      expect(event.defaultPrevented).toBe(false);
    });
  });

  it('removes its listener when the injector is destroyed', () => {
    sessionSignal.set(session('RINGING'));
    TestBed.resetTestingModule();
    press({ ...ctrlAlt, code: 'Enter', key: 'Enter' });
    expect(calls.answerRingingCall).not.toHaveBeenCalled();
  });
});
