import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin { compilerOptions.jvmTarget = JvmTarget.JVM_21 }
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val ktorVersion = "3.6.0"
dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("com.auth0:java-jwt:4.6.1")
    implementation("org.postgresql:postgresql:42.7.14")
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jsoup:jsoup:1.23.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test {
    useJUnitPlatform()
    inputs.property("databaseTestsEnabled", providers.environmentVariable("LOCATOR_TEST_DATABASE_URL").map { true }.orElse(false))
    inputs.property("databaseTestsRequired", providers.environmentVariable("LOCATOR_REQUIRE_DATABASE_TESTS").orElse("false"))
}
