import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { TranslocoTestingModule } from '@jsverse/transloco';

import { LanguageSwitcherComponent } from './language-switcher.component';
import { LanguageService } from '../../../core/services/language.service';

// ── Translations stub ──────────────────────────────────────────────────────────

const plTranslations = {
  language: { select: 'Wybierz język', pl: 'Polski', en: 'English', de: 'Deutsch' },
};

// ── Helpers ────────────────────────────────────────────────────────────────────

function buildLanguageServiceMock(initialLang: 'pl' | 'en' | 'de' = 'pl') {
  const _lang = signal<'pl' | 'en' | 'de'>(initialLang);
  return {
    currentLang: _lang.asReadonly(),
    setLanguage: vi.fn((lang: 'pl' | 'en' | 'de') => _lang.set(lang)),
  };
}

// ── Tests ──────────────────────────────────────────────────────────────────────

describe('LanguageSwitcherComponent', () => {
  let fixture: ComponentFixture<LanguageSwitcherComponent>;
  let component: LanguageSwitcherComponent;
  let langServiceMock: ReturnType<typeof buildLanguageServiceMock>;

  function setup(initialLang: 'pl' | 'en' | 'de' = 'pl') {
    langServiceMock = buildLanguageServiceMock(initialLang);

    TestBed.configureTestingModule({
      imports: [
        LanguageSwitcherComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl: plTranslations },
          translocoConfig: { availableLangs: ['pl', 'en', 'de'], defaultLang: 'pl' },
        }),
      ],
      providers: [{ provide: LanguageService, useValue: langServiceMock }],
    });

    fixture = TestBed.createComponent(LanguageSwitcherComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  // ── Rendering ──────────────────────────────────────────────────────────────

  it('renders current language label in trigger button', () => {
    setup('en');
    const trigger = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    expect(trigger.textContent).toContain('EN');
  });

  it('does not render dropdown list initially', () => {
    setup();
    const list = fixture.nativeElement.querySelector('.lang-switcher__list');
    expect(list).toBeNull();
  });

  // ── Toggle ─────────────────────────────────────────────────────────────────

  it('opens dropdown when trigger is clicked', () => {
    setup();
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();
    const list = fixture.nativeElement.querySelector('.lang-switcher__list');
    expect(list).not.toBeNull();
  });

  it('closes dropdown when trigger is clicked again', () => {
    setup();
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();
    trigger.click();
    fixture.detectChanges();
    const list = fixture.nativeElement.querySelector('.lang-switcher__list');
    expect(list).toBeNull();
  });

  it('sets aria-expanded="true" when dropdown is open', () => {
    setup();
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
  });

  it('sets aria-expanded="false" when dropdown is closed', () => {
    setup();
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });

  // ── Language selection ─────────────────────────────────────────────────────

  it('calls languageService.setLanguage when an option is clicked', () => {
    setup('pl');
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();

    const options: HTMLElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.lang-switcher__option'),
    );
    const enOption = options.find((el) => el.textContent?.includes('EN'));
    expect(enOption).toBeTruthy();
    enOption!.click();
    fixture.detectChanges();

    expect(langServiceMock.setLanguage).toHaveBeenCalledWith('en');
  });

  it('closes dropdown after selecting a language', () => {
    setup('pl');
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();

    const firstOption: HTMLElement = fixture.nativeElement.querySelector('.lang-switcher__option');
    firstOption.click();
    fixture.detectChanges();

    expect(component.isOpen()).toBe(false);
  });

  // ── aria-selected ──────────────────────────────────────────────────────────

  it('marks active option with aria-selected="true"', () => {
    setup('de');
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();

    const options: HTMLElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.lang-switcher__option'),
    );
    const deOption = options.find((el) => el.textContent?.includes('DE'));
    expect(deOption).toBeTruthy();
    expect(deOption!.getAttribute('aria-selected')).toBe('true');
  });

  it('marks inactive options with aria-selected="false"', () => {
    setup('pl');
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();

    const options: HTMLElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('.lang-switcher__option'),
    );
    const enOption = options.find((el) => el.textContent?.includes('EN'));
    expect(enOption).toBeTruthy();
    expect(enOption!.getAttribute('aria-selected')).toBe('false');
  });

  // ── Click outside ──────────────────────────────────────────────────────────

  it('closes dropdown when clicking outside the component', () => {
    setup();
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();
    expect(component.isOpen()).toBe(true);

    // Simulate click on document body (outside the component)
    const outsideClick = new MouseEvent('click', { bubbles: true });
    document.body.dispatchEvent(outsideClick);
    fixture.detectChanges();

    expect(component.isOpen()).toBe(false);
  });

  it('does not close dropdown when clicking inside the component', () => {
    setup();
    const trigger: HTMLButtonElement = fixture.nativeElement.querySelector('.lang-switcher__trigger');
    trigger.click();
    fixture.detectChanges();
    expect(component.isOpen()).toBe(true);

    // Simulate click inside the component element
    const insideClick = new MouseEvent('click', { bubbles: true });
    fixture.nativeElement.querySelector('.lang-switcher__list').dispatchEvent(insideClick);
    fixture.detectChanges();

    expect(component.isOpen()).toBe(true);
  });
});
