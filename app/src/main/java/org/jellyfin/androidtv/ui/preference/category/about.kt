package org.jellyfin.androidtv.ui.preference.category

import android.content.Intent
import android.os.Build
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.preference.dsl.OptionsScreen
import org.jellyfin.androidtv.ui.preference.dsl.link
import org.jellyfin.androidtv.ui.preference.screen.LicensesScreen
import org.jellyfin.androidtv.ui.preference.dsl.action
import org.jellyfin.androidtv.ui.update.UpdateDialogActivity

fun OptionsScreen.aboutCategory() = category {
	setTitle(R.string.pref_about_title)

	link {
		// Hardcoded strings for troubleshooting purposes
		title = "JellyArc app version"
		content = "jellyarc-androidtv ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}"
		icon = R.drawable.ic_jellyfin
	}

	link {
		setTitle(R.string.pref_device_model)
		content = "${Build.MANUFACTURER} ${Build.MODEL}"
		icon = R.drawable.ic_tv
	}

	link {
		setTitle(R.string.licenses_link)
		setContent(R.string.licenses_link_description)
		icon = R.drawable.ic_guide
		withFragment<LicensesScreen>()
	}

	action {
		setTitle(R.string.jellyseerr_update_check_title)
		setContent(R.string.jellyseerr_update_check_summary)
		icon = R.drawable.ic_settings
		onActivate = {
			val intent = Intent(context, UpdateDialogActivity::class.java).apply {
				putExtra(UpdateDialogActivity.EXTRA_NOTIFY_IF_CURRENT, true)
			}
			context.startActivity(intent)
		}
	}
}
