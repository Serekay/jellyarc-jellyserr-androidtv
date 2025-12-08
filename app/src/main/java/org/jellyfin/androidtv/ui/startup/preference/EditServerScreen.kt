package org.jellyfin.androidtv.ui.startup.preference

import android.app.ProgressDialog
import android.content.Intent
import android.net.VpnService
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.repository.ServerUserRepository
import org.jellyfin.androidtv.tailscale.TailscaleManager
import org.jellyfin.androidtv.ui.preference.dsl.OptionsFragment
import org.jellyfin.androidtv.ui.preference.dsl.action
import org.jellyfin.androidtv.ui.preference.dsl.checkbox
import org.jellyfin.androidtv.ui.preference.dsl.link
import org.jellyfin.androidtv.ui.preference.dsl.optionsScreen
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.util.getValue
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

class EditServerScreen : OptionsFragment() {
	private val startupViewModel: StartupViewModel by activityViewModel()
	private val serverUserRepository: ServerUserRepository by inject()
	private var isSwitching = false
	private var progressDialog: ProgressDialog? = null

	override val rebuildOnResume = true

	private var vpnPermissionCallback: ((Boolean) -> Unit)? = null
	private var currentDialog: AlertDialog? = null

	private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		vpnPermissionCallback?.invoke(result.resultCode == android.app.Activity.RESULT_OK)
		vpnPermissionCallback = null
	}

	private suspend fun ensureVpnPermission(): Boolean = suspendCoroutine { cont ->
		val intent = VpnService.prepare(requireContext())
		if (intent == null) {
			cont.resume(true)
			return@suspendCoroutine
		}

		vpnPermissionCallback = { granted ->
			cont.resume(granted)
		}
		try {
			vpnPermissionLauncher.launch(intent)
		} catch (t: Throwable) {
			vpnPermissionCallback = null
			cont.resumeWithException(t)
		}
	}

	private fun showProgressDialog() {
		progressDialog = ProgressDialog.show(
			requireContext(),
			"",
			getString(R.string.tailscale_switching_progress),
			true
		)
	}
	
	private fun hideProgressDialog() {
		progressDialog?.dismiss()
		progressDialog = null
	}

	private fun showAddressInputDialog(server: Server, onAddressSet: (String) -> Unit) {
		val editText = EditText(requireContext()).apply {
			setText(server.address)
		}
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.edit_server_address_title)
			.setMessage(R.string.edit_server_address_message)
			.setView(editText)
			.setPositiveButton(R.string.lbl_ok) { _, _ ->
				val newAddress = editText.text.toString()
				if (newAddress.isNotBlank()) {
					onAddressSet(newAddress)
				}
			}
			.setNegativeButton(R.string.lbl_cancel, null)
			.show()
	}

	override val screen by optionsScreen {
		val serverUUID = requireNotNull(
			requireArguments().getValue<UUID>(ARG_SERVER_UUID)
		) { "Server null or malformed uuid" }

		val server = requireNotNull(startupViewModel.getServer(serverUUID)) { "Server not found" }
		val users = serverUserRepository.getStoredServerUsers(server)

		title = server.name

		if (users.isNotEmpty()) {
			category {
				setTitle(R.string.pref_accounts)

				users.forEach { user ->
					link {
						title = user.name
						icon = R.drawable.ic_user

						val lastUsedDate = LocalDateTime.ofInstant(
							Instant.ofEpochMilli(user.lastUsed),
							ZoneId.systemDefault()
						)
						content = context.getString(
							R.string.lbl_user_last_used,
							lastUsedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
							lastUsedDate.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
						)

						withFragment<EditUserScreen>(bundleOf(
							EditUserScreen.ARG_SERVER_UUID to server.id,
							EditUserScreen.ARG_USER_UUID to user.id,
						))
					}
				}
			}
		}

		category {
			setTitle(R.string.tailscale_section_title)

			checkbox {
				type = org.jellyfin.androidtv.ui.preference.dsl.OptionsItemCheckbox.Type.SWITCH
				setTitle(R.string.tailscale_enable_title)
				setContent(R.string.tailscale_enable_summary_on, R.string.tailscale_enable_summary_off)
				bind {
					get { server.tailscaleEnabled }
					set { enabled ->
						if (isSwitching) return@set
						isSwitching = true
						if (enabled) {
							// ### TAILSCALE ACTIVATION FLOW ###
							lifecycleScope.launch {
								try {
									// 1. Get VPN permission
									val permissionGranted = ensureVpnPermission()
									if (!permissionGranted) {
										Toast.makeText(requireContext(), "VPN permission is required.", Toast.LENGTH_LONG).show()
										isSwitching = false
										rebuild()
										return@launch
									}

									// 2. Request login code
									val codeResult = TailscaleManager.requestLoginCode()
									if (codeResult.isFailure) {
										throw codeResult.exceptionOrNull() ?: Exception("Failed to get login code")
									}
									val code = codeResult.getOrThrow()

									// 3. Show code and wait for login
									currentDialog = AlertDialog.Builder(requireContext())
										.setTitle(R.string.tailscale_connecting_title)
										.setMessage(getString(R.string.tailscale_connecting_message, code))
										.setCancelable(false)
										.show()

									val loginFinished = TailscaleManager.waitUntilLoginFinished(timeoutMs = 120_000L)
									currentDialog?.dismiss()

									if (!loginFinished) {
										throw Exception("Login timed out. Please make sure to authorize the device in your Tailscale dashboard.")
									}

									// 4. Start VPN and wait until connected
									TailscaleManager.startVpn()
									val vpnConnected = TailscaleManager.waitUntilConnected(timeoutMs = 60_000L)
									if (!vpnConnected) {
										throw Exception("VPN connection timed out after login.")
									}
									
									// 5. Ask for new address
									showAddressInputDialog(server) { newAddress ->
										startupViewModel.setServerAddress(serverUUID, newAddress)
										startupViewModel.setTailscaleEnabled(serverUUID, true)
										Toast.makeText(requireContext(), "Server updated. Please restart the app.", Toast.LENGTH_LONG).show()
										isSwitching = false
										rebuild()
									}

								} catch (e: Exception) {
									currentDialog?.dismiss()
									Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
									isSwitching = false
									rebuild()
								}
							}
						} else {
							// ### TAILSCALE DEACTIVATION FLOW ###
							lifecycleScope.launch {
								try {
									showProgressDialog()
									TailscaleManager.stopVpn()
									hideProgressDialog()
									showAddressInputDialog(server) { newAddress ->
										startupViewModel.setServerAddress(serverUUID, newAddress)
										startupViewModel.setTailscaleEnabled(serverUUID, false)
										Toast.makeText(requireContext(), "Server updated. Please restart the app.", Toast.LENGTH_LONG).show()
									}
								} finally {
									isSwitching = false
									rebuild()
								}
							}
						}
					}
					default { false }
				}
			}

			action {
				setTitle(R.string.tailscale_login_code_title)
				setContent(R.string.tailscale_login_code_summary)
				onActivate = {
					if (!server.tailscaleEnabled) {
						Toast.makeText(requireContext(), R.string.tailscale_enable_first, Toast.LENGTH_SHORT).show()
					} else {
						lifecycleScope.launch {
							val result = TailscaleManager.requestLoginCode()
							val message = result.fold(
								onSuccess = { code -> getString(R.string.tailscale_setup_toast_code, code) },
								onFailure = { it.message ?: getString(R.string.tailscale_setup_toast_error) }
							)
							requireContext().let { ctx ->
								Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
							}
						}
					}
				}
			}

			action {
				setTitle(R.string.tailscale_connect_title)
				setContent(R.string.tailscale_connect_summary)
				onActivate = {
					if (!server.tailscaleEnabled) {
						Toast.makeText(requireContext(), R.string.tailscale_enable_first, Toast.LENGTH_SHORT).show()
					} else {
						lifecycleScope.launch {
							TailscaleManager.startVpn()
						}
					}
				}
			}

			action {
				setTitle(R.string.tailscale_disconnect_title)
				setContent(R.string.tailscale_disconnect_summary)
				onActivate = {
					TailscaleManager.stopVpn()
					Toast.makeText(requireContext(), R.string.tailscale_disconnect_done, Toast.LENGTH_SHORT).show()
				}
			}

			action {
				setTitle(R.string.tailscale_reset_auth_title)
				setContent(R.string.tailscale_reset_auth_summary)
				onActivate = {
					lifecycleScope.launch {
						val msg = TailscaleManager.resetAuthPublic().fold(
							onSuccess = { getString(R.string.tailscale_reset_auth_success) },
							onFailure = { it.message ?: getString(R.string.tailscale_setup_toast_error) }
						)
						Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
					}
				}
			}
		}

		category {
			setTitle(R.string.lbl_server)

			action {
				setTitle(R.string.lbl_remove_server)
				setContent(R.string.lbl_remove_users)
				icon = R.drawable.ic_delete
				onActivate = {
					startupViewModel.deleteServer(serverUUID)

					parentFragmentManager.popBackStack()
				}
			}
		}
	}


	companion object {
		const val ARG_SERVER_UUID = "server_uuid"
	}
}