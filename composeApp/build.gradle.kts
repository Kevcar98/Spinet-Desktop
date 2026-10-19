import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val fxClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val aarch = arch.contains("aarch64") || arch.contains("arm64")
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (aarch) "mac-aarch64" else "mac"
        else -> if (aarch) "linux-aarch64" else "linux"
    }
}
val fxVersion = libs.versions.javafx.get()

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation("dev.spinet:extension-api")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings.no.arg)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)

            implementation("com.squareup.okio:okio:3.9.0")
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.jaudiotagger)
            implementation("com.tianscar.javasound:javasound-aac:0.9.8")

            implementation("com.squareup.okhttp3:okhttp:4.12.0")

            implementation("com.russhwolf:multiplatform-settings:1.3.0")

            implementation("net.java.dev.jna:jna:5.14.0")
            implementation("net.java.dev.jna:jna-platform:5.14.0")

            implementation("org.openjfx:javafx-base:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-graphics:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-media:$fxVersion:$fxClassifier")
            implementation("org.openjfx:javafx-swing:$fxVersion:$fxClassifier")
        }
    }
}

val msiUpgradeUuid = "838d2ca0-f454-4b48-b699-7013b2c9a710"
val appVersion = "0.0.2"

val generateAppVersion = tasks.register("generateAppVersion") {
    val outDir = layout.buildDirectory.dir("generated/appversion")
    val version = appVersion
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("dev/spinet/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package dev.spinet

            /** Generated from build.gradle.kts - do not edit. */
            const val APP_VERSION: String = "$version"
            """.trimIndent()
        )
    }
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateAppVersion)
}

val packagingJdk: String = System.getenv("PACKAGING_JDK")
    ?: "C:\\Program Files\\Java\\jdk-21.0.11"

compose.desktop {
    application {
        mainClass = "dev.spinet.MainKt"
        javaHome = packagingJdk

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Spinet"
            packageVersion = appVersion
            vendor = "Spinet"

            windows {
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = msiUpgradeUuid

                iconFile.set(rootProject.file("installer/Spinet.ico"))
            }
        }
    }
}

tasks.register<JavaExec>("uiShots") {
    group = "verification"
    description = "Render the mini player and playback bar to build/screenshots"

    val desktopMain = kotlin.targets.getByName("desktop").compilations.getByName("main")
    dependsOn(desktopMain.compileTaskProvider)
    classpath = files(desktopMain.output.allOutputs, desktopMain.runtimeDependencyFiles)

    mainClass.set("dev.spinet.tools.UiShotsKt")
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)

    if (project.hasProperty("art")) {
        systemProperty("shot.art", project.property("art") as String)
    }
}

tasks.register<Exec>("packageInstaller") {
    group = "compose desktop"
    description = "Windows setup .exe via Inno Setup (update-aware, clean UI)"
    dependsOn("createDistributable")

    val iscc = System.getenv("INNO_SETUP_ISCC")
        ?: listOf(
            "${System.getenv("LOCALAPPDATA")}\\Programs\\Inno Setup 6\\ISCC.exe",
            "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe",
            "C:\\Program Files\\Inno Setup 6\\ISCC.exe"
        ).firstOrNull { File(it).exists() }
        ?: "C:\\Program Files (x86)\\Inno Setup 6\\ISCC.exe"
    val script = rootProject.file("installer/Spinet.iss")

    commandLine(iscc, "/DAppVersion=$appVersion", script.absolutePath)

    doFirst {
        check(File(iscc).exists()) {
            "Inno Setup not found at $iscc: install it (winget install JRSoftware.InnoSetup) " +
                "or set INNO_SETUP_ISCC to the ISCC.exe path."
        }
    }
}

val portableMarker = layout.buildDirectory.file("tmp/portable/portable.txt")
val portableReadme = layout.buildDirectory.file("tmp/portable/UPDATING.txt")
val preparePortableMarker = tasks.register("preparePortableMarker") {
    outputs.file(portableMarker)
    outputs.file(portableReadme)
    doLast {
        portableMarker.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "Portable mode marker: keep this file.\r\n" +
                    "It tells Spinet to store settings in the data folder next to it,\r\n" +
                    "so the whole app runs from this folder and leaves nothing on the PC.\r\n"
            )
        }
        portableReadme.get().asFile.writeText(
            "Updating the portable Spinet\r\n" +
                "=================================\r\n\r\n" +
                "Your playlists, liked songs, server settings, themes and everything else\r\n" +
                "live in the  data  folder here, NOT inside the app folder.\r\n\r\n" +
                "To update to a newer version:\r\n" +
                "  1. Download the new Spinet-Portable zip.\r\n" +
                "  2. Delete (or replace) ONLY the  app  folder here.\r\n" +
                "  3. Copy the new  app  folder into this folder.\r\n" +
                "  4. Keep  portable.txt  and the  data  folder as they are.\r\n\r\n" +
                "That's it: everything from the previous version is kept.\r\n\r\n" +
                "Tip: to back up your setup, copy the  data  folder somewhere safe.\r\n"
        )
    }
}

tasks.register<Zip>("packagePortable") {
    group = "compose desktop"
    description = "Portable zip (run from anywhere, settings stay in ./data, update-safe)"
    dependsOn("createDistributable", preparePortableMarker)

    from(layout.buildDirectory.dir("compose/binaries/main/app/Spinet")) {
        into("app")
    }

    from(portableMarker)
    from(portableReadme)
    archiveFileName.set("Spinet-Portable-$appVersion.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/portable"))
}

tasks.register<Exec>("packageMsiWithPrompt") {
    group = "compose desktop"
    description = "Windows MSI with a default-on desktop-shortcut checkbox"
    dependsOn("createDistributable")
    dependsOn(":unzipWix")

    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/Spinet")
    val outDir = layout.buildDirectory.dir("compose/binaries/main/msi")
    val jpackage = File(packagingJdk, "bin/jpackage.exe")

    val wixDir = rootProject.layout.buildDirectory.dir("wix311")
    doFirst {
        environment(
            "PATH",
            wixDir.get().asFile.absolutePath + File.pathSeparator + (System.getenv("PATH") ?: "")
        )
    }

    commandLine(
        jpackage.absolutePath,
        "--type", "msi",
        "--app-image", appImage.get().asFile.absolutePath,
        "--app-version", appVersion,
        "--vendor", "Spinet",
        "--icon", rootProject.file("installer/Spinet.ico").absolutePath,
        "--win-menu",
        "--win-shortcut",
        "--win-shortcut-prompt",
        "--win-dir-chooser",
        "--win-upgrade-uuid", msiUpgradeUuid,
        "--dest", outDir.get().asFile.absolutePath
    )
}

tasks.register("prepareRelease") {
    group = "compose desktop"
    description = "MSI + portable zip + SHA256SUMS in build/release, ready to upload"
    dependsOn("packageMsiWithPrompt", "packagePortable")

    val msiDir = layout.buildDirectory.dir("compose/binaries/main/msi")
    val zipDir = layout.buildDirectory.dir("compose/binaries/main/portable")
    val outDir = layout.buildDirectory.dir("release")
    val version = appVersion

    inputs.dir(msiDir)
    inputs.dir(zipDir)
    outputs.dir(outDir)

    doLast {
        val dest = outDir.get().asFile

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        dest.deleteRecursively()
        dest.mkdirs()
        val built = listOf(
            File(msiDir.get().asFile, "Spinet-$version.msi"),
            File(zipDir.get().asFile, "Spinet-Portable-$version.zip")
        )
        val lines = built.map { src ->
            check(src.isFile) { "expected ${src.name}: it was not built" }
            val copy = File(dest, src.name)
            src.copyTo(copy, overwrite = true)
            "${sha256(copy)} *${copy.name}"
        }
        File(dest, "SHA256SUMS").writeText(lines.joinToString("\n") + "\n")
        logger.lifecycle("Release files in ${dest.absolutePath}")
    }
}

compose.resources {
    packageOfResClass = "dev.spinet.resources"
    publicResClass = false
}
