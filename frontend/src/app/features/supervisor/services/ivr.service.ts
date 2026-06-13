import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateIvrRequest,
  IvrDefinition,
  IvrDefinitionUI,
  IvrNodeUI,
  IvrResponse,
  UpdateIvrRequest,
} from '../models/ivr.model';

const DEFAULT_NODE_SPACING_X = 260;
const DEFAULT_NODE_SPACING_Y = 160;

@Injectable({ providedIn: 'root' })
export class IvrService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/ivr`;

  getIvrList(): Observable<IvrResponse[]> {
    return this.http.get<IvrResponse[]>(this.baseUrl);
  }

  getIvr(ivrId: string): Observable<IvrResponse> {
    return this.http.get<IvrResponse>(`${this.baseUrl}/${ivrId}`);
  }

  createIvr(req: CreateIvrRequest): Observable<IvrResponse> {
    return this.http.post<IvrResponse>(this.baseUrl, req);
  }

  updateIvr(ivrId: string, req: UpdateIvrRequest): Observable<IvrResponse> {
    return this.http.patch<IvrResponse>(`${this.baseUrl}/${ivrId}`, req);
  }

  deleteIvr(ivrId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${ivrId}`);
  }

  activateIvr(ivrId: string): Observable<IvrResponse> {
    return this.http.post<IvrResponse>(`${this.baseUrl}/${ivrId}/activate`, {});
  }

  deactivateIvr(ivrId: string): Observable<IvrResponse> {
    return this.http.post<IvrResponse>(`${this.baseUrl}/${ivrId}/deactivate`, {});
  }

  /**
   * Converts IvrResponse definition to UI definition, applying saved layout positions
   * (from definition.layout) or falling back to a default grid position.
   */
  toUIDefinition(_ivrId: string, response: IvrResponse): IvrDefinitionUI {
    const layout = response.definition.layout ?? {};
    const cols = 3;
    const nodes: IvrNodeUI[] = response.definition.nodes.map((n, index) => {
      const pos = layout[n.node_id];
      return {
        ...n,
        // PLAY_AUDIO always needs a "next" output option; add it if missing (e.g. legacy data)
        options:
          n.type === 'PLAY_AUDIO' && (!n.options || n.options.length === 0)
            ? [{ key: 'next', next_node_id: '' }]
            : (n.options ?? []),
        x: pos?.x ?? 40 + (index % cols) * DEFAULT_NODE_SPACING_X,
        y: pos?.y ?? 40 + Math.floor(index / cols) * DEFAULT_NODE_SPACING_Y,
      };
    });
    return {
      nodes,
      entry_node_id: response.definition.entry_node_id,
    };
  }

  /**
   * Converts UI definition back to API definition (strips x/y from nodes,
   * but includes them as a separate `layout` map keyed by node_id).
   */
  toApiDefinition(def: IvrDefinitionUI): IvrDefinition {
    const layout: Record<string, { x: number; y: number }> = {};
    def.nodes.forEach((n) => {
      layout[n.node_id] = { x: n.x, y: n.y };
    });
    return {
      entry_node_id: def.entry_node_id,
      nodes: def.nodes.map(({ x: _x, y: _y, ...rest }) => rest),
      layout,
    };
  }

  generateNodeId(): string {
    return `node-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  }
}
