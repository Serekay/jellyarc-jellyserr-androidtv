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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.repository.ServerUserRepository
import org.jellyfin.androidtv.tailscale.LoginCodeTimeoutException
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
		if (!isAdded) {
			cont.resume(false)
			return@suspendCoroutine
		}

		val intent = VpnService.prepare(requireContext())
		if (intent == null) {
			// Berechtigung bereits erteilt
			timber.log.Timber.d("VPN permission already granted")
			cont.resume(true)
			return@suspendCoroutine
		}

		timber.log.Timber.d("VPN permission needed, showing system dialog")
		vpnPermissionCallback = { granted ->
			timber.log.Timber.d("VPN permission result: granted=$granted")
			cont.resume(granted)
		}
		try {
			vpnPermissionLauncher.launch(intent)
		} catch (t: Throwable) {
			timber.log.Timber.e(t, "Failed to launch VPN permission dialog")
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
		// Speichere Activity-Referenz am Anfang, da isAdded später false sein kann
		val activityRef = activity ?: run {
			timber.log.Timber.e("startActivationFlow: No activity available")
			return
		}

		try {
			timber.log.Timber.d("=== VPN ACTIVATION START ===")

			// Step 1: VPN Permission (mit Retry-Möglichkeit)
			updateProgressDialog(getString(R.string.tailscale_step_permission))
			var permissionGranted = ensureVpnPermission()

			// Wenn nicht genehmigt, biete Retry an
			while (!permissionGranted) {
				hideProgressDialog()
				if (!isAdded) return

				// Frage ob der User es nochmal versuchen möchte
				val shouldRetry = suspendCoroutine<Boolean> { cont ->
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.tailscale_error_title)
						.setMessage(R.string.tailscale_vpn_permission_needed)
						.setPositiveButton(R.string.tailscale_retry_button) { _, _ ->
							cont.resume(true)
						}
						.setNegativeButton(R.string.lbl_cancel) { _, _ ->
							cont.resume(false)
						}
						.setCancelable(false)
						.show()
				}

				if (!shouldRetry) {
					isSwitching = false
					rebuild()
					return
				}

				// Erneut versuchen
				updateProgressDialog(getString(R.string.tailscale_step_permission))
				permissionGranted = ensureVpnPermission()
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

			// Sicherer Dialog-Aufbau mit isAdded Check
			if (!isAdded) {
				timber.log.Timber.w("Fragment not attached, aborting VPN activation")
				return
			}

			// Dialog auf dem Main Thread erstellen und referenzieren
			withContext(Dispatchers.Main) {
				if (!isAdded) return@withContext
				currentDialog = AlertDialog.Builder(requireContext())
					.setTitle(R.string.tailscale_connecting_title)
					.setMessage(getString(R.string.tailscale_connecting_message, code))
					.setCancelable(false)
					.show()
			}

			// Warte auf Login (läuft auf IO Thread)
			timber.log.Timber.d("Waiting for login to finish...")
			val loginFinished = TailscaleManager.waitUntilLoginFinished(timeoutMs = 120_000L)
			timber.log.Timber.d("waitUntilLoginFinished returned: $loginFinished")

			// Dialog sicher schließen
			withContext(Dispatchers.Main) {
				currentDialog?.dismiss()
				currentDialog = null
			}

			if (!loginFinished) {
				timber.log.Timber.w("Login timeout - user did not authorize device, isAdded=$isAdded")
				withContext(Dispatchers.Main) {
					if (!isAdded) return@withContext
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.tailscale_login_timeout_title)
						.setMessage(R.string.tailscale_login_timeout_message)
						.setPositiveButton(R.string.lbl_ok, null)
						.setOnDismissListener {
							isSwitching = false
							rebuild()
						}
						.show()
				}
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
				withContext(Dispatchers.Main) {
					if (!isAdded) return@withContext
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.tailscale_vpn_timeout_title)
						.setMessage(R.string.tailscale_vpn_timeout_message)
						.setPositiveButton(R.string.lbl_ok, null)
						.setOnDismissListener {
							isSwitching = false
							rebuild()
						}
						.show()
				}
				return
			}

			timber.log.Timber.d("VPN connected successfully, showing success dialog")

			// Step 7: Show success message before asking for address
			// Zeige Success-Dialog und warte auf OK, dann zeige Address-Input
			withContext(Dispatchers.Main) {
				timber.log.Timber.d("Step 7: isAdded=$isAdded, activityRef=$activityRef")

				// Zeige Success Toast (wird immer angezeigt)
				Toast.makeText(
					activityRef,
					R.string.tailscale_auth_success_title,
					Toast.LENGTH_LONG
				).show()

				// Zeige Success-Dialog, dann Address-Input
				AlertDialog.Builder(activityRef)
					.setTitle(R.string.tailscale_auth_success_title)
					.setMessage(R.string.tailscale_auth_success_message)
					.setPositiveButton(R.string.lbl_ok) { _, _ ->
						timber.log.Timber.d("Success dialog OK clicked, showing address input")
						// Step 8: Nach OK -> Address Input Dialog zeigen
						showAddressInputDialogWithContext(
							activityRef,
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
					}
					.setCancelable(false)
					.show()
				timber.log.Timber.d("Success dialog shown")
			}

			timber.log.Timber.d("=== VPN ACTIVATION END ===")

		} catch (e: Exception) {
			timber.log.Timber.e(e, "VPN activation failed")
			withContext(Dispatchers.Main) {
				currentDialog?.dismiss()
				currentDialog = null
				hideProgressDialog()
			}

			try {
				TailscaleManager.stopVpn()
			} catch (stopError: Exception) {
				timber.log.Timber.w(stopError, "Failed to stop VPN after error")
			}

			withContext(Dispatchers.Main) {
				if (!isAdded) return@withContext

				// Spezielle Behandlung für Login-Code Timeout (Android TV Bug)
				val (title, message) = when (e) {
					is LoginCodeTimeoutException -> {
						timber.log.Timber.w("Login code timeout - TV restart likely required")
						Pair(
							R.string.tailscale_login_code_timeout_title,
							getString(R.string.tailscale_login_code_timeout_message)
						)
					}
					else -> {
						Pair(
							R.string.tailscale_error_title,
							getString(R.string.tailscale_error_generic, e.message)
						)
					}
				}

				AlertDialog.Builder(requireContext())
					.setTitle(title)
					.setMessage(message)
					.setPositiveButton(R.string.lbl_ok, null)
					.setOnDismissListener {
						isSwitching = false
						rebuild()
					}
					.show()
			}
		}
	}

	private fun showAddressInputDialog(
		server: Server,
		useTailscale: Boolean = server.tailscaleEnabled,
		onCancel: () -> Unit = {},
		onAddressSet: (String) -> Unit
	) {
		// Sicherstellen, dass Fragment noch attached ist
		if (!isAdded) {
			timber.log.Timber.w("showAddressInputDialog: Fragment not attached")
			onCancel()
			return
		}

		showAddressInputDialogWithContext(requireContext(), server, useTailscale, onCancel, onAddressSet)
	}

	private fun showAddressInputDialogWithContext(
		ctx: android.content.Context,
		server: Server,
		useTailscale: Boolean = server.tailscaleEnabled,
		onCancel: () -> Unit = {},
		onAddressSet: (String) -> Unit
	) {
		// First ask: Manual, Auto, or Cancel?
		// Button order: Negative=Manual (left), Neutral=Auto (middle), Positive=Cancel (right)
		AlertDialog.Builder(ctx)
			.setTitle(R.string.server_address_selection_title)
			.setMessage(R.string.server_address_selection_message)
			.setNegativeButton(R.string.server_address_manual) { _, _ ->
				// MANUAL: Show input dialog
				showManualAddressInputDialogWithContext(ctx, onCancel, onAddressSet)
			}
			.setNeutralButton(R.string.server_address_auto) { _, _ ->
				// AUTO: Fetch from plugin
				lifecycleScope.launch {
					fetchJellyfinUrlFromPlugin(server, useTailscale, onAddressSet, onCancel)
				}
			}
			.setPositiveButton(R.string.lbl_cancel) { _, _ ->
				onCancel()
			}
			.setOnCancelListener {
				onCancel()
			}
			.show()
	}

	private fun showManualAddressInputDialog(
		onCancel: () -> Unit,
		onAddressSet: (String) -> Unit
	) {
		showManualAddressInputDialogWithContext(requireContext(), onCancel, onAddressSet)
	}

	private fun showManualAddressInputDialogWithContext(
		ctx: android.content.Context,
		onCancel: () -> Unit,
		onAddressSet: (String) -> Unit
	) {
		val editText = EditText(ctx).apply {
			hint = ctx.getString(R.string.edit_server_address_hint)
		}
		val dialog = AlertDialog.Builder(ctx)
			.setTitle(R.string.edit_server_address_title)
			.setMessage(R.string.edit_server_address_message)
			.setView(editText)
			.setPositiveButton(R.string.lbl_ok, null)
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
					editText.error = ctx.getString(R.string.server_field_empty)
					return@setOnClickListener
				}

				editText.error = null
				showProgressDialog()

				lifecycleScope.launch {
					val addressToTest = normalizeServerAddress(newAddress)
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
						AlertDialog.Builder(ctx)
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

	private suspend fun fetchJellyfinUrlFromPlugin(
		server: Server,
		useTailscale: Boolean,
		onSuccess: (String) -> Unit,
		onError: () -> Unit
	) = withContext(Dispatchers.IO) {
		try {
			withContext(Dispatchers.Main) {
				updateProgressDialog(getString(R.string.fetching_server_url))
			}

			// WICHTIG: Wir nutzen die AKTUELLE Server-Adresse um das Plugin zu erreichen
			// Das Plugin gibt uns dann die NEUE Adresse zurück (VPN oder lokal)
			val jellyfinBase = server.address.trimEnd('/')

			// Wir fragen nach der Adresse die wir haben WOLLEN (nicht die wir haben)
			val endpoint = if (useTailscale) {
				"$jellyfinBase/plugins/requests/tailscale/jellyfinbase"
			} else {
				"$jellyfinBase/plugins/requests/jellyfinbase"
			}

			timber.log.Timber.d("Fetching Jellyfin URL from plugin: $endpoint (current: $jellyfinBase, want: ${if (useTailscale) "VPN" else "local"})")

			// Simple HTTP call - we just need the JSON
			val client = OkHttpClient()
			val request = Request.Builder()
				.url(endpoint)
				.build()

			client.newCall(request).execute().use { response ->
				withContext(Dispatchers.Main) {
					hideProgressDialog()
				}

				if (!response.isSuccessful) {
					throw Exception("HTTP ${response.code}")
				}

				val body = response.body?.string() ?: throw Exception("Empty response")
				val json = JSONObject(body.trimStart().removePrefix("\uFEFF"))
				val success = json.optBoolean("success", false)
				val jellyfinUrl = json.optString("jellyfinBase").trim()

				if (!success || jellyfinUrl.isBlank()) {
					throw Exception("Plugin returned invalid data")
				}

				timber.log.Timber.d("Fetched Jellyfin URL from plugin: $jellyfinUrl")

				// Validate the URL
				val isValid = try {
					startupViewModel.testServerAddress(jellyfinUrl)
				} catch (e: Exception) {
					timber.log.Timber.e(e, "Auto-fetched address validation failed")
					false
				}

				if (isValid) {
					withContext(Dispatchers.Main) {
						onSuccess(jellyfinUrl)
					}
				} else {
					throw Exception("Fetched URL could not be validated")
				}
			}
		} catch (e: Exception) {
			timber.log.Timber.e(e, "Failed to fetch Jellyfin URL from plugin")

			withContext(Dispatchers.Main) {
				hideProgressDialog()
				AlertDialog.Builder(requireContext())
					.setTitle(R.string.server_url_fetch_failed_title)
					.setMessage(getString(R.string.server_url_fetch_failed_message, e.message))
					.setPositiveButton(R.string.server_address_manual) { _, _ ->
						showManualAddressInputDialog(onError, onSuccess)
					}
					.setNegativeButton(R.string.lbl_cancel) { _, _ ->
						onError()
					}
					.show()
			}
		}
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
									// Step 1: Ask for new local address (WHILE VPN is still running!)
									// Das ist wichtig - wir müssen das Plugin erreichen können
									showAddressInputDialog(
										server = server,
										useTailscale = false,
										onCancel = {
											// User cancelled - keep VPN running
											isSwitching = false
											rebuild()
										},
										onAddressSet = { newAddress ->
											// Step 2: Now stop VPN AFTER we got the address
											lifecycleScope.launch {
												try {
													updateProgressDialog(getString(R.string.tailscale_step_stopping_vpn))
													TailscaleManager.stopVpn()
													kotlinx.coroutines.delay(500)
													hideProgressDialog()

													// Step 3: Save settings
													startupViewModel.setServerAddress(serverUUID, newAddress)
													startupViewModel.setTailscaleEnabled(serverUUID, false)
													isSwitching = false
													showRestartDialog()
												} catch (e: Exception) {
													timber.log.Timber.e(e, "Failed to stop VPN")
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
					showRemoveServerConfirmation(serverUUID)
				}
			}
		}
	}

	private fun showRemoveServerConfirmation(serverUUID: UUID) {
		AlertDialog.Builder(requireContext())
			.setTitle(R.string.remove_server_confirm_title)
			.setMessage(R.string.remove_server_confirm_message)
			.setPositiveButton(R.string.lbl_remove) { _, _ ->
				// Delete server and restart app
				startupViewModel.deleteServer(serverUUID)
				restartApp()
			}
			.setNegativeButton(R.string.btn_cancel, null)
			.show()
	}

	private fun restartApp() {
		val context = requireActivity()
		val packageManager = context.packageManager
		val intent = packageManager.getLaunchIntentForPackage(context.packageName)
		val componentName = intent!!.component
		val mainIntent = Intent.makeRestartActivityTask(componentName)
		context.startActivity(mainIntent)
		Runtime.getRuntime().exit(0)
	}


	companion object {
		const val ARG_SERVER_UUID = "server_uuid"
	}
}