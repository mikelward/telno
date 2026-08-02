plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

// GitHub Actions sets CI=true. Distinguishes the two builds that will ship (the
// Play AAB and the CI tester APK) from a build made on a developer's machine;
// drives R8, the launcher icon color, the app label, and the debug application ID.
val isCiBuild: Boolean = System.getenv("CI") == "true"

// A locally built debug APK is `.dev`, not `.debug`, so it co-installs beside
// the CI tester build instead of fighting it for one package name. They are
// signed by different keys — CI's stable debug keystore vs. the developer's
// own — so sharing an ID isn't an upgrade, it's an INSTALL_FAILED_UPDATE_
// INCOMPATIBLE that forces an uninstall to switch between them.
val debugApplicationIdSuffix = if (isCiBuild) ".debug" else ".dev"

// Firebase (Crashlytics + Analytics) activates per build: these plugins wire
// the config and mapping upload only when the untracked google-services.json
// is present, so fresh clones and CI build with Firebase dormant.
val firebaseConfigFile = file("google-services.json")
if (firebaseConfigFile.exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
    // The Google Services plugin hard-fails any build whose application ID has
    // no matching client in the json. `app.telno.dev` deliberately has none: a
    // build from a developer's machine should not file crashes and analytics
    // into the shared project alongside real tester data. For that ID, skip the
    // debug-variant processing; telemetry then stays dormant in local builds
    // exactly as it does in a checkout with no config at all. The !isCiBuild
    // clause matters: in CI a missing debug client means the secret is stale,
    // and the plugin's hard failure is the wanted signal.
    if (!isCiBuild && !firebaseConfigFile.readText().contains("\"app.telno$debugApplicationIdSuffix\"")) {
        // Gradle does not delete a disabled task's earlier output, so purge any
        // previously generated Firebase resources ahead of the resource merge —
        // otherwise a stale google_app_id from a tester build would ship in the
        // `.dev` APK and report to the shared project.
        val purgeForeignFirebaseResources = tasks.register<Delete>("purgeDebugGoogleServicesResources") {
            description = "Deletes Firebase resources generated for a different application ID."
            delete(
                layout.buildDirectory.dir("generated/res/processDebugGoogleServices"),
                layout.buildDirectory.dir("generated/res/google-services/debug"),
            )
        }
        afterEvaluate {
            tasks.matching {
                it.name in setOf(
                    "processDebugGoogleServices",
                    "injectCrashlyticsMappingFileIdDebug",
                    "uploadCrashlyticsMappingFileDebug",
                )
            }.configureEach {
                enabled = false
            }
            tasks.matching { it.name == "mergeDebugResources" }.configureEach {
                dependsOn(purgeForeignFirebaseResources)
            }
        }
    }
}

fun gitOutput(vararg args: String, fallback: String): String =
    try {
        val output = providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        output.ifEmpty { fallback }
    } catch (_: Exception) {
        fallback
    }

// Monotonic versionCode as long as main only moves forward; Play rejects an
// AAB whose versionCode is <= the highest already uploaded. CI checks out with
// fetch-depth: 0 so the count isn't truncated by a shallow clone. A CI build
// that can't derive the count fails loudly — silently shipping versionCode 1
// would be rejected by Play as non-monotonic with no hint why — while a local
// build (source drop, no .git) may fall back with a warning, since it never
// ships.
val rawCommitCount = gitOutput("rev-list", "--count", "HEAD", fallback = "")
val gitCommitCount: Int = rawCommitCount.toIntOrNull()
    ?: if (isCiBuild) {
        throw GradleException(
            "versionCode needs the git commit count, but `git rev-list --count HEAD` " +
                "produced '$rawCommitCount'. CI must check out with full history (fetch-depth: 0).",
        )
    } else {
        logger.warn("Telno: no git history available; using fallback versionCode 1 (local build only).")
        1
    }
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown")
// SPEC "Distribution and versioning": versionName is 1.0.<count>+<shortSha>.
val baseVersionName = "1.0"

// Which launcher icon each build wears, resolved at manifest-merge time so a
// phone carrying more than one Telno says which is which from the home screen.
// Only the Play build gets the brand icon; the CI tester (app.telno.debug) gets
// a dark variant and anything built outside CI gets an amber "dev" variant.
val devLauncherIcon = "@mipmap/ic_launcher_dev"
val releaseLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher" else devLauncherIcon
val debugLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher_debug" else devLauncherIcon

// The same distinction in the name under the icon. The shipping label stays a
// string resource so it can be localized; the badged labels are literals — they
// are never translated and never reach a store listing. The in-app title keeps
// reading @string/app_name so recorded screenshots don't churn per environment.
val devAppLabel = "Telno Dev"
val releaseAppLabel = if (isCiBuild) "@string/app_name" else devAppLabel
val debugAppLabel = if (isCiBuild) "Telno Debug" else devAppLabel

android {
    namespace = "app.telno"
    compileSdk = 36

    defaultConfig {
        // Owner decision: matches sibling Simmo's `app.simmo` scheme.
        applicationId = "app.telno"
        // minSdk 34 as a courtesy; designed and tested against Android 16+
        // Pixel and Samsung (SPEC "Devices and compatibility").
        minSdk = 34
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount+$gitShortSha"
    }

    signingConfigs {
        // CI materializes a stable debug keystore from a secret and points
        // DEBUG_KEYSTORE_FILE at it, so successive tester builds carry the same
        // signature and devices install them as updates. Local builds without
        // the env var fall through to AGP's auto-generated debug keystore.
        getByName("debug") {
            val keystorePath = providers.environmentVariable("DEBUG_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("DEBUG_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DEBUG_KEY_ALIAS").getOrElse("androiddebugkey")
                keyPassword = providers.environmentVariable("DEBUG_KEY_PASSWORD").orNull
            }
        }
        // CI materializes the Play upload keystore from a secret. Local builds
        // without RELEASE_KEYSTORE_FILE produce an unsigned release AAB, so
        // forks and fresh clones build cleanly.
        create("release") {
            val keystorePath = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // R8 in CI only: shipping builds are CI-built so they go out
            // minified, while a local release build skips R8 and stays fast.
            isMinifyEnabled = isCiBuild
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (!providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            manifestPlaceholders["launcherIcon"] = releaseLauncherIcon
            manifestPlaceholders["appLabel"] = releaseAppLabel
        }
        debug {
            applicationIdSuffix = debugApplicationIdSuffix
            // Same shrink-only R8 as release, CI-only — so testers and the PR
            // build job exercise the pipeline shipping builds use, while local
            // debug builds skip R8 and stay fast.
            isMinifyEnabled = isCiBuild
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            manifestPlaceholders["launcherIcon"] = debugLauncherIcon
            manifestPlaceholders["appLabel"] = debugAppLabel
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Compiled into every build so the telemetry wiring compiles, but inert
    // (never initialized) unless the build had a google-services.json.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
}
