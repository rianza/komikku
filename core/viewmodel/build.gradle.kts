plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
}

android {
    namespace = "mihon.core.viewmodel"
}

dependencies {
    implementation(androidx.lifecycle.viewmodel)
}
