import { DatePipe, LowerCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { ContactTab } from '../../models/contact-tab.model';

/**
 * Header of the active contact card: avatar, name, identifier, start time and channel badge.
 * Purely presentational; extracted from AgentDesktopComponent (FE-124) to keep its style budget.
 * In a low viewport the whole header collapses to a single 37px row.
 */
@Component({
  selector: 'app-contact-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, LowerCasePipe, TranslocoModule],
  templateUrl: './contact-header.component.html',
  styleUrl: './contact-header.component.scss',
})
export class ContactHeaderComponent {
  readonly tab = input.required<ContactTab>();
  /** Localised channel label (the parent owns the translation of the channel names). */
  readonly typeLabel = input.required<string>();

  protected readonly showIdentifier = computed(
    () => this.tab().customerIdentifier !== this.tab().customerName,
  );

  /** Full text for the tooltip where the compact one-row header ellipsizes it. */
  protected readonly fullTitle = computed(() => {
    const t = this.tab();
    return this.showIdentifier() ? `${t.customerName} · ${t.customerIdentifier}` : t.customerName;
  });
}
