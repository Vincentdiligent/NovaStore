plugins {
    alias(libs.plugins.kotlin.jvm)
    id("com.google.protobuf") version "0.9.4"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.okhttp)
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation(libs.junit)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    // The "java" builtin is the default and is already registered by the
    // plugin; declaring `builtins { id("java") } }` again fails with
    // "Cannot add a PluginOptions with name 'java' ... already exists".
}

sourceSets {
    main {
        java.srcDirs("src/main/java", "build/generated/source/proto/main/java")
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("nova.live", System.getProperty("nova.live") ?: "false")
    systemProperty("nova.profile", System.getProperty("nova.profile") ?: "px_3a.properties")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
