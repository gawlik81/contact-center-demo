import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { of } from 'rxjs';
import { DataRetentionComponent } from './data-retention.component';
import { RetentionService } from '../../../services/retention.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { RetentionDataCategory, RetentionSummaryDto } from '../../../models/retention.model';

// jsdom does not implement HTMLDialogElement.showModal/close.
beforeAll(() => {
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  }
  if (!HTMLDialogElement.prototype.close) {
    HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
  }
});

const CATEGORIES: RetentionDataCategory[] = [
  'CONTACT_INTERACTIONS',
  'RECORDINGS',
  'TRANSCRIPTS',
  'CAMPAIGN_DATA',
];

function summary(category: RetentionDataCategory): RetentionSummaryDto {
  return {
    dataCategory: category,
    eligibleRowCount: 5,
    oldestEligiblePeriod: '2024-01-01',
    newestEligiblePeriod: '2024-03-01',
    computedAt: '2026-10-01T10:00:00Z',
    computed: true,
  };
}

const translations = {
  supervisor: {
    settings: {
      dataRetention: {
        category: {
          CONTACT_INTERACTIONS: 'Interakcje z kontaktami',
          RECORDINGS: 'Nagrania rozmów',
          TRANSCRIPTS: 'Transkrypcje rozmów',
          CAMPAIGN_DATA: 'Dane kampanii',
        },
        categoryDescription: {
          CONTACT_INTERACTIONS: 'Opis interakcji z wiadomościami',
          RECORDINGS: 'Opis nagrań',
          TRANSCRIPTS: 'Opis transkrypcji',
          CAMPAIGN_DATA: 'Opis kampanii',
        },
        purgeModal: { contactInteractionsNote: 'Liczba obejmuje także wiadomości' },
      },
    },
  },
};

describe('DataRetentionComponent (FE-110)', () => {
  let fixture: ComponentFixture<DataRetentionComponent>;
  let component: DataRetentionComponent;

  beforeEach(async () => {
    const retentionServiceMock = {
      listPolicies: vi.fn().mockReturnValue(
        of(
          CATEGORIES.map((c, i) => ({
            policyId: 'p' + i,
            tenantId: 't',
            dataCategory: c,
            retentionMonths: 12,
            autoPurgeEnabled: false,
            updatedBy: null,
            createdAt: '',
            updatedAt: '',
          })),
        ),
      ),
      getSummary: vi.fn().mockReturnValue(of(CATEGORIES.map(summary))),
      getHistory: vi
        .fn()
        .mockReturnValue(of({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 })),
    } as unknown as RetentionService;

    await TestBed.configureTestingModule({
      imports: [
        DataRetentionComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: translations },
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        { provide: RetentionService, useValue: retentionServiceMock },
        {
          provide: NotificationService,
          useValue: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DataRetentionComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('enables "Usuń teraz" for CONTACT_INTERACTIONS, TRANSCRIPTS and CAMPAIGN_DATA', () => {
    for (const category of ['CONTACT_INTERACTIONS', 'TRANSCRIPTS', 'CAMPAIGN_DATA'] as const) {
      expect(component.isPurgeUnsupported(category)).toBe(false);
      expect(component.isPurgeDisabled(summary(category))).toBe(false);
    }
  });

  it('keeps "Usuń teraz" disabled for RECORDINGS', () => {
    expect(component.isPurgeUnsupported('RECORDINGS')).toBe(true);
    expect(component.isPurgeDisabled(summary('RECORDINGS'))).toBe(true);
  });

  it('renders only the RECORDINGS purge button as disabled with a hint title', () => {
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('.dr-summary-card__actions button'),
    ) as HTMLButtonElement[];
    expect(buttons.map((b) => b.disabled)).toEqual([false, true, false, false]);
  });

  it('shows category descriptions in the policy table and on summary cards', () => {
    const el: HTMLElement = fixture.nativeElement;
    const descriptions = Array.from(el.querySelectorAll('.dr-category-description')).map((n) =>
      n.textContent?.trim(),
    );
    // 4 in the policy table + 4 on the summary cards
    expect(descriptions.filter((d) => d === 'Opis interakcji z wiadomościami').length).toBe(2);
    expect(descriptions.length).toBe(8);
  });

  it('shows the messages/attachments note in the purge modal only for CONTACT_INTERACTIONS', () => {
    component.openPurgeModal(summary('CONTACT_INTERACTIONS'));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="purge-contact-interactions-note"]')).not.toBeNull();

    component.closePurgeModal();
    fixture.detectChanges();
    component.openPurgeModal(summary('CAMPAIGN_DATA'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="purge-contact-interactions-note"]')).toBeNull();
  });
});
