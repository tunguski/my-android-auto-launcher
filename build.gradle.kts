plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.diffplug.spotless") version "6.25.0"
}

// ───────────────────────────────── formatting ─────────────────────────────────

// The Android Java style, checked with "./gradlew spotlessCheck" and applied with
// "./gradlew spotlessApply". The build itself only checks; nothing is rewritten
// behind the author back.
spotless {
    java {
        target("app/src/**/*.java", "tools/**/*.java")
        googleJavaFormat("1.17.0").aosp().reflowLongStrings()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.0.1")
    }
    format("misc") {
        target("*.md", "docs/**/*.md", ".github/**/*.yml", "**/*.sh")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ──────────────────────────────── documentation ────────────────────────────────

/** The JDK running Gradle; it also runs the single-file documentation tool. */
val javaExecutable: String =
    File(System.getProperty("java.home"), "bin/java").path +
        if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""

/**
 * Renders the user guide into a PDF, pictures and all.
 *
 * The tool is a single source file with no dependencies, run straight from
 * source, so this works the same on a developer machine and in CI.
 */
val userGuidePdf by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Renders docs/USER_GUIDE.md into docs/navi-user-guide.pdf"
    inputs.file(file("docs/USER_GUIDE.md"))
    inputs.dir(file("docs/previews"))
    inputs.file(file("tools/MdPdf.java"))
    outputs.file(file("docs/navi-user-guide.pdf"))
    commandLine(
        javaExecutable,
        "-Djava.awt.headless=true",
        file("tools/MdPdf.java").absolutePath,
        file("docs/USER_GUIDE.md").absolutePath,
        file("docs/navi-user-guide.pdf").absolutePath,
    )
}

/**
 * The same guide as one self-contained HTML page, pictures inlined.
 *
 * One file, so it can be mailed or put on a stick and opened anywhere,
 * including on the head unit, without the previews folder beside it.
 */
val userGuideHtml by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Renders docs/USER_GUIDE.md into docs/navi-user-guide.html"
    inputs.file(file("docs/USER_GUIDE.md"))
    inputs.dir(file("docs/previews"))
    inputs.file(file("tools/MdHtml.java"))
    outputs.file(file("docs/navi-user-guide.html"))
    commandLine(
        javaExecutable,
        file("tools/MdHtml.java").absolutePath,
        file("docs/USER_GUIDE.md").absolutePath,
        file("docs/navi-user-guide.html").absolutePath,
    )
}

val userGuide by tasks.registering {
    group = "documentation"
    description = "Renders the user guide in every published format"
    dependsOn(userGuidePdf, userGuideHtml)
}

/**
 * Overwrites every picture in docs/previews with a fresh screenshot of this
 * build, taken on an attached device or emulator.
 *
 * It needs a 1280x720 landscape device, so it is not part of an ordinary build;
 * CI runs it on an emulator, and so does anyone changing how a screen looks.
 */
val previews by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Refreshes docs/previews from a connected device (1280x720)"
    commandLine("bash", file("tools/screenshots.sh").absolutePath)
}

// The guide is part of the deliverable, so building an APK produces it too.
gradle.projectsEvaluated {
    project(":app").tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }
        .configureEach { finalizedBy(userGuide) }
}
