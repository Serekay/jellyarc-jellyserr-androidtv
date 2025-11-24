package org.jellyfin.androidtv.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.UpdateChecker
import java.io.File

enum class UpdatePhase {
	CHECKING,
	AVAILABLE,
	DOWNLOADING,
	INSTALLING,
	UP_TO_DATE,
	ERROR
}

data class UpdateDialogState(
	val isVisible: Boolean = false,
	val updatePhase: UpdatePhase = UpdatePhase.CHECKING,
	val releaseInfo: UpdateChecker.ReleaseInfo? = null,
	val downloadProgress: Float = -1f,
	val errorMessage: String? = null
)

class UpdateDialogViewModel(
	private val context: Context,
	private val currentVersion: String
) : ViewModel() {

	private val _state = MutableStateFlow(UpdateDialogState())
	val state: StateFlow<UpdateDialogState> = _state.asStateFlow()

	companion object {
		private const val TAG = "UpdateDialogViewModel"
	}

	fun checkForUpdate(notifyIfCurrent: Boolean = false) {
		_state.value = UpdateDialogState(
			isVisible = true,
			updatePhase = UpdatePhase.CHECKING
		)

		viewModelScope.launch {
			try {
				val release = UpdateChecker.fetchLatestRelease(currentVersion)

				if (release != null) {
					_state.value = _state.value.copy(
						updatePhase = UpdatePhase.AVAILABLE,
						releaseInfo = release
					)
				} else if (notifyIfCurrent) {
					_state.value = _state.value.copy(
						updatePhase = UpdatePhase.UP_TO_DATE
					)
				} else {
					// Kein Update verfügbar und notifyIfCurrent ist false - Dialog schließen
					dismiss()
				}
			} catch (e: Exception) {
				Log.e(TAG, "Error checking for update", e)
				_state.value = _state.value.copy(
					updatePhase = UpdatePhase.ERROR,
					errorMessage = context.getString(R.string.jellyseerr_update_check_failed)
				)
			}
		}
	}

	fun startUpdate() {
		val release = _state.value.releaseInfo ?: return

		_state.value = _state.value.copy(
			updatePhase = UpdatePhase.DOWNLOADING,
			downloadProgress = 0f
		)

		viewModelScope.launch {
			try {
				// Alte APK löschen
				UpdateChecker.clearOldApk(context)

				// APK herunterladen mit Progress
				val apkFile = UpdateChecker.downloadApkWithProgress(
					context = context,
					url = release.downloadUrl,
					onProgress = { progress ->
						_state.value = _state.value.copy(
							downloadProgress = progress
						)
					}
				)

				if (apkFile == null) {
					_state.value = _state.value.copy(
						updatePhase = UpdatePhase.ERROR,
						errorMessage = context.getString(R.string.jellyseerr_update_download_failed)
					)
					return@launch
				}

				// Installation vorbereiten
				_state.value = _state.value.copy(
					updatePhase = UpdatePhase.INSTALLING
				)

				// Prüfe Installationsberechtigung (Android 8.0+)
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					val canInstall = context.packageManager.canRequestPackageInstalls()
					if (!canInstall) {
						_state.value = _state.value.copy(
							updatePhase = UpdatePhase.ERROR,
							errorMessage = context.getString(R.string.jellyseerr_update_permission_required)
						)
						// Öffne Einstellungen für Installation aus unbekannten Quellen
						val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
							data = Uri.parse("package:${context.packageName}")
							addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
						}
						runCatching { context.startActivity(settingsIntent) }
							.onFailure { Log.w(TAG, "Failed to open unknown sources settings", it) }
						return@launch
					}
				}

				// Installer starten
				installApk(apkFile)

			} catch (e: Exception) {
				Log.e(TAG, "Error during update", e)
				_state.value = _state.value.copy(
					updatePhase = UpdatePhase.ERROR,
					errorMessage = context.getString(R.string.jellyseerr_update_install_failed)
				)
			}
		}
	}

	private fun installApk(apkFile: File) {
		try {
			val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
			val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
				setDataAndType(uri, "application/vnd.android.package-archive")
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
			}

			context.startActivity(intent)
			// Dialog bleibt offen während Installation läuft
		} catch (e: Exception) {
			Log.e(TAG, "Failed to launch installer", e)
			_state.value = _state.value.copy(
				updatePhase = UpdatePhase.ERROR,
				errorMessage = context.getString(R.string.jellyseerr_update_install_failed)
			)
		}
	}

	fun dismiss() {
		_state.value = _state.value.copy(isVisible = false)
	}
}
