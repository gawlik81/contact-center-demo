import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';
import pl from '../../../../../../public/i18n/pl.json';
import { NoContactsComponent } from './no-contacts.component';

describe('NoContactsComponent', () => {
  it('renders a status message with a decorative icon', () => {
    TestBed.configureTestingModule({
      imports: [
        NoContactsComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
    });
    const fixture = TestBed.createComponent(NoContactsComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.no-contacts')?.getAttribute('role')).toBe('status');
    expect(el.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(el.querySelector('.no-contacts__text')?.textContent?.trim()).toBe(
      pl.agent.desktop.noActiveContacts,
    );
  });
});
