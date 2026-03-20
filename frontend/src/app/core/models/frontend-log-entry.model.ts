export type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';

export interface FrontendLogEntry {
  level: LogLevel;
  message: string;
  logger: string;
  stackTrace?: string;
  tenantId?: string;
  userId?: string;
  timestamp: number;
}
