package com.matrix.agent.client;

import android.os.IBinder;

/**
 * Optional root-service discovery port for OEM/platform integrations.
 *
 * <p>The SDK deliberately has no dependency on hidden {@code ServiceManager} APIs. Regular
 * APKs use the explicit {@code MatrixAgentManagerService} binding path. An OEM component that is
 * both allowed and responsible for querying its platform service registry may inject this port
 * when creating {@link MatrixAgent}; that platform-only code stays outside the published SDK.
 */
@FunctionalInterface
public interface ServiceDiscovery {

    /** No platform registry is available; {@link MatrixAgent} will use explicit binding. */
    ServiceDiscovery NONE = serviceName -> null;

    /** Returns the root Binder for {@code serviceName}, or {@code null} when it is not ready. */
    IBinder findService(String serviceName);
}
