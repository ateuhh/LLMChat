// Top-level build file where you can add configuration options common to all sub-projects/modules.
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Exec>("pushRepo") {
    workingDir = rootDir

    // Commit message from -Pm="...", or default
    val msg = (project.findProperty("m") as String?) ?: "chore: updates from Android Studio"

    // Resolve bash executable (works on macOS/Linux; on Windows requires Git Bash in PATH)
    val bash: String = System.getenv("BASH")
        ?: if (OperatingSystem.current().isWindows) "bash" else "/bin/bash"

    // Absolute path to the script
    val script = rootDir.resolve("tools").resolve("push.sh").absolutePath

    commandLine(bash, script, msg)
}

tasks.register<Exec>("updateCommitsAndPush") {
    workingDir = rootDir
    val shell = if (OperatingSystem.current().isWindows) "bash" else "/bin/sh"
    commandLine(shell, "${rootDir}/tools/update_commits_and_push.sh")
    // Пример проброса переменных в скрипт:
    // environment("AUTHOR", "you@example.com")
    // environment("LIMIT", "100")
    // environment("SINCE", "2025-06-01")
}