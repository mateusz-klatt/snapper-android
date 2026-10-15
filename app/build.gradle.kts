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
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

android {
    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
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
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    debugImplementation(libs.compose.ui.tooling.core)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.compose.ui.test.junit4)
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
