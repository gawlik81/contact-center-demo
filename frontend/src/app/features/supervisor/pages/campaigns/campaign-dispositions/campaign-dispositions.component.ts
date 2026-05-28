import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { TranslocoModule } from '@jsverse/transloco';
import { DispositionListEditorComponent } from '../../../../../shared/components/disposition-list-editor/disposition-list-editor.component';

@Component({
  selector: 'app-campaign-dispositions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [DispositionListEditorComponent, TranslocoModule],
  templateUrl: './campaign-dispositions.component.html',
  styleUrl: './campaign-dispositions.component.scss',
})
export class CampaignDispositionsComponent {
  readonly campaignId = input.required<string>();
}
