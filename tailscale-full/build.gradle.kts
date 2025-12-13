plugins {
	id("com.android.library")
	kotlin("android")
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.kotlin.compose)
}

android {
	namespace = "com.tailscale.ipn"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = 26
		consumerProguardFiles("consumer-rules.pro")
		buildConfigField("boolean", "USE_GOOGLE_DNS_FALLBACK", "true")
		buildConfigField("String", "VERSION_NAME", "\"1.0.0\"")
	}

	buildFeatures {
		compose = true
		buildConfig = true
	}

	composeOptions {
		kotlinCompilerExtensionVersion = libs.versions.androidx.compose.ui.get()
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_1_8
		targetCompatibility = JavaVersion.VERSION_1_8
	}
}

dependencies {
	// Core deps aligned with project versions
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kotlinx.coroutines)
	implementation(libs.androidx.core)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.lifecycle.runtime)
	implementation(libs.androidx.lifecycle.viewmodel)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.lifecycle.service)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.work.runtime)
	implementation(libs.androidx.startup)
	implementation(libs.androidx.window)
	implementation(libs.androidx.compose.foundation)
	implementation(libs.androidx.compose.material)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.ui.tooling)
	implementation(libs.androidx.compose.navigation)

	implementation(libs.accompanist.permissions)
	implementation(libs.coil.core)
	implementation(libs.coil.compose)
	// Coil 2.x for AsyncImage used in upstream code
	implementation("io.coil-kt:coil-compose:2.6.0")
	implementation(libs.androidx.browser)
	implementation(libs.androidx.core.splashscreen)
	implementation(libs.zxing.core)
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	implementation("androidx.security:security-crypto:1.1.0-alpha06")
	implementation("androidx.datastore:datastore-preferences:1.1.1")

	// Timber for logging
	implementation(libs.timber)

	// libtailscale AAR - compileOnly here, actual dependency provided by app module
	compileOnly(files("libs/libtailscale.aar"))
}
