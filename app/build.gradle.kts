import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    jacoco
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

jacoco {
    toolVersion = "0.8.15"
}

android {
    testCoverage {
        jacocoVersion = "0.8.15"
    }
    namespace = "ch.snapper.android"
    compileSdk { version = release(37) }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ch.snapper.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"http://10.0.2.2:8000\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "DEFAULT_BACKEND_URL", "\"\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "pl")
    }

    bundle {
        language { enableSplit = false }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

abstract class CombinedCoverageReport : JacocoReport() {
    @get:Internal
    abstract val compiledJars: ListProperty<RegularFile>

    @get:Internal
    abstract val compiledDirectories: ListProperty<Directory>
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        val report = tasks.register<CombinedCoverageReport>("combinedDebugCoverageReport") {
            group = "verification"
            description = "Combine JVM and device execution data against the original application classes."
            dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")
            classDirectories.from(compiledJars, compiledDirectories)
            sourceDirectories.from(variant.sources.java?.all, variant.sources.kotlin?.all)
            val unitData = fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage/debugUnitTest")) {
                include("**/*.exec")
            }
            val deviceData = fileTree(layout.buildDirectory.dir("outputs/code_coverage/debugAndroidTest/connected")) {
                include("**/*.ec")
            }
            executionData.from(unitData, deviceData)
            doFirst {
                check(!unitData.isEmpty) { "JVM coverage execution data is missing." }
                check(!deviceData.isEmpty) { "Device coverage execution data is missing." }
            }
            reports {
                xml.required.set(true)
                xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/combinedDebugCoverageReport/combinedDebugCoverageReport.xml"))
                html.required.set(true)
            }
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(report)
            .toGet(ScopedArtifact.CLASSES, CombinedCoverageReport::compiledJars, CombinedCoverageReport::compiledDirectories)
    }
}

tasks.register("writeSonarProperties") {
    group = "verification"
    description = "Provide Sonar with the actual compiled application and test classpaths."
    val mainKotlin = tasks.named<KotlinCompile>("compileDebugKotlin")
    val mainJava = tasks.named<JavaCompile>("compileDebugJavaWithJavac")
    val testKotlin = listOf("compileDebugUnitTestKotlin", "compileDebugAndroidTestKotlin")
        .map { tasks.named<KotlinCompile>(it) }
    val testJava = listOf("compileDebugUnitTestJavaWithJavac", "compileDebugAndroidTestJavaWithJavac")
        .map { tasks.named<JavaCompile>(it) }
    dependsOn(mainKotlin, mainJava, testKotlin, testJava)
    val output = layout.buildDirectory.file("sonar/sonar-project.properties")
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        val properties = Properties()
        rootProject.file("sonar-project.properties").inputStream().use(properties::load)
        val mainLibraries = files(mainKotlin.get().libraries, mainJava.get().classpath,
            mainJava.get().options.bootstrapClasspath)
        val testLibraries = files(mainLibraries, testKotlin.map { it.get().libraries },
            testJava.map { it.get().classpath }, testJava.mapNotNull { it.get().options.bootstrapClasspath })
        val mainBinaries = files(mainKotlin.get().destinationDirectory, mainJava.get().destinationDirectory)
        val testBinaries = files(testKotlin.map { it.get().destinationDirectory },
            testJava.map { it.get().destinationDirectory })
        mapOf(
            "sonar.java.libraries" to mainLibraries,
            "sonar.java.test.libraries" to testLibraries,
            "sonar.java.binaries" to mainBinaries,
            "sonar.java.test.binaries" to testBinaries,
        ).forEach { (key, paths) ->
            val existingPaths = paths.files.filter { it.exists() }.sortedBy { it.absolutePath }
            check(existingPaths.isNotEmpty()) { "No compiled paths available for $key." }
            properties.setProperty(key, existingPaths.joinToString(",") { it.absolutePath })
        }
        output.get().asFile.apply {
            parentFile.mkdirs()
            outputStream().use { properties.store(it, "Resolved Android compile classpaths") }
        }
    }
}
