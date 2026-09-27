package org.jetbrains.letsPlot.compose.graphite.runtime

internal data class GraphiteCompatibilityInput(
    val osName: String,
    val osArch: String,
    val javaMajor: Int,
    val markerSchema: Int,
    val markerEligible: Boolean,
    val markerComposeVersion: String,
    val markerSkikoVersion: String,
    val markerProfile: String
)

internal data class GraphiteCompatibilityDecision(
    val allowed: Boolean,
    val reason: String,
    val input: GraphiteCompatibilityInput
)

internal object GraphiteRuntimeCompatibility {
    const val EXPECTED_MARKER_SCHEMA = 1
    const val EXPECTED_COMPOSE_VERSION = "1.13.0-alpha01"
    const val EXPECTED_SKIKO_VERSION = "0.153.0"
    const val EXPECTED_PROFILE = "WINDOWS_X64_JDK21_GRAPHITE_0_153"
    const val EXPECTED_JAVA_MAJOR = 21

    fun evaluateActual(): GraphiteCompatibilityDecision {
        val markerClass = try {
            Class.forName("org.jetbrains.letsPlot.compose.DesktopGraphiteCompatibilityMarker")
        } catch (_: ClassNotFoundException) {
            return evaluate(
                GraphiteCompatibilityInput(
                    osName = System.getProperty("os.name").orEmpty(),
                    osArch = System.getProperty("os.arch").orEmpty(),
                    javaMajor = currentJavaMajor(),
                    markerSchema = -1,
                    markerEligible = false,
                    markerComposeVersion = "MISSING",
                    markerSkikoVersion = "MISSING",
                    markerProfile = "MISSING"
                )
            )
        }

        fun field(name: String): java.lang.reflect.Field =
            markerClass.getDeclaredField(name).apply { isAccessible = true }

        return evaluate(
            GraphiteCompatibilityInput(
                osName = System.getProperty("os.name").orEmpty(),
                osArch = System.getProperty("os.arch").orEmpty(),
                javaMajor = currentJavaMajor(),
                markerSchema = field("SCHEMA").getInt(null),
                markerEligible = field("ELIGIBLE").getBoolean(null),
                markerComposeVersion = field("COMPOSE_VERSION").get(null).toString(),
                markerSkikoVersion = field("COMPILE_SKIKO_VERSION").get(null).toString(),
                markerProfile = field("PROFILE").get(null).toString()
            )
        )
    }

    fun evaluate(input: GraphiteCompatibilityInput): GraphiteCompatibilityDecision {
        val normalizedOs = input.osName.trim().lowercase()
        if (!normalizedOs.startsWith("windows")) {
            return blocked("UNSUPPORTED_OS", input)
        }

        val normalizedArch = input.osArch.trim().lowercase()
        if (normalizedArch !in setOf("amd64", "x86_64")) {
            return blocked("UNSUPPORTED_ARCH", input)
        }

        if (input.javaMajor != EXPECTED_JAVA_MAJOR) {
            return blocked("UNVERIFIED_JAVA", input)
        }

        if (input.markerSchema != EXPECTED_MARKER_SCHEMA) {
            return blocked("FRONTEND_MARKER_SCHEMA_MISMATCH", input)
        }

        if (!input.markerEligible) {
            return blocked("FRONTEND_NOT_GRAPHITE_READY", input)
        }

        if (input.markerComposeVersion != EXPECTED_COMPOSE_VERSION) {
            return blocked("COMPOSE_VERSION_MISMATCH", input)
        }

        if (input.markerSkikoVersion != EXPECTED_SKIKO_VERSION) {
            return blocked("SKIKO_VERSION_MISMATCH", input)
        }

        if (input.markerProfile != EXPECTED_PROFILE) {
            return blocked("COMPATIBILITY_PROFILE_MISMATCH", input)
        }

        return GraphiteCompatibilityDecision(
            allowed = true,
            reason = "VERIFIED_BASELINE",
            input = input
        )
    }

    private fun blocked(
        reason: String,
        input: GraphiteCompatibilityInput
    ): GraphiteCompatibilityDecision =
        GraphiteCompatibilityDecision(
            allowed = false,
            reason = reason,
            input = input
        )

    private fun currentJavaMajor(): Int {
        val specification = System.getProperty("java.specification.version").orEmpty()
        return specification.substringAfterLast('.').toIntOrNull() ?: -1
    }
}
