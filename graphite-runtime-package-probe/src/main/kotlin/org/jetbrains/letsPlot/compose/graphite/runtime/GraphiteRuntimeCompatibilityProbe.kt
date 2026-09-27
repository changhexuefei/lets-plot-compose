package org.jetbrains.letsPlot.compose.graphite.runtime

import java.io.File

fun main() {
    val outputDir = File(
        System.getenv("GRAPHITE_COMPATIBILITY_OUTPUT_DIR")
            ?: "graphite-runtime-package-probe/build/compatibility"
    ).apply { mkdirs() }

    val expected = GraphiteCompatibilityInput(
        osName = "Windows 11",
        osArch = "amd64",
        javaMajor = 21,
        markerSchema = 1,
        markerEligible = true,
        markerComposeVersion = "1.13.0-alpha01",
        markerSkikoVersion = "0.153.0",
        markerProfile = "WINDOWS_X64_JDK21_GRAPHITE_0_153"
    )

    check(GraphiteRuntimeCompatibility.evaluate(expected).allowed)

    val rejected = linkedMapOf(
        "unsupported_os" to expected.copy(osName = "Linux"),
        "unsupported_arch" to expected.copy(osArch = "aarch64"),
        "unverified_java" to expected.copy(javaMajor = 17),
        "marker_schema" to expected.copy(markerSchema = 2),
        "frontend_not_ready" to expected.copy(markerEligible = false),
        "compose_mismatch" to expected.copy(markerComposeVersion = "1.12.1"),
        "skiko_mismatch" to expected.copy(markerSkikoVersion = "0.152.0-alpha02"),
        "profile_mismatch" to expected.copy(markerProfile = "UNVERIFIED")
    )

    val reasons = linkedMapOf<String, String>()
    rejected.forEach { (name, input) ->
        val decision = GraphiteRuntimeCompatibility.evaluate(input)
        check(!decision.allowed) { "Compatibility case unexpectedly allowed: $name" }
        reasons[name] = decision.reason
    }

    val manifest = outputDir.resolve("graphite-runtime-compatibility-matrix.txt")
    manifest.writeText(
        buildString {
            appendLine("schema=1")
            appendLine("result=COMPOSE_PLOTPANEL_GRAPHITE_RUNTIME_COMPATIBILITY_GATE_EVIDENCE_CAPABLE")
            appendLine("verified.os=Windows")
            appendLine("verified.arch=x86_64")
            appendLine("verified.jdk=21")
            appendLine("verified.compose=1.13.0-alpha01")
            appendLine("verified.skiko=0.153.0")
            appendLine("verified.profile=WINDOWS_X64_JDK21_GRAPHITE_0_153")
            appendLine("verified.activation=ALLOW")
            appendLine("unverified.activation=BLOCK")
            appendLine("case.unsupported_os=${reasons.getValue("unsupported_os")}")
            appendLine("case.unsupported_arch=${reasons.getValue("unsupported_arch")}")
            appendLine("case.unverified_java=${reasons.getValue("unverified_java")}")
            appendLine("case.marker_schema=${reasons.getValue("marker_schema")}")
            appendLine("case.frontend_not_ready=${reasons.getValue("frontend_not_ready")}")
            appendLine("case.compose_mismatch=${reasons.getValue("compose_mismatch")}")
            appendLine("case.skiko_mismatch=${reasons.getValue("skiko_mismatch")}")
            appendLine("case.profile_mismatch=${reasons.getValue("profile_mismatch")}")
            appendLine("production.renderer.default=NATIVE_CANVAS")
            appendLine("renderer.adoption=EXPERIMENTAL_OPT_IN")
            appendLine("public.api.change=NONE")
        }
    )

    println(manifest.readText())
}
