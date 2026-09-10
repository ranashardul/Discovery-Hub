package com.stown.ingestion.service;

/**
 * Raised when a disposition run is requested while disposition is switched off.
 *
 * <p>Surfaces as HTTP 409. Disposition destroys data, so it is opt-in per
 * environment via {@code app.retention.enabled}. An HTTP endpoint must not be
 * a way around that switch: an environment that deliberately has disposition
 * disabled should not be able to delete its corpus because someone called an
 * API.
 */
public class DispositionDisabledException extends RuntimeException {

    public DispositionDisabledException() {
        super("Disposition is disabled on this environment (app.retention.enabled=false)");
    }
}
