import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cloudin.refract"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.0")
}

// 发布到本地 Maven（~/.m2），其他项目可以直接 implementation("com.github.cloudin:refract:1.0.0")
apply(plugin = "maven-publish")
afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("release") {
                groupId = "com.github.cloudin"
                artifactId = "refract"
                version = "1.0.0"
                afterEvaluate { from(components["release"]) }
            }
        }
    }
}
