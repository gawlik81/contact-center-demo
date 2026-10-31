import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { PluginAdminService } from './plugin-admin.service';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { PluginVersionDto } from '../models/plugin.model';

const UPLOAD_URL = '/api/supervisor/plugins';

const mockVersion: PluginVersionDto = {
  id: 'v1',
  pluginId: 'p1',
  pluginKey: 'crm-sync',
  displayName: 'CRM Sync',
  vendor: 'ACME',
  version: '1.0.0',
  sdkVersion: '1.0',
  status: 'VALIDATED',
  validationErrors: null,
  permissions: [],
  uploadedByUserId: 'u1',
  uploadedAt: '2026-01-01T10:00:00Z',
};

function jarFile(): File {
  return new File(['jar-content'], 'crm-sync.jar', { type: 'application/java-archive' });
}

describe('PluginAdminService – uploadJar', () => {
  let service: PluginAdminService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PluginAdminService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('bez argumentu overwrite nie dodaje query param overwrite', () => {
    let result: PluginVersionDto | undefined;
    service.uploadJar(jarFile()).subscribe((v) => (result = v));

    const req = httpMock.expectOne((r) => r.method === 'POST' && r.url === UPLOAD_URL);
    expect(req.request.params.has('overwrite')).toBe(false);
    expect(req.request.urlWithParams).toBe(UPLOAD_URL);
    expect(req.request.body).toBeInstanceOf(FormData);
    expect((req.request.body as FormData).get('file')).toBeInstanceOf(File);
    req.flush(mockVersion);

    expect(result).toEqual(mockVersion);
  });

  it('overwrite=false nie dodaje query param overwrite', () => {
    service.uploadJar(jarFile(), false).subscribe();

    const req = httpMock.expectOne((r) => r.method === 'POST' && r.url === UPLOAD_URL);
    expect(req.request.params.has('overwrite')).toBe(false);
    expect(req.request.urlWithParams).toBe(UPLOAD_URL);
    req.flush(mockVersion);
  });

  it('overwrite=true dodaje query param overwrite=true', () => {
    service.uploadJar(jarFile(), true).subscribe();

    const req = httpMock.expectOne((r) => r.method === 'POST' && r.url === UPLOAD_URL);
    expect(req.request.params.get('overwrite')).toBe('true');
    expect(req.request.urlWithParams).toBe(`${UPLOAD_URL}?overwrite=true`);
    req.flush(mockVersion);
  });

  it('ustawia SKIP_ERROR_TOAST, żeby globalny interceptor nie dublował błędu konfliktu', () => {
    service.uploadJar(jarFile(), false).subscribe({ error: () => undefined });

    const req = httpMock.expectOne((r) => r.method === 'POST' && r.url === UPLOAD_URL);
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush(
      { title: 'Konflikt stanu zasobu', status: 409, detail: 'konflikt' },
      { status: 409, statusText: 'Conflict' },
    );
  });
});
