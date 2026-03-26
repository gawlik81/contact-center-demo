---
name: Angular test patterns — no fakeAsync, no zone.js testing
description: Tests use async/await + TestBed.compileComponents(), NOT fakeAsync/tick. Zone.js testing not available.
type: project
---

The project uses `@angular/build:unit-test` builder with Vitest v4. Zone.js testing (`zone-testing.js`) is NOT available.

**Do NOT use:**
- `fakeAsync()` / `tick()`
- `jasmine.clock()`

**DO use:**
- `async/await` + `TestBed.configureTestingModule(...).compileComponents()`
- `fixture.detectChanges()` synchronously after mock setup
- RxJS `of()` for synchronous mock observables (subscriptions complete immediately)
- `firstValueFrom()` when you need a Promise from an Observable
- Direct signal reads: `component.someSignal()` after `fixture.detectChanges()`

**Test setup pattern (from customer-list spec):**
```typescript
beforeEach(async () => {
  await TestBed.configureTestingModule({
    imports: [MyComponent],
    providers: [{ provide: MyService, useValue: mockService }],
  }).compileComponents();
  fixture = TestBed.createComponent(MyComponent);
  component = fixture.componentInstance;
  fixture.detectChanges();
});
```

**For required inputs:** use `fixture.componentRef.setInput('inputName', value)` before `detectChanges()`.
