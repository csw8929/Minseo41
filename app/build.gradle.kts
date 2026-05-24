plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.kotlin.serialization)
}

val appVersion = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.split(".")
val computedVersionCode =
    versionParts[0].toInt() * 1000 +
    versionParts[1].toInt() * 100 +
    versionParts[2].toInt() * 10 +
    versionParts[3].toInt()

android {
    namespace = "com.minseo41.subfeed"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minseo41.subfeed"
        minSdk = 26
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "Minseo41.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // NewPipeExtractor 가 protobuf 등 transitive 로 가져오는 META-INF 충돌 회피
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/proguard/**",
            )
        }
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // ExoPlayer / Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // WorkManager + Hilt-Work
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // Google Sign-In
    implementation(libs.play.services.auth)

    // OkHttp — RSS 파싱 + PoToken WebView 의 BotGuard 서비스 호출용
    implementation(libs.okhttp)

    // NewPipeExtractor — YouTube stream URL 추출 + n-param/signature deobfuscation + DASH 빌드.
    // PoToken은 우리 PoTokenWebView 를 NewPipe 의 PoTokenProvider 에 plug 해서 공급.
    // protobuf-javalite 를 exclude 해서 Firebase 의 protolite-well-known-types 와 duplicate class 충돌 회피.
    // NewPipe 의 proto 사용 코드는 외부 클라이언트(NewPipeService 내부 일부)에 한정되어 stream 추출 path 와 무관.
    implementation(libs.newpipe.extractor) {
        exclude(group = "com.google.protobuf", module = "protobuf-javalite")
    }

    // Image loading
    implementation(libs.coil.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // JSON
    implementation(libs.kotlinx.serialization.json)
}
