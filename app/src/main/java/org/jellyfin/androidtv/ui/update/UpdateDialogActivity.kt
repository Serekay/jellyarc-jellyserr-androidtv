package org.jellyfin.androidtv.ui.update

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.ui.base.JellyfinTheme

class UpdateDialogActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val notifyIfCurrent = intent.getBooleanExtra(EXTRA_NOTIFY_IF_CURRENT, false)

		setContent {
			JellyfinTheme {
				var viewModel by remember {
					mutableStateOf(
						UpdateDialogViewModel(
							context = this@UpdateDialogActivity,
							currentVersion = BuildConfig.VERSION_NAME
						)
					)
				}

				LaunchedEffect(Unit) {
					viewModel.checkForUpdate(notifyIfCurrent = notifyIfCurrent)
				}

				UpdateDialog(
					viewModel = viewModel,
					onDismiss = { finish() }
				)
			}
		}
	}

	companion object {
		const val EXTRA_NOTIFY_IF_CURRENT = "notify_if_current"
	}
}
