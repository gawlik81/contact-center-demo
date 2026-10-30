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

  function fakeDevice(register: () => Promise<void>) {
    return {
      on: (event: string, cb: (...args: unknown[]) => void) => {
        handlers[event] = cb;
      },
      register,
      destroy: () => undefined,
    };
  }

  function stubDevice(factory: () => unknown) {
    vi.spyOn(service as unknown as DeviceFactory, 'createTwilioDevice').mockImplementation(factory);
  }

  beforeEach(() => {
    handlers = {};
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
});
