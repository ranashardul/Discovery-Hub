/**
 * Runtime configuration.
 *
 * `useMockBackend` is the single switch between the in-memory prototype
 * backend and the real microservices. With it `false` the app talks HTTP to
 * the services for everything they support, and falls back to the in-memory
 * store only for the capabilities listed in `mockBacked` below.
 *
 * The base URLs assume the dev-server proxy in `proxy.conf.json`, which keeps
 * the browser on one origin and avoids needing CORS on the Spring services.
 */
export const environment = {
  production: false,

  /** Set true to run entirely offline against the in-memory prototype. */
  useMockBackend: false,

  api: {
    ingestion: '/api/ingestion',
    search: '/api/search',
    /** case-hold-service: /api/v1/cases and /api/v1/holds. */
    caseHold: '/api/v1',
    /** export-audit-service: /api/exports and /api/audit, NOT under /api/v1. */
    exportAudit: '/api',
    /** Proxied per service; see proxy.conf.json. */
    health: '/actuator/health',
  },

  /** Poll interval for asynchronous work: hold propagation, export jobs. */
  jobPollIntervalMs: 1500,

  /**
   * Capabilities with no backend endpoint, served from the in-memory store
   * even when useMockBackend is false.
   *
   * They are listed rather than silently faked so a screen can say so, and so
   * the list shrinks visibly as the services grow. Saved searches, the
   * custodian directory, hold scope preview, retention policies, the
   * disposition trigger, the export manifest, audit filtering, attaching
   * custodians to a case, and removing a single evidence item have all since
   * been implemented and are gone from this list.
   */
  mockBacked: {
    /** Removing a single communication from a case is now implemented; this is empty. */
  },
};
