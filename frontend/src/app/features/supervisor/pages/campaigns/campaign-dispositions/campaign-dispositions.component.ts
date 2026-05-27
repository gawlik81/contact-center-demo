import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { DispositionListEditorComponent } from '../../../../../shared/components/disposition-list-editor/disposition-list-editor.component';

@Component({
  selector: 'app-campaign-dispositions',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [DispositionListEditorComponent],
  templateUrl: './campaign-dispositions.component.html',
  styleUrl: './campaign-dispositions.component.scss',
})
export class CampaignDispositionsComponent {
  readonly campaignId = input.required<string>();
}
