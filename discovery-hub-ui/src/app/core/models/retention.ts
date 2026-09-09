import { CommunicationType } from './message';

/**
 * Retention is configured per communication type. The period is held in
 * minutes so a demo can set it to something observable (FR-5.1).
 */
export interface RetentionPolicy {
  communicationType: CommunicationType;
  retentionMinutes: number;
  updatedAt: string;
  updatedBy: string;
}

export interface DispositionRun {
  id: string;
  startedAt: string;
  completedAt: string | null;
  trigger: 'SCHEDULED' | 'MANUAL';
  scanned: number;
  deleted: number;
  skippedOnHold: number;
  failed: number;
  /** Sample of affected message IDs, for the demo write-up. */
  deletedSample: string[];
  skippedSample: string[];
}

export interface DashboardCounts {
  totalMessages: number;
  indexedMessages: number;
  emailCount: number;
  chatCount: number;
  withAttachments: number;
  custodians: number;
  activeCases: number;
  totalCases: number;
  activeHolds: number;
  heldMessages: number;
  exportsCompleted: number;
  exportsInFlight: number;
  auditEntries: number;
  disposedMessages: number;
}

export type ServiceHealth = 'UP' | 'DEGRADED' | 'DOWN';

/** Per-service status strip, so a downstream outage is visible (NFR-2). */
export interface ServiceStatus {
  id: string;
  name: string;
  port: number;
  health: ServiceHealth;
  detail: string;
}
