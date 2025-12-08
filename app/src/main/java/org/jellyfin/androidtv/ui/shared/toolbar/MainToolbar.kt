package org.jellyfin.androidtv.ui.shared.toolbar

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.tailscale.TailscaleManager
import org.jellyfin.androidtv.ui.NowPlayingComposable
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.button.IconButtonDefaults
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.preference.PreferencesActivity
import org.jellyfin.androidtv.ui.startup.preference.EditServerScreen
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.compose.koinInject

enum class MainToolbarActiveButton {
	User,
	Home,
	Search,
	Requests,
	None,
}

@Composable
fun MainToolbar(
	activeButton: MainToolbarActiveButton = MainToolbarActiveButton.None,
) {
	val userRepository = koinInject<UserRepository>()
	val api = koinInject<ApiClient>()

	// Prevent user image to disappear when signing out by skipping null values
	val currentUser by remember { userRepository.currentUser.filterNotNull() }.collectAsState(null)
	val userImage = remember(currentUser) { currentUser?.primaryImage?.getUrl(api) }

	MainToolbar(
		userImage = userImage,
		activeButton = activeButton,
	)
}

@Composable
private fun MainToolbar(
	userImage: String? = null,
	activeButton: MainToolbarActiveButton,
) {
	val focusRequester = remember { FocusRequester() }
	val navigationRepository = koinInject<NavigationRepository>()
	val mediaManager = koinInject<MediaManager>()
	val sessionRepository = koinInject<SessionRepository>()
	val serverRepository = koinInject<ServerRepository>()
	val activity = LocalActivity.current
	val context = LocalContext.current
	val activeButtonColors = ButtonDefaults.colors(
		containerColor = JellyfinTheme.colorScheme.buttonActive,
		contentColor = JellyfinTheme.colorScheme.onButtonActive,
	)

	var indicatorText by remember { mutableStateOf("") }
	var indicatorColor by remember { mutableStateOf(Color.Transparent) }
	var indicatorVisible by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		while (true) {
			val server = serverRepository.currentServer.value
			if (server == null) {
				indicatorVisible = false
			} else {
				indicatorVisible = true
				if (server.tailscaleEnabled) {
					indicatorText = context.getString(R.string.connection_status_vpn)
					indicatorColor = if (TailscaleManager.isVpnActive()) {
						Color(0xFF4CAF50) // Green
					} else {
						Color(0xFFFFA000) // Orange
					}
				} else {
					indicatorText = context.getString(R.string.connection_status_local)
					indicatorColor = Color(0xFF2196F3) // Blue
				}
			}
			delay(5000) // Refresh every 5 seconds
		}
	}

	Toolbar(
		modifier = Modifier
			.focusRestorer(focusRequester)
			.focusGroup(),
		start = {
			ToolbarButtons {
				val userImagePainter = rememberAsyncImagePainter(userImage)
				val userImageState by userImagePainter.state.collectAsState()
				val userImageVisible = userImageState is AsyncImagePainter.State.Success

				IconButton(
					onClick = {
						if (activeButton != MainToolbarActiveButton.User) {
							mediaManager.clearAudioQueue()
							sessionRepository.destroyCurrentSession()

							// Open login activity
							activity?.startActivity(ActivityDestinations.startup(activity))
							activity?.finishAfterTransition()
						}
					},
					colors = if (activeButton == MainToolbarActiveButton.User) activeButtonColors else ButtonDefaults.colors(),
					contentPadding = if (userImageVisible) PaddingValues(3.dp) else IconButtonDefaults.ContentPadding,
				) {
					Image(
						painter = if (userImageVisible) userImagePainter else rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_user)),
						contentDescription = stringResource(R.string.lbl_switch_user),
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.aspectRatio(1f)
							.clip(IconButtonDefaults.Shape)
					)
				}

				NowPlayingComposable(
					onFocusableChange = {},
				)
			}
		},
		center = {
			ToolbarButtons(
				modifier = Modifier
					.focusRequester(focusRequester)
			) {
				ProvideTextStyle(JellyfinTheme.typography.default.copy(fontWeight = FontWeight.Bold)) {
					Button(
						onClick = {
							if (activeButton != MainToolbarActiveButton.Home) {
								navigationRepository.navigate(
									destination = Destinations.home,
									replace = true,
								)
							}
						},
						colors = if (activeButton == MainToolbarActiveButton.Home) activeButtonColors else ButtonDefaults.colors(),
						content = { Text(stringResource(R.string.lbl_home)) }
					)
					Button(
						onClick = {
							if (activeButton != MainToolbarActiveButton.Search) {
								navigationRepository.navigate(Destinations.search())
							}
						},
						colors = if (activeButton == MainToolbarActiveButton.Search) activeButtonColors else ButtonDefaults.colors(),
						content = { Text(stringResource(R.string.lbl_search)) }
					)
					Button(
						onClick = {
							navigationRepository.navigate(
								destination = Destinations.jellyseerr,
								replace = activeButton == MainToolbarActiveButton.Requests,
							)
						},
						colors = if (activeButton == MainToolbarActiveButton.Requests) activeButtonColors else ButtonDefaults.colors(),
						content = { Text(stringResource(R.string.jellyseerr_lbl_requests)) }
					)
				}
			}
		},
		end = {
			ToolbarButtons {
				if (indicatorVisible) {
					Button(
						onClick = {
							val server = serverRepository.currentServer.value
							if (server != null && activity != null) {
								val intent = Intent(activity, PreferencesActivity::class.java).apply {
									putExtra(PreferencesActivity.EXTRA_SCREEN, EditServerScreen::class.java.name)
									putExtra(PreferencesActivity.EXTRA_SCREEN_ARGS, bundleOf(EditServerScreen.ARG_SERVER_UUID to server.id))
								}
								activity.startActivity(intent)
							}
						},
						colors = ButtonDefaults.colors(containerColor = indicatorColor)
					) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_vpn),
							contentDescription = null
						)
						Spacer(Modifier.width(8.dp))
						Text(indicatorText)
					}
				}
				IconButton(
					onClick = {
						activity?.startActivity(ActivityDestinations.userPreferences(activity))
					},
				) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_settings),
						contentDescription = stringResource(R.string.lbl_settings),
					)
				}

				ToolbarClock()
			}
		}
	)
}

