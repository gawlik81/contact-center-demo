import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { ContactService } from './contact.service';

describe('ContactService email endpoints', () => {
  let service: ContactService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ContactService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('getEmailPreview returns the multi-message payload', () => {
    let result: unknown;
    service.getEmailPreview('c1').subscribe((r) => (result = r));
    const req = httpMock.expectOne('/api/contacts/c1/email-preview');
    expect(req.request.method).toBe('GET');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    const body = { messages: [], totalMessages: 0, truncated: false };
    req.flush(body);
    expect(result).toEqual(body);
  });

  it('getEmailMessageEmlUrl calls the per-message EML endpoint', () => {
    service.getEmailMessageEmlUrl('c1', 'm9').subscribe();
    const req = httpMock.expectOne('/api/contacts/c1/email-messages/m9/eml');
    expect(req.request.method).toBe('GET');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({
      presignedUrl: 'https://s3/x.eml',
      expiresAt: '2026-01-01T00:00:00Z',
      fileName: 'x.eml',
    });
  });
});
