---
name: Language switcher component (FE-051)
description: LanguageSwitcherComponent — custom dropdown w TopNavbar, integracja z LanguageService, testy 12/12
type: project
---

`LanguageSwitcherComponent` (`app-language-switcher`) zaimplementowany w `shared/components/language-switcher/`.

**Kluczowe decyzje:**
- Custom dropdown (nie natywny `<select>`) z `position: absolute`, animacja CSS `langDropdownIn`
- `@HostListener('document:click')` do zamykania po kliknięciu poza komponentem — bez CDK Overlay
- `isOpen = signal(false)`, `currentLangLabel = computed(() => currentLang().toUpperCase())`
- `TranslocoModule` w imports (pipe `transloco` na kluczach `language.pl/en/de`)
- Wstawiony w `TopNavbarComponent` przed przyciskiem logout; import `LanguageSwitcherComponent` dodany do tablicy `imports`
- Styl dopasowany do navbara: border `#e2e8f0`, border-radius 6px, hover `#f1f5f9`, aktywna opcja `#eff6ff/1a56db`
- ARIA: `aria-expanded`, `aria-haspopup="listbox"`, `role="listbox"`, `role="option"`, `aria-selected`

**Testy (12/12 passed):**
- `vi` nie importujemy z 'vitest' — dostępne globalnie przez Angular builder
- Uruchamianie: `ng test --watch=false --include="..."` (nie `npx vitest run`)
- Mock `LanguageService`: signal writable + `vi.fn()` dla `setLanguage`
- `TranslocoTestingModule.forRoot({ langs: { pl: {...} } })`

**Why:** FE-051 — przełącznik języka w globalnym navbarze dla wszystkich ról.
**How to apply:** Przy kolejnych komponentach shared z dropdown używaj tego samego wzorca `@HostListener('document:click')` + `isOpen signal`.
