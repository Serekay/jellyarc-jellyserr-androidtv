package org.jellyfin.androidtv.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
	private const val TAG = "UpdateChecker"
	private const val LATEST_RELEASE_URL = "https://api.github.com/repos/Serekay/jellyfin-jellyserr-tv/releases/latest"
	private const val APK_NAME = "jellyarc-update.apk"

	data class ReleaseInfo(
		val version: String,
		val downloadUrl: String,
		val changelog: String?
	)

	suspend fun fetchLatestRelease(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
		try {
			val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				connectTimeout = 8000
				readTimeout = 8000
				setRequestProperty("Accept", "application/vnd.github+json")
				setRequestProperty("User-Agent", "jellyarc-tv")
			}

			connection.inputStream.buffered().use { stream ->
				val payload = stream.reader().readText()
				val json = JSONObject(payload)
				val tagName = normalizeVersion(json.optString("tag_name"))
				val htmlUrl = json.optString("html_url")
				val apkUrl = findApkUrl(json) ?: htmlUrl
				val changelog = json.optString("body")

				if (tagName.isNotEmpty() && isNewer(tagName, normalizeVersion(currentVersion))) {
					ReleaseInfo(tagName, apkUrl, changelog)
				} else null
			}
		} catch (ex: Exception) {
			Log.w(TAG, "Update check failed", ex)
			null
		}
	}

	private fun findApkUrl(json: JSONObject): String? {
		val assets = json.optJSONArray("assets") ?: return null
		for (i in 0 until assets.length()) {
			val asset = assets.optJSONObject(i) ?: continue
			val name = asset.optString("name")
			val url = asset.optString("browser_download_url")
			if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
				return url
			}
		}
		return null
	}

	private fun normalizeVersion(version: String): String =
		version.removePrefix("v").trim()

	private fun isNewer(latest: String, current: String): Boolean {
		val latestParts = latest.split(".")
		val currentParts = current.split(".")
		val max = maxOf(latestParts.size, currentParts.size)

		for (index in 0 until max) {
			val latestPart = latestParts.getOrNull(index)?.toIntOrNull() ?: 0
			val currentPart = currentParts.getOrNull(index)?.toIntOrNull() ?: 0
			if (latestPart != currentPart) return latestPart > currentPart
		}
		return false
	}


	private fun copyStream(input: InputStream, output: OutputStream, bufferSize: Int = 8 * 1024) {
		val buffer = ByteArray(bufferSize)
		while (true) {
			val read = input.read(buffer)
			if (read <= 0) break
			output.write(buffer, 0, read)
		}
	}

	private fun copyStreamWithProgress(
		input: InputStream,
		output: OutputStream,
		totalBytes: Long,
		onProgress: (Float) -> Unit,
		bufferSize: Int = 8 * 1024
	) {
		val buffer = ByteArray(bufferSize)
		var bytesRead = 0L

		while (true) {
			val read = input.read(buffer)
			if (read <= 0) break
			output.write(buffer, 0, read)
			bytesRead += read

			if (totalBytes > 0) {
				val progress = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
				onProgress(progress)
			}
		}
		onProgress(1f) // Ensure we reach 100%
	}

	suspend fun downloadApkWithProgress(
		context: Context,
		url: String,
		onProgress: (Float) -> Unit
	): File? = withContext(Dispatchers.IO) {
		return@withContext try {
			val target = File(context.cacheDir, APK_NAME)
			if (target.exists()) target.delete()

			val connection = (URL(url).openConnection() as HttpURLConnection).apply {
				requestMethod = "GET"
				connectTimeout = 10000
				readTimeout = 20000
			}

			val totalBytes = connection.contentLengthLong

			connection.inputStream.use { input ->
				target.outputStream().use { output ->
					if (totalBytes > 0) {
						copyStreamWithProgress(input, output, totalBytes, onProgress)
					} else {
						copyStream(input, output)
						onProgress(1f)
					}
				}
			}
			target
		} catch (ex: Exception) {
			Log.w(TAG, "Download failed", ex)
			null
		}
	}

	fun clearOldApk(context: Context) {
		runCatching {
			val target = File(context.cacheDir, APK_NAME)
			if (target.exists()) target.delete()
		}
	}
}
