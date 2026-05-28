import { DispositionToneApi } from './custom-disposition.model';

export interface DispositionSetItem {
  id: string;
  dispositionCode: string;
  label: string;
  tone: DispositionToneApi;
  ordinal: number;
}

export interface DispositionSet {
  id: string;
  name: string;
  description?: string;
  itemCount: number;
  createdAt: string;
}

export interface DispositionSetDetail extends DispositionSet {
  items: DispositionSetItem[];
}

export interface CreateDispositionSetRequest {
  name: string;
  description?: string;
}

export interface UpdateDispositionSetRequest {
  name: string;
  description?: string;
}

export interface CreateDispositionSetItemRequest {
  dispositionCode: string;
  label: string;
  tone: DispositionToneApi;
  ordinal: number;
}

export interface UpdateDispositionSetItemRequest {
  label: string;
  tone: DispositionToneApi;
  ordinal: number;
}

export interface ApplySetResponse {
  copied: number;
  skipped: number;
  message: string;
}
