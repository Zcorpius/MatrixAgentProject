plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

/**
 * One reproducible non-device quality gate for local work and CI.
 *
 * <p>APK/device checks deliberately remain explicit because they need a connected, correctly
 * signed target. Everything that can be proven from the repository—including every module's
 * tests/lint and the published SDK metadata—must pass here.</p>
 */
tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Runs all non-device checks and verifies the SDK publication is dependency-pure."
    dependsOn(
        ":matrix-agent-service:check",
        ":matrix-agent-service-lib:check",
        ":matrix-agent-launcher:check",
        ":matrix-agent-test:check",
        ":ondevice:check",
        ":matrix-agent-service-lib:verifyPublishedPom",
    )
}
