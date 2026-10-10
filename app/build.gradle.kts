import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------------------------
// sherpa-onnx: the on-device inference runtime (ONNX Runtime + espeak-ng phonemizer + KittenTTS
// front end). It is published as a plain .aar on GitHub releases rather than on Maven Central, so
// the build fetches it into app/libs/ once (the file is git-ignored) and uses it as a local file
// dependency. The download happens at configuration time so that Android Studio's Gradle sync,
// which runs no tasks, can resolve the dependency on a fresh clone.
// ---------------------------------------------------------------------------------------------
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxAar: File = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaOnnxVersion.aar").asFile
val sherpaOnnxAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
// Optional integrity pin. Fill in the output of `sha256sum app/libs/sherpa-onnx-1.13.8.aar` to
// have every build verify the library; empty skips the check.
val sherpaOnnxAarSha256 = ""

fun downloadFile(url: String, target: File) {
    target.parentFile.mkdirs()
    val partial = File(target.parentFile, target.name + ".part")
    val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.connectTimeout = 30_000
    connection.readTimeout = 120_000
    connection.setRequestProperty("User-Agent", "kitten-tts-android-build")
    try {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw GradleException("HTTP $code while downloading $url")
        }
        connection.inputStream.use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
    } catch (e: Exception) {
        partial.delete()
        throw e
    } finally {
        connection.disconnect()
    }
    if (partial.length() == 0L) {
        partial.delete()
        throw GradleException("Downloaded file is empty: $url")
    }
    target.delete()
    if (!partial.renameTo(target)) {
        throw GradleException("Could not move ${partial.name} to ${target.name}")
    }
}

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

if (!sherpaOnnxAar.exists()) {
    logger.lifecycle("Downloading $sherpaOnnxAarUrl -> ${sherpaOnnxAar.absolutePath}")
    downloadFile(sherpaOnnxAarUrl, sherpaOnnxAar)
    logger.lifecycle("Downloaded ${sherpaOnnxAar.name} (${sherpaOnnxAar.length() / (1024 * 1024)} MB)")
}
if (sherpaOnnxAarSha256.isNotEmpty()) {
    val actual = sha256Of(sherpaOnnxAar)
    if (!actual.equals(sherpaOnnxAarSha256, ignoreCase = true)) {
        throw GradleException(
            "${sherpaOnnxAar.name} has SHA-256 $actual but $sherpaOnnxAarSha256 was expected; " +
                "delete the file to download it again or update sherpaOnnxAarSha256.",
        )
    }
}

android {
    namespace = "com.lrechis.kittentts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lrechis.kittentts"
        // java.nio.file (used by commons-compress) is only available from API 26 on.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // The sherpa-onnx AAR ships native libraries for arm64-v8a, armeabi-v7a, x86 and x86_64.
        // Keep the ABIs real phones (and the x86_64 emulator) use; drop 32-bit x86 to save space.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // On-device TTS runtime (downloaded above on the first build).
    implementation(files(sherpaOnnxAar))
    // Unpacks the .tar.bz2 model bundles published by the sherpa-onnx project.
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
}
