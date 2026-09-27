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
                return@synchronized true
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

    private const val ACTIVATION_PROPERTY =
        "letsplot.compose.graphite.runtime.activation"
    private const val BACKEND_PROPERTY =
        "letsplot.compose.graphite.runtime.backend"
}
