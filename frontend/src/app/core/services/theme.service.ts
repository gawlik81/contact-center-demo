import { Injectable, signal, effect } from '@angular/core';

export type ThemeMode = 'light' | 'dark' | 'auto';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly mode = signal<ThemeMode>((localStorage.getItem('kmn-theme') as ThemeMode) || 'auto');
  readonly resolved = signal<'light' | 'dark'>('light');

  constructor() {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');

    const compute = () => {
      const m = this.mode();
      const r = m === 'auto' ? (mq.matches ? 'dark' : 'light') : m;
      this.resolved.set(r);
      document.documentElement.setAttribute('data-theme', r);
    };

    effect(compute);
    mq.addEventListener('change', compute);
  }

  setMode(mode: ThemeMode): void {
    this.mode.set(mode);
    localStorage.setItem('kmn-theme', mode);
  }
}
