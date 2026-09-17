package com.matrix.agent.client;

/** Pure policy extracted from Binder transport so compatibility rules have JVM coverage. */
final class ContractNegotiationPolicy {
    private ContractNegotiationPolicy() { }

    static boolean isCompatible(int serverMajor, int minimumMinor, int maximumMinor,
            String serverHash, int clientMajor, int clientMinor, String clientHash) {
        return serverMajor == clientMajor
                && clientMinor >= minimumMinor
                && clientMinor <= maximumMinor
                && clientHash != null
                && clientHash.equals(serverHash);
    }
}
