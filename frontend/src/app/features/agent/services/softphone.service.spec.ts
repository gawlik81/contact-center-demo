import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import pl from '../../../../../public/i18n/pl.json';
import { SoftphoneService } from './softphone.service';

type Handlers = Record<string, (...args: unknown[]) => void>;

interface DeviceFactory {
  createTwilioDevice: (token: string) => unknown;
}

describe('SoftphoneService twilioDeviceError', () => {
  let service: SoftphoneService;
  let http: HttpTestingController;
  let handlers: Handlers;
  let destroySpy: ReturnType<typeof vi.fn>;
  let removeSpy: ReturnType<typeof vi.fn>;

  function fakeDevice(register: () => Promise<void>) {
    return {
      on: (event: string, cb: (...args: unknown[]) => void) => {
        handlers[event] = cb;
      },
      register,
      removeAllListeners: removeSpy,
      destroy: destroySpy,
    };
  }

  function stubDevice(factory: () => unknown) {
    vi.spyOn(service as unknown as DeviceFactory, 'createTwilioDevice').mockImplementation(factory);
  }

  beforeEach(() => {
    handlers = {};
    destroySpy = vi.fn();
    removeSpy = vi.fn();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(SoftphoneService);
    http = TestBed.inject(HttpTestingController);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => service.ngOnDestroy());

  async function init(): Promise<void> {
    const p = service.initializeTwilioDevice();
    http
      .expectOne((r) => r.url.endsWith('/telephony/voice-token'))
      .flush({ token: 't', identity: 'agent-1' });
    await p;
  }

  it('stores an i18n key (not a literal) when the voice token cannot be fetched', async () => {
    const p = service.initializeTwilioDevice();
    http
      .expectOne((r) => r.url.endsWith('/telephony/voice-token'))
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await p;

    expect(service.twilioDeviceError()).toBe('agent.softphone.errors.tokenFetch');
    expect(pl.agent.softphone.errors.tokenFetch).toBeTruthy();
  });

  it('does not set an error when tenant has no Twilio config (404)', async () => {
    vi.spyOn(console, 'info').mockImplementation(() => undefined);
    const p = service.initializeTwilioDevice();
    http
      .expectOne((r) => r.url.endsWith('/telephony/voice-token'))
      .flush('', { status: 404, statusText: 'Not Found' });
    await p;
    expect(service.twilioDeviceError()).toBeNull();
  });

  it("stores the device key on Device 'error' without leaking the raw message", async () => {
    stubDevice(() => fakeDevice(() => Promise.resolve()));
    await init();
    expect(service.twilioDeviceError()).toBeNull();

    handlers['error'](new Error('31005 raw twilio detail'));

    expect(service.twilioDeviceError()).toBe('agent.softphone.errors.device');
    expect(service.twilioDeviceReady()).toBe(false);
    expect(pl.agent.softphone.errors.device).toBeTruthy();
  });

  it('stores the device key when Device construction throws', async () => {
    stubDevice(() => {
      throw new Error('boom: bad token');
    });
    await init();
    expect(service.twilioDeviceError()).toBe('agent.softphone.errors.device');
    expect(service.twilioDeviceReady()).toBe(false);
  });

  it('stores the device key when register() rejects', async () => {
    stubDevice(() => fakeDevice(() => Promise.reject(new Error('register failed'))));
    await init();
    expect(service.twilioDeviceError()).toBe('agent.softphone.errors.device');
  });

  it('ignores call-scoped Device errors (second argument is the call)', async () => {
    stubDevice(() => fakeDevice(() => Promise.resolve()));
    await init();
    handlers['error'](new Error('31003 call failed'), { sid: 'CA1' });
    expect(service.twilioDeviceError()).toBeNull();
  });

  describe('retryTwilioDevice', () => {
    const tokenReq = () => http.expectOne((r) => r.url.endsWith('/telephony/voice-token'));

    async function failedDevice() {
      const factory = vi.fn(() => fakeDevice(() => Promise.resolve()));
      stubDevice(factory);
      await init();
      handlers['error'](new Error('x'));
      return factory;
    }

    it('keeps the error visible while retrying, then clears it on `registered`', async () => {
      const factory = await failedDevice();
      const errors: unknown[] = [];

      const p = service.retryTwilioDevice();
      errors.push(service.twilioDeviceError());
      expect(service.twilioDeviceInitializing()).toBe(true);
      tokenReq().flush({ token: 't2', identity: 'agent-1' });
      await p;
      errors.push(service.twilioDeviceError());
      handlers['registered']();

      expect(errors).toEqual(['agent.softphone.errors.device', 'agent.softphone.errors.device']);
      expect(service.twilioDeviceError()).toBeNull();
      expect(service.twilioDeviceInitializing()).toBe(false);
      expect(factory).toHaveBeenCalledTimes(2);
      // the old Device is torn down, not left registered next to the new one
      expect(removeSpy).toHaveBeenCalledTimes(1);
      expect(destroySpy).toHaveBeenCalledTimes(1);
    });

    it('keeps the same error (never null) when the retry fails again', async () => {
      await failedDevice();
      const seen: unknown[] = [];
      const p = service.retryTwilioDevice();
      seen.push(service.twilioDeviceError());
      tokenReq().flush('boom', { status: 500, statusText: 'Server Error' });
      await p;
      seen.push(service.twilioDeviceError());
      expect(seen).toEqual(['agent.softphone.errors.device', 'agent.softphone.errors.tokenFetch']);
    });

    it('is a no-op during a call (no token request)', async () => {
      await failedDevice();
      service.session.set({ state: 'ACTIVE' } as never);
      await service.retryTwilioDevice();
      http.expectNone((r) => r.url.endsWith('/telephony/voice-token'));
      expect(destroySpy).not.toHaveBeenCalled();
    });

    it('does not destroy the Device when a call starts while the token is in flight', async () => {
      await failedDevice();
      const p = service.retryTwilioDevice();
      service.session.set({ state: 'RINGING' } as never);
      tokenReq().flush({ token: 't2', identity: 'agent-1' });
      await p;
      expect(destroySpy).not.toHaveBeenCalled();
    });

    it('coalesces concurrent calls and allows a new GET after the flight ends', async () => {
      const factory = vi.fn(() => fakeDevice(() => Promise.resolve()));
      stubDevice(factory);
      const a = service.retryTwilioDevice();
      const b = service.retryTwilioDevice();
      tokenReq().flush({ token: 't', identity: 'agent-1' });
      await Promise.all([a, b]);
      expect(factory).toHaveBeenCalledTimes(1);

      const c = service.retryTwilioDevice();
      tokenReq().flush({ token: 't3', identity: 'agent-1' });
      await c;
      expect(factory).toHaveBeenCalledTimes(2);
    });

    it('destroys a Device whose register() failed so a retry cannot leak it', async () => {
      stubDevice(() => fakeDevice(() => Promise.reject(new Error('nope'))));
      await init();
      expect(destroySpy).toHaveBeenCalledTimes(1);
    });

    it('resetForLogout during register() destroys that Device and leaves state clean', async () => {
      let release!: () => void;
      stubDevice(() => fakeDevice(() => new Promise<void>((r) => (release = r))));
      const p = service.initializeTwilioDevice();
      tokenReq().flush({ token: 't', identity: 'agent-1' });
      await Promise.resolve();
      service.resetForLogout();
      release();
      await p;
      expect(destroySpy).toHaveBeenCalledTimes(1);
      expect(service.twilioDeviceInitializing()).toBe(false);
      expect(service.twilioDeviceError()).toBeNull();
    });

    it('stale Device events after resetForLogout do not touch the new session state', async () => {
      let release!: () => void;
      stubDevice(() => fakeDevice(() => new Promise<void>((r) => (release = r))));
      const p = service.initializeTwilioDevice();
      tokenReq().flush({ token: 't', identity: 'agent-1' });
      await Promise.resolve();
      const old = { ...handlers };
      service.resetForLogout();
      release();
      await p;

      old['registered']();
      old['error'](new Error('late'));
      old['unregistered']();

      expect(service.twilioDeviceReady()).toBe(false);
      expect(service.twilioDeviceError()).toBeNull();
    });

    it('resetForLogout during the token fetch drops the stale init', async () => {
      const factory = vi.fn(() => fakeDevice(() => Promise.resolve()));
      stubDevice(factory);
      const p = service.initializeTwilioDevice();
      service.resetForLogout();
      tokenReq().flush({ token: 't', identity: 'agent-1' });
      await p;
      expect(factory).not.toHaveBeenCalled();
      // a fresh init after logout is not coalesced into the stale one
      const q = service.initializeTwilioDevice();
      tokenReq().flush({ token: 't', identity: 'agent-1' });
      await q;
      expect(factory).toHaveBeenCalledTimes(1);
    });
  });
});
