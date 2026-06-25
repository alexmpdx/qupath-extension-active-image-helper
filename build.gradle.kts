plugins {
    // Plain Java extension
    `java-library`
    // Optionally create a shadow/fat jar that bundles up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

qupathExtension {
    name = "qupath-extension-active-image-helper"
    group = "io.github.alexmpdx"
    version = "0.2.1"
    description = "Highlights the active image in the project list and adds convenience actions"
    automaticModule = "io.github.alexmpdx.activeimagehelper"
}

dependencies {
    // QuPath APIs (provided by the running QuPath instance, not bundled)
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // Testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    // Gradle 9 no longer puts the JUnit Platform launcher on the test classpath automatically
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
