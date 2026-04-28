import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  HostListener,
  inject,
  signal,
  ViewChild,
} from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { LanguageService } from '../../../core/services/language.service';
import { SUPPORTED_LANGUAGES, SupportedLanguage } from '../../../core/models/language.model';

const LANGUAGE_FLAGS: Record<SupportedLanguage, string> = {
  pl: '🇵🇱',
  en: '🇬🇧',
  de: '🇩🇪',
};

@Component({
  selector: 'app-language-switcher',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  templateUrl: './language-switcher.component.html',
  styleUrl: './language-switcher.component.scss',
})
export class LanguageSwitcherComponent {
  protected readonly languageService = inject(LanguageService);

  @ViewChild('switcher') private switcherRef!: ElementRef<HTMLElement>;

  readonly languages: SupportedLanguage[] = SUPPORTED_LANGUAGES;
  readonly currentLang = this.languageService.currentLang;
  readonly flags = LANGUAGE_FLAGS;

  readonly isOpen = signal(false);

  readonly currentLangLabel = computed(() => this.currentLang().toUpperCase());

  toggleDropdown(event: Event): void {
    event.stopPropagation();
    this.isOpen.update((v) => !v);
  }

  selectLanguage(lang: SupportedLanguage): void {
    this.languageService.setLanguage(lang);
    this.isOpen.set(false);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.isOpen()) {
      return;
    }
    const target = event.target as Node;
    if (this.switcherRef && !this.switcherRef.nativeElement.contains(target)) {
      this.isOpen.set(false);
    }
  }
}
