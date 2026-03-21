plugins {
    alias(mihonx.plugins.android.library)
}

android {
    namespace = "com.murgupluoglu.flagkit"
}

dependencies {
    implementation(projects.core.common)
}
