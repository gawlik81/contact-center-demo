export type DeduplicationMode = 'SKIP' | 'OVERWRITE';
export type ImportJobStatus = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED_PARTIAL' | 'FAILED';

export interface CustomerImportStatus {
  jobId: string;
  status: ImportJobStatus;
  processed: number;
  total: number;
  imported: number;
  updated: number;
  skipped: number;
  failed: number;
  errorFileAvailable: boolean;
}
