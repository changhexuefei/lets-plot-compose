package org.jetbrains.letsPlot.compose.graphite.runtime

import org.jetbrains.letsPlot.commons.registration.Registration
import probe.PersistentGraphiteShadowBackend
import probe.installInternalRenderer
import probe.preloadDirectVulkan

/**
 * Probe-only optional runtime bootstrap loaded reflectively by lets-plot-compose-desktop.
 *
 * The production frontend never has a static dependency on this class. When the
 * experimental graphite-offscreen render path is explicitly requested, the
 * frontend attempts to load this class once. If the optional runtime is absent,
 * native canvas remains the effective path.
 */
object GraphiteRuntimeBootstrap {
    private val lock = Any()

    @Volatile
    private var registration: Registration? = null

    @Volatile
    private var shutdownHookRegistered = false

    @JvmStatic
    fun activate(): Boolean {
        registration?.let {
            System.setProperty(ACTIVATION_PROPERTY, "ACTIVE")
            System.setProperty(BACKEND_PROPERTY, "PERSISTENT_GRAPHITE_VULKAN")
            return true
        }

        return synchronized(lock) {
            registration?.let {
                System.setProperty(ACTIVATION_PROPERTY, "ACTIVE")
                System.setProperty(BACKEND_PROPERTY, "PERSISTENT_GRAPHITE_VULKAN")
                return@synchronized true
            }

            val compatibility = try {
                GraphiteRuntimeCompatibility.evaluateActual()
            } catch (t: Throwable) {
                System.setProperty(COMPATIBILITY_PROPERTY, "BLOCKED")
                System.setProperty(COMPATIBILITY_REASON_PROPERTY, "MARKER_READ_FAILURE")
                System.setProperty(
                    ACTIVATION_PROPERTY,
                    "BLOCKED:MARKER_READ_FAILURE"
                )
                return@synchronized false
            }

            publishCompatibility(compatibility)
            if (!compatibility.allowed) {
                System.setProperty(
                    ACTIVATION_PROPERTY,
                    "BLOCKED:" + compatibility.reason
                )
                return@synchronized false
            }

            try {
                val evidence = linkedMapOf<String, String>()
                preloadDirectVulkan(evidence)

                val backend = PersistentGraphiteShadowBackend(evidence)
                val installed = installInternalRenderer(backend, evidence)
                registration = installed

                if (!shutdownHookRegistered) {
                    Runtime.getRuntime().addShutdownHook(
                        Thread(
                            {
                                synchronized(lock) {
                                    registration?.dispose()
                                    registration = null
                                }
                            },
                            "lets-plot-graphite-runtime-shutdown"
                        )
                    )
                    shutdownHookRegistered = true
                }

                System.setProperty(ACTIVATION_PROPERTY, "ACTIVE")
                System.setProperty(BACKEND_PROPERTY, "PERSISTENT_GRAPHITE_VULKAN")
                true
            } catch (t: Throwable) {
                System.setProperty(
                    ACTIVATION_PROPERTY,
                    "FAILED:" + (t::class.simpleName ?: "Throwable")
                )
                false
            }
        }
    }

    private fun publishCompatibility(
        decision: GraphiteCompatibilityDecision
    ) {
        System.setProperty(
            COMPATIBILITY_PROPERTY,
            if (decision.allowed) "PASS" else "BLOCKED"
        )
        System.setProperty(COMPATIBILITY_REASON_PROPERTY, decision.reason)
        System.setProperty(
            COMPATIBILITY_COMPOSE_PROPERTY,
            decision.input.markerComposeVersion
        )
        System.setProperty(
            COMPATIBILITY_SKIKO_PROPERTY,
            decision.input.markerSkikoVersion
        )
        System.setProperty(
            COMPATIBILITY_PROFILE_PROPERTY,
            decision.input.markerProfile
        )
        System.setProperty(
            COMPATIBILITY_PLATFORM_PROPERTY,
            decision.input.osName + "/" + decision.input.osArch
        )
        System.setProperty(
            COMPATIBILITY_JAVA_PROPERTY,
            decision.input.javaMajor.toString()
        )
    }

    private const val ACTIVATION_PROPERTY =
        "letsplot.compose.graphite.runtime.activation"
    private const val BACKEND_PROPERTY =
        "letsplot.compose.graphite.runtime.backend"
    private const val COMPATIBILITY_PROPERTY =
        "letsplot.compose.graphite.runtime.compatibility"
    private const val COMPATIBILITY_REASON_PROPERTY =
        "letsplot.compose.graphite.runtime.compatibility.reason"
    private const val COMPATIBILITY_COMPOSE_PROPERTY =
        "letsplot.compose.graphite.runtime.compatibility.compose"
    private const val COMPATIBILITY_SKIKO_PROPERTY =
        "letsplot.compose.graphite.runtime.compatibility.skiko"
    private const val COMPATIBILITY_PROFILE_PROPERTY =
        "letsplot.compose.graphite.runtime.compatibility.profile"
    private const val COMPATIBILITY_PLATFORM_PROPERTY =
        "letsplot.compose.graphite.runtime.compatibility.platform"
    private const val COMPATIBILITY_JAVA_PROPERTY =
        "letsplot.compose.graphite.runtime.compatibility.java"
}
