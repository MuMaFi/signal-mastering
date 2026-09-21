plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

/**
 * The harness compiles the app's real DSP and inference sources — not a copy — so a
 * passing run says something about the code that ships in the APK. Only the files that
 * touch the Android framework are left out.
 */
sourceSets["main"].kotlin {
    srcDir("../../app/src/main/java")
    exclude(
        "app/signal/isolate/MainActivity.kt",
        "app/signal/isolate/ui/**",
        "app/signal/isolate/work/**",
        "app/signal/isolate/audio/AudioDecoder.kt",
        "app/signal/isolate/model/ModelManager.kt",
        "app/signal/isolate/model/DeviceCapability.kt",
    )
}

dependencies {
    implementation(libs.onnxruntime.jvm)
}

application {
    mainClass.set("verify.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
