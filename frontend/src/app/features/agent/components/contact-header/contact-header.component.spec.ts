import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';
import pl from '../../../../../../public/i18n/pl.json';
import { ContactTab } from '../../models/contact-tab.model';
import { ContactHeaderComponent } from './contact-header.component';

function makeTab(overrides: Partial<ContactTab> = {}): ContactTab {
  return {
    id: 't-1',
    type: 'PHONE',
    contactId: 'c-1',
    customerName: 'Jan Kowalski',
    customerIdentifier: '+48123456789',
    status: 'ACTIVE',
    isActive: true,
    startedAt: new Date(2026, 9, 29, 12, 34, 56),
    ...overrides,
  };
}

function render(tab: ContactTab, typeLabel = 'Telefon') {
  TestBed.configureTestingModule({
    imports: [
      ContactHeaderComponent,
      TranslocoTestingModule.forRoot({
        langs: { pl } as never,
        translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
      }),
    ],
  });
  const fixture = TestBed.createComponent(ContactHeaderComponent);
  fixture.componentRef.setInput('tab', tab);
  fixture.componentRef.setInput('typeLabel', typeLabel);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('ContactHeaderComponent', () => {
  it('shows avatar initial, name, identifier, start time and channel label', () => {
    const el = render(makeTab());
    expect(el.querySelector('.contact-header__avatar')?.textContent?.trim()).toBe('J');
    expect(el.querySelector('.contact-header__avatar')?.getAttribute('aria-hidden')).toBe('true');
    expect(el.querySelector('h2')?.textContent?.trim()).toBe('Jan Kowalski');
    expect(el.querySelector('.contact-header__identifier')?.textContent?.trim()).toBe(
      '+48123456789',
    );
    expect(el.querySelector('.contact-header__since')?.textContent).toContain('12:34:56');
    expect(el.querySelector('.contact-header__type-badge')?.textContent?.trim()).toBe('Telefon');
  });

  it('puts the full name and identifier into the title', () => {
    const el = render(makeTab());
    expect(el.querySelector('.contact-header__info')?.getAttribute('title')).toBe(
      'Jan Kowalski · +48123456789',
    );
  });

  it('hides the identifier when it equals the name', () => {
    const el = render(makeTab({ customerName: '+48123456789' }));
    expect(el.querySelector('.contact-header__identifier')).toBeNull();
    expect(el.querySelector('.contact-header__info')?.getAttribute('title')).toBe('+48123456789');
  });

  it.each([
    ['PHONE', 'phone'],
    ['CHAT', 'chat'],
    ['EMAIL', 'email'],
    ['SOCIAL', 'social'],
  ] as const)('applies the %s tone class to the badge', (type, tone) => {
    const el = render(makeTab({ type }));
    expect(
      el
        .querySelector('.contact-header__type-badge')
        ?.classList.contains(`contact-header__type-badge--${tone}`),
    ).toBe(true);
  });
});
