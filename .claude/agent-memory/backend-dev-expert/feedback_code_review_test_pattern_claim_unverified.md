---
name: feedback-code-review-test-pattern-claim-unverified
description: Nie ufaj cytatowi z code review o "istniejącym wzorcu testowym" bez otwarcia pliku — grep po nazwie narzędzia może trafić w Javadoc opisujący jego BRAK
metadata:
  type: feedback
---

Przy BE129-02 `CR-BACKEND.md` napisał wprost: "część kontrolerów (`EmailAttachmentControllerTest`,
`RetentionControllerTest`, `CustomerImportControllerTest` i inne, potwierdzone grepem po
`@WithMockUser`/`MockMvc`) MA testy na poziomie Springa". Po otwarciu obu wskazanych plików okazało
się, że NIE używają `MockMvc` w ogóle — słowo "MockMvc" występuje WYŁĄCZNIE w Javadoc klasy, który
explicite tłumaczy DLACZEGO tego narzędzia NIE użyto ("Wzorzec testów kontrolera w tym projekcie:
wywołanie metody kontrolera bezpośrednio, bez MockMvc..."). Recenzent (inny agent/przebieg) najwyraźniej
zrobił grep po samym słowie i nie doczytał kontekstu zdania.

**Why:** gdybym zbudował `GdprControllerTest` "wzorowany" na tych dwóch plikach dosłownie (goła
metoda kontrolera, bez `MockMvc`), test w ogóle NIE dowiódłby tego, co zadanie wymagało (`@PreAuthorize`
faktycznie blokujący 403, `ConflictException` faktycznie mapowany przez `GlobalExceptionHandler` na
409) — oba mechanizmy wymagają przejścia przez proxy AOP/DispatcherServlet, których bezpośrednie
wywołanie metody Javy całkowicie omija. Test wyglądałby na pokrycie, a niczego by nie dowodził.

**How to apply:** gdy code review (własny wcześniejszy przebieg, inny agent, albo notatka pamięci)
twierdzi "ten plik już to robi/ma ten wzorzec" — zawsze otwórz plik i sprawdź TREŚĆ, nie tylko czy
nazwa narzędzia/API gdzieś występuje. Dotyczy zwłaszcza claimów o testach ("ma MockMvc", "ma
`@WithMockUser`", "testuje X") — grep -l po nazwie narzędzia łapie też komentarze/Javadoc opisujące
BRAK tego narzędzia. Prawdziwy wzorzec MockMvc w tym repo to `CampaignImportControllerTest`/
`CustomerImportControllerTest` (`grep -rl "MockMvc\b"` + realna inspekcja treści potwierdza
`@Autowired MockMvc mockMvc` + `mockMvc.perform(...)`).
