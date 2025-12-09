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

	private fun showProgressDialog(message: String = getString(R.string.tailscale_switching_progress)) {
		progressDialog = ProgressDialog.show(
			requireContext(),
			"",
			message,
			true
		)
	}

	private fun updateProgressDialog(message: String) {
		if (progressDialog == null) {
			showProgressDialog(message)
		} else {
			progressDialog?.setMessage(message)
		}
	}

	private fun hideProgressDialog() {
		progressDialog?.dismiss()
		progressDialog = null
	}

	private fun showRestartDialog() {
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.restart_required_title)
			.setMessage(R.string.restart_required_message)
			.setCancelable(false)
			.setPositiveButton(R.string.restart_now) { _, _ ->
				val context = requireActivity()
				val packageManager = context.packageManager
				val intent = packageManager.getLaunchIntentForPackage(context.packageName)
				val componentName = intent!!.component
				val mainIntent = Intent.makeRestartActivityTask(componentName)
				context.startActivity(mainIntent)
				Runtime.getRuntime().exit(0)
			}
			.show()
	}

	/**
	 * Normalisiert eine Server-Adresse und stellt sicher dass sie ein gültiges Format hat.
	 *
	 * Unterstützt:
	 * - Reine IPs: 192.168.1.1 → http://192.168.1.1
	 * - IPs mit Port: 192.168.1.1:8096 → http://192.168.1.1:8096
	 * - Hostnamen: server → http://server
	 * - Hostnamen mit Port: server:8096 → http://server:8096
	 * - URLs mit Schema: http://server.com → bleibt unverändert
	 */
	private fun normalizeServerAddress(address: String): String {
		var normalized = address.trim()

		// Schema hinzufügen falls nicht vorhanden
		if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
			normalized = "http://$normalized"
			timber.log.Timber.d("normalizeServerAddress: Added http:// schema: $normalized")
		}

		// Entferne trailing slashes
		normalized = normalized.trimEnd('/')

		timber.log.Timber.d("normalizeServerAddress: Final address: '$address' → '$normalized'")
		return normalized
	}

	private suspend fun startActivationFlow(server: Server, serverUUID: UUID) {
		try {
			timber.log.Timber.d("=== VPN ACTIVATION START ===")

			// Step 1: VPN Permission
			updateProgressDialog(getString(R.string.tailscale_step_permission))
			val permissionGranted = ensureVpnPermission()
			if (!permissionGranted) {
				hideProgressDialog()
				AlertDialog.Builder(requireContext())
					.setTitle(R.string.tailscale_error_title)
					.setMessage(R.string.tailscale_vpn_permission_needed)
					.setPositiveButton(R.string.lbl_ok, null)
					.setOnDismissListener {
						isSwitching = false
						rebuild()
					}
					.show()
				return
			}

			// Step 2: Stop VPN if running (clean state)
			updateProgressDialog(getString(R.string.tailscale_step_stopping_vpn))
			TailscaleManager.stopVpn()
			kotlinx.coroutines.delay(1000)

			// Step 3: Request login code
			updateProgressDialog(getString(R.string.tailscale_step_requesting_code))
			val codeResult = TailscaleManager.requestLoginCode()
			if (codeResult.isFailure) {
				hideProgressDialog()
				throw codeResult.exceptionOrNull() ?: Exception("Failed to get login code")
			}
			val code = codeResult.getOrThrow()
			timber.log.Timber.d("Got login code: $code")

			// Step 4: Show code dialog and wait for login
			hideProgressDialog()
			currentDialog = AlertDialog.Builder(requireContext())
				.setTitle(R.string.tailscale_connecting_title)
				.setMessage(getString(R.string.tailscale_connecting_message, code))
				.setCancelable(false)
				.show()

			val loginFinished = TailscaleManager.waitUntilLoginFinished(timeoutMs = 120_000L)
			currentDialog?.dismiss()
			currentDialog = null

			if (!loginFinished) {
				timber.log.Timber.w("Login timeout - user did not authorize device")
				AlertDialog.Builder(requireContext())
					.setTitle(R.string.tailscale_login_timeout_title)
					.setMessage(R.string.tailscale_login_timeout_message)
					.setPositiveButton(R.string.lbl_ok, null)
					.setOnDismissListener {
						isSwitching = false
						rebuild()
					}
					.show()
				return
			}

			// Step 5: Start VPN
			updateProgressDialog(getString(R.string.tailscale_step_starting_vpn))
			val vpnStarted = TailscaleManager.startVpn()
			if (!vpnStarted) {
				hideProgressDialog()
				throw Exception("Failed to start VPN service")
			}

			// Step 6: Wait for connection
			updateProgressDialog(getString(R.string.tailscale_step_connecting))
			val vpnConnected = TailscaleManager.waitUntilConnected(timeoutMs = 30_000L)
			hideProgressDialog()

			if (!vpnConnected) {
				timber.log.Timber.w("VPN connection timeout after successful login")
				AlertDialog.Builder(requireContext())
					.setTitle(R.string.tailscale_vpn_timeout_title)
					.setMessage(R.string.tailscale_vpn_timeout_message)
					.setPositiveButton(R.string.lbl_ok, null)
					.setOnDismissListener {
						isSwitching = false
						rebuild()
					}
					.show()
				return
			}

			timber.log.Timber.d("VPN connected successfully")

			// Step 7: Ask for new server address
			showAddressInputDialog(
				server = server,
				useTailscale = true,
				onCancel = {
					lifecycleScope.launch {
						TailscaleManager.stopVpn()
						isSwitching = false
						rebuild()
					}
				},
				onAddressSet = { newAddress ->
					startupViewModel.setServerAddress(serverUUID, newAddress)
					startupViewModel.setTailscaleEnabled(serverUUID, true)
					isSwitching = false
					showRestartDialog()
				}
			)

			timber.log.Timber.d("=== VPN ACTIVATION END ===")

		} catch (e: Exception) {
			timber.log.Timber.e(e, "VPN activation failed")
			currentDialog?.dismiss()
			currentDialog = null
			hideProgressDialog()

			try {
				TailscaleManager.stopVpn()
			} catch (stopError: Exception) {
				timber.log.Timber.w(stopError, "Failed to stop VPN after error")
			}

			AlertDialog.Builder(requireContext())
				.setTitle(R.string.tailscale_error_title)
				.setMessage(getString(R.string.tailscale_error_generic, e.message))
				.setPositiveButton(R.string.lbl_ok, null)
				.setOnDismissListener {
					isSwitching = false
					rebuild()
				}
				.show()
		}
	}

	private fun showAddressInputDialog(
		server: Server,
		useTailscale: Boolean = server.tailscaleEnabled,
		onCancel: () -> Unit = {},
		onAddressSet: (String) -> Unit
	) {
		val editText = EditText(requireContext()).apply {
			hint = getString(R.string.edit_server_address_hint)
			// Leave empty - user must enter the full address from admin
		}
		val dialog = AlertDialog.Builder(requireContext())
			.setTitle(R.string.edit_server_address_title)
			.setMessage(R.string.edit_server_address_message)
			.setView(editText)
			.setPositiveButton(R.string.lbl_ok, null) // Set to null. We override the handler later.
			.setNegativeButton(R.string.lbl_cancel) { _, _ ->
				onCancel()
			}
			.setOnCancelListener {
				onCancel()
			}
			.create()

		dialog.setOnShowListener {
			val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
			positiveButton.setOnClickListener {
				val newAddress = editText.text.toString().trim()
				if (newAddress.isBlank()) {
					editText.error = getString(R.string.server_field_empty)
					return@setOnClickListener
				}

				// Reset error and show progress
				editText.error = null
				showProgressDialog()

				lifecycleScope.launch {
					// Use centralized address normalization
					val addressToTest = normalizeServerAddress(newAddress)

					// Validiere die Adresse (jellyfin.discovery.getAddressCandidates macht schon Smart-Parsing)
					val isValid = try {
						startupViewModel.testServerAddress(addressToTest)
					} catch (e: Exception) {
						timber.log.Timber.e(e, "Address validation failed")
						false
					}

					hideProgressDialog()

					if (isValid) {
						onAddressSet(addressToTest)
						dialog.dismiss()
					} else {
						AlertDialog.Builder(requireContext())
							.setTitle(R.string.server_connection_failed_title)
							.setMessage(R.string.server_connection_failed_message)
							.setPositiveButton(R.string.lbl_ok) { _, _ -> }
							.show()
					}
				}
			}
		}
		dialog.show()
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
							timber.log.Timber.d("Tailscale activation requested")
							lifecycleScope.launch {
								startActivationFlow(server, serverUUID)
							}
						} else {
							// ### TAILSCALE DEACTIVATION FLOW ###
							timber.log.Timber.d("Tailscale deactivation requested")
							lifecycleScope.launch {
								try {
									// Step 1: Stop VPN
									updateProgressDialog(getString(R.string.tailscale_step_stopping_vpn))
									TailscaleManager.stopVpn()
									kotlinx.coroutines.delay(500) // Brief pause
									hideProgressDialog()

									// Step 2: Ask for new local address
									showAddressInputDialog(
										server = server,
										useTailscale = false,
										onCancel = {
											// User cancelled - re-enable VPN
											lifecycleScope.launch {
												try {
													TailscaleManager.startVpn()
												} catch (e: Exception) {
													timber.log.Timber.w(e, "Failed to restart VPN after cancel")
												}
												isSwitching = false
												rebuild()
											}
										},
										onAddressSet = { newAddress ->
											// Success - save settings
											startupViewModel.setServerAddress(serverUUID, newAddress)
											startupViewModel.setTailscaleEnabled(serverUUID, false)
											isSwitching = false
											showRestartDialog()
										}
									)
								} catch (e: Exception) {
									timber.log.Timber.e(e, "VPN deactivation failed")
									hideProgressDialog()
									AlertDialog.Builder(requireContext())
										.setTitle(R.string.tailscale_error_title)
										.setMessage(getString(R.string.tailscale_error_generic, e.message))
										.setPositiveButton(R.string.lbl_ok, null)
										.setOnDismissListener {
											isSwitching = false
											rebuild()
										}
										.show()
								}
							}
						}
					}
					default { false }
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