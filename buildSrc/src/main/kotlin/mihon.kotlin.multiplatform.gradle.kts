import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import mihon.buildlogic.AndroidConfig
import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("com.android.kotlin.multiplatform.library")

    id("mihon.code.lint")
}

val libs = the<LibrariesForLibs>()

extensions.configure<KotlinMultiplatformExtension> {
    targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
        compileSdk = AndroidConfig.COMPILE_SDK
        minSdk = AndroidConfig.MIN_SDK
        enableCoreLibraryDesugaring = true

        androidResources {
            enable = true
        }

        // Create the host unit test compilation so the auto-generated
        // commonTest source set is not left unused (AGP built-in KMP
        // does not create test compilations unless opted in).
        withHostTest {}

        compilerOptions {
            jvmTarget.set(AndroidConfig.JvmTarget)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
            )

            // Treat all Kotlin warnings as errors (disabled by default)
            // Override by setting warningsAsErrors=true in your ~/.gradle/gradle.properties
            val warningsAsErrors: String? by project
            allWarningsAsErrors.set(warningsAsErrors.toBoolean())
        }
    }
}

dependencies {
    "coreLibraryDesugaring"(libs.desugar)
}
