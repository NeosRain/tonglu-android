buildscript {
    dependencies {
        // AGP 9 has built-in Kotlin support; pin KGP to the version used by Miuix 0.9.4.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
