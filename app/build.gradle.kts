import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val supportedAbis = arrayOf("arm64-v8a", "x86_64", "armeabi-v7a")
// 本地默认只构建 ARM64；GitHub CI 保留完整架构和通用包供远端验收。
val isGitHubActions = providers.environmentVariable("GITHUB_ACTIONS").orNull == "true"
val buildAbis = if (isGitHubActions) supportedAbis else arrayOf("arm64-v8a")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.spotless)
}

@Suppress("UnstableApiUsage")
android {
    androidResources {
        localeFilters += listOf(
            "zh",
            "zh-rCN",
            "zh-rHK",
            "zh-rTW",
            "ja",
        )
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*buildAbis)
            isUniversalApk = isGitHubActions
        }
    }

    // 仅使用仓库外部提供的签名；缺少全部配置时生成明确的未签名 Release。
    val signingVariables = listOf(
        "EHREADER_KEYSTORE_PATH",
        "EHREADER_STORE_PASSWORD",
        "EHREADER_KEY_ALIAS",
        "EHREADER_KEY_PASSWORD",
    )
    val signingValues = signingVariables.map { providers.environmentVariable(it).orNull }
    require(signingValues.all { it.isNullOrBlank() } || signingValues.all { !it.isNullOrBlank() }) {
        "Release signing requires all four EHREADER signing environment variables."
    }
    val signConfig = if (signingValues.all { !it.isNullOrBlank() }) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(signingValues[0]!!)
            require(storeFile!!.isFile) { "External release keystore does not exist." }
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
            enableV3Signing = true
            enableV4Signing = true
        }
    } else {
        logger.lifecycle("EhReader: release signing is not configured; Release outputs are unsigned.")
        null
    }

    val commitSha by lazy {
        val stdout = providers.exec {
            commandLine = "git rev-parse --short=7 HEAD".split(' ')
        }.standardOutput
        stdout.asText.get().trim()
    }

    val buildTime by lazy {
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneOffset.UTC)
        formatter.format(Instant.now())
    }

    defaultConfig {
        applicationId = "com.haorengg12.ehreader"
        // 新包名使用独立版本序列，后续每次发布必须严格递增。
        versionCode = 10815
        versionName = "1.8.15"
        buildConfigField("String", "VERSION_CODE", "\"${defaultConfig.versionCode}\"")
        buildConfigField("String", "COMMIT_SHA", "\"$commitSha\"")
        ndk {
            debugSymbolLevel = "FULL"
        }
        externalNativeBuild {
            cmake {
                abiFilters.addAll(buildAbis)
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = File("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable.add("MissingTranslation")
    }

    packaging {
        resources {
            excludes += "/META-INF/**"
            excludes += "/kotlin/**"
            excludes += "**.txt"
            excludes += "**.bin"
        }
    }

    dependenciesInfo.includeInApk = false

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signConfig
            buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "BUILD_TIME", "\"\"")
        }
    }

    // 快捷方式目标跟随最终包名，兼容带 .debug 后缀的构建。
    applicationVariants.all {
        resValue("string", "shortcut_target_package", applicationId)
    }

    buildFeatures {
        buildConfig = true
    }

    namespace = "com.hippo.ehviewer"
}

dependencies {
    testImplementation(libs.junit)
    // https://developer.android.com/jetpack/androidx/releases/activity
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.core)

    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.fragment)
    // https://developer.android.com/jetpack/androidx/releases/lifecycle
    implementation(libs.androidx.lifecycle.process)

    // https://developer.android.com/jetpack/androidx/releases/paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)

    // https://developer.android.com/jetpack/androidx/releases/room
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.paging)

    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.drawer)
    implementation(libs.material)

    // https://square.github.io/okhttp/changelogs/changelog/
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp.coroutines)
    implementation(libs.okhttp.tls)

    implementation(libs.okio.jvm)

    // https://github.com/RikkaApps/RikkaX
    implementation(libs.bundles.rikkax)

    // https://coil-kt.github.io/coil/changelog/
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.ktor.utils)
    implementation(libs.jsoup)

    coreLibraryDesugaring(libs.desugar)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        progressiveMode = true
        optIn.addAll(
            "coil3.annotation.ExperimentalCoilApi",
            "kotlin.contracts.ExperimentalContracts",
            "kotlin.time.ExperimentalTime",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.coroutines.InternalCoroutinesApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

configurations.all {
    exclude("dev.rikka.rikkax.appcompat", "appcompat")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

val ktlintVersion = libs.ktlint.get().version

spotless {
    kotlin {
        // https://github.com/diffplug/spotless/issues/111
        target("src/**/*.kt")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        ktlint(ktlintVersion)
    }
}
