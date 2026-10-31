import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { firstValueFrom, throwError } from 'rxjs';
import en from '../../../../../public/i18n/en.json';
import pl from '../../../../../public/i18n/pl.json';
import {
  AiConfigNotSetError,
  AiServiceUnavailableError,
  AiSummaryService,
} from '../../../features/agent/services/ai-summary.service';
import { AiSummaryPanelComponent } from './ai-summary-panel.component';

describe.each([
  ['pl', pl],
  ['en', en],
] as const)('AiSummaryPanelComponent copy (FE-119, %s)', (lang, dict) => {
  let fixture: ComponentFixture<AiSummaryPanelComponent>;
  let root: HTMLElement;
  let generateSummary: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    generateSummary = vi.fn();
    TestBed.configureTestingModule({
      imports: [
        AiSummaryPanelComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl, en } as never,
          translocoConfig: { availableLangs: ['pl', 'en'], defaultLang: lang },
        }),
      ],
      providers: [{ provide: AiSummaryService, useValue: { generateSummary } }],
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load(lang));
    fixture = TestBed.createComponent(AiSummaryPanelComponent);
    fixture.componentRef.setInput('contactId', 'c-1');
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const generate = async (): Promise<void> => {
    root.querySelector<HTMLButtonElement>('.btn--secondary')?.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  it('renders the controls from i18n', () => {
    expect(root.textContent).toContain(dict.agent.aiSummary.title);
    expect(root.textContent).toContain(dict.agent.aiSummary.generate);
  });

  it.each([
    [new AiConfigNotSetError(), 'errorNotConfigured'],
    [new AiServiceUnavailableError(), 'errorUnavailable'],
    [new Error('boom'), 'errorUnknown'],
  ] as const)('maps %o to a translated message', async (err, key) => {
    generateSummary.mockReturnValue(throwError(() => err));
    await generate();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain(dict.agent.aiSummary[key]);
  });
});
