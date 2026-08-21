import mihon.buildlogic.tasks.LocalesConfigTask
import mihon.buildlogic.tasks.getLocalesConfigTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform")
    id("mihon.kotlin.multiplatform")
    alias(libs.plugins.moko)
    id("com.github.ben-manes.versions")
}

kotlin {
    applyDefaultHierarchyTemplate()

    android {
        namespace = "tachiyomi.i18n"

        lint {
            disable.addAll(listOf("MissingTranslation", "ExtraTranslation"))
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.core)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

multiplatformResources {
    resourcesPackage.set("tachiyomi.i18n")
}

val generateLocalesConfig = getLocalesConfigTask()

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateLocalesConfig,
            LocalesConfigTask::outputResourceDir,
        )
    }
}
