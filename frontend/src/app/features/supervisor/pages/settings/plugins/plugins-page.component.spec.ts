import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of, throwError } from 'rxjs';
import { PluginsPageComponent } from './plugins-page.component';
import { PluginAdminService } from '../../../../plugins/services/plugin-admin.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { PluginVersionDto } from '../../../../plugins/models/plugin.model';

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

const LABEL_PL = 'Zastąp istniejącą wersję (overwrite)';
const CONFLICT_PL = 'Ta wersja pluginu jest już wgrana dla tego tenanta.';
const CONFLICT_HINT_PL =
  'Włącz opcję „Zastąp istniejącą wersję”, aby ją nadpisać, albo zwiększ numer wersji w manifeście pluginu i wgraj go ponownie.';
const UPLOAD_ERROR_PL = 'Nie udało się wgrać pliku pluginu.';

function jarFile(): File {
  return new File(['jar-content'], 'crm-sync.jar', { type: 'application/java-archive' });
}

function jarEvent(file: File): Event {
  return { target: { files: [file], value: '' } } as unknown as Event;
}

describe('PluginsPageComponent – overwrite upload', () => {
  let fixture: ComponentFixture<PluginsPageComponent>;
  let component: PluginsPageComponent;

  let uploadJarSpy: ReturnType<typeof vi.fn>;
  let errorSpy: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    uploadJarSpy = vi.fn().mockReturnValue(of(mockVersion));
    errorSpy = vi.fn();

    await TestBed.configureTestingModule({
      imports: [
        PluginsPageComponent,
        TranslocoTestingModule.forRoot({
          langs: {
            pl: {
              supervisor: {
                settings: {
                  plugins: {
                    overwriteLabel: LABEL_PL,
                    overwriteHint: 'Dotyczy wgrania wersji o tym samym numerze.',
                    errorConflict: CONFLICT_PL,
                    errorConflictHint: CONFLICT_HINT_PL,
                    errorUpload: UPLOAD_ERROR_PL,
                  },
                },
              },
            },
          },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        {
          provide: PluginAdminService,
          useValue: {
            listInstallations: () => of([]),
            listCatalog: () => of([]),
            uploadJar: uploadJarSpy,
          },
        },
        {
          provide: NotificationService,
          useValue: { success: vi.fn(), error: errorSpy, warning: vi.fn(), info: vi.fn() },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PluginsPageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function overwriteInput(): HTMLInputElement {
    return fixture.nativeElement.querySelector('#pp-overwrite-toggle') as HTMLInputElement;
  }

  function toggleOverwrite(checked: boolean): void {
    const input = overwriteInput();
    input.checked = checked;
    input.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  function conflictAlert(): HTMLElement | null {
    return fixture.nativeElement.querySelector('[role="alert"]');
  }

  it('przełącznik overwrite jest domyślnie wyłączony', () => {
    expect(component.overwriteEnabled()).toBe(false);
    expect(overwriteInput().checked).toBe(false);
  });

  it('etykieta jest powiązana z checkboxem przełącznika', () => {
    const label = overwriteInput().closest('label');
    expect(label?.textContent?.trim()).toBe(LABEL_PL);
    expect(overwriteInput().getAttribute('aria-describedby')).toBe('pp-overwrite-hint');
  });

  it('upload przy wyłączonym przełączniku wywołuje uploadJar z overwrite=false', () => {
    const file = jarFile();
    component.onFileSelected(jarEvent(file));

    expect(uploadJarSpy).toHaveBeenCalledTimes(1);
    expect(uploadJarSpy).toHaveBeenCalledWith(file, false);
  });

  it('po włączeniu przełącznika upload wywołuje uploadJar z overwrite=true', () => {
    toggleOverwrite(true);
    expect(component.overwriteEnabled()).toBe(true);

    const file = jarFile();
    component.onFileSelected(jarEvent(file));

    expect(uploadJarSpy).toHaveBeenCalledWith(file, true);
  });

  it('409 przy overwrite=false pokazuje inline podpowiedź, nie włącza przełącznika i nie toastuje', () => {
    uploadJarSpy.mockReturnValueOnce(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { detail: 'Wersja 1.0.0 pluginu crm-sync jest już wgrana' },
          }),
      ),
    );

    component.onFileSelected(jarEvent(jarFile()));
    fixture.detectChanges();

    const alert = conflictAlert();
    expect(alert).not.toBeNull();
    expect(alert?.textContent).toContain(CONFLICT_PL);
    expect(alert?.textContent).toContain(CONFLICT_HINT_PL);
    expect(component.overwriteEnabled()).toBe(false);
    expect(overwriteInput().checked).toBe(false);
    expect(errorSpy).not.toHaveBeenCalled();
  });

  it('po konflikcie włączenie przełącznika i ponowny upload czyści komunikat konfliktu', () => {
    uploadJarSpy.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409 })));
    component.onFileSelected(jarEvent(jarFile()));
    fixture.detectChanges();
    expect(conflictAlert()).not.toBeNull();

    toggleOverwrite(true);
    component.onFileSelected(jarEvent(jarFile()));
    fixture.detectChanges();

    expect(uploadJarSpy).toHaveBeenLastCalledWith(expect.any(File), true);
    expect(conflictAlert()).toBeNull();
  });

  it('409 przy włączonym overwrite jest zwykłym błędem (toast), bez podpowiedzi konfliktu', () => {
    toggleOverwrite(true);
    uploadJarSpy.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409 })));

    component.onFileSelected(jarEvent(jarFile()));
    fixture.detectChanges();

    expect(conflictAlert()).toBeNull();
    expect(errorSpy).toHaveBeenCalledWith(UPLOAD_ERROR_PL);
  });

  it('błąd inny niż 409 pokazuje zwykły toast błędu uploadu', () => {
    uploadJarSpy.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.onFileSelected(jarEvent(jarFile()));
    fixture.detectChanges();

    expect(conflictAlert()).toBeNull();
    expect(errorSpy).toHaveBeenCalledWith(UPLOAD_ERROR_PL);
  });
});
