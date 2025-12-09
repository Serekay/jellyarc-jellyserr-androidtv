package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ConnectedState
import org.jellyfin.androidtv.auth.model.ConnectingState
import org.jellyfin.androidtv.auth.model.UnableToConnectState
import org.jellyfin.androidtv.databinding.FragmentServerAddBinding
import org.jellyfin.androidtv.ui.startup.ServerAddViewModel
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.tailscale.TailscaleManager
import org.jellyfin.androidtv.util.getSummary
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.android.ext.android.inject
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import timber.log.Timber
import kotlinx.coroutines.runBlocking

class ServerAddFragment : Fragment() {
	companion object {
		const val ARG_SERVER_ADDRESS = "server_address"
	}

	private val startupViewModel: ServerAddViewModel by viewModel()
	private val serverRepository: ServerRepository by inject()
	private var _binding: FragmentServerAddBinding? = null
	private val binding get() = _binding!!

	private var vpnPermissionCallback: ((Boolean) -> Unit)? = null

	private val vpnPermissionLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult()
	) { result ->
		vpnPermissionCallback?.invoke(result.resultCode == Activity.RESULT_OK)
		vpnPermissionCallback = null
	}

	private val serverAddressArgument get() = arguments?.getString(ARG_SERVER_ADDRESS)?.ifBlank { null }
	private var currentDialog: AlertDialog? = null
	private var useTailscale = false

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_binding = FragmentServerAddBinding.inflate(inflater, container, false)

		with(binding.address) {
			setOnEditorActionListener { _, actionId, _ ->
				when (actionId) {
					EditorInfo.IME_ACTION_DONE -> {
						submitAddress()
						true
					}
					else -> false
				}
			}
		}

		// "Lokal verbinden" Button
		binding.connectLocal.setOnClickListener {
			Timber.d("User selected: Local connection")
			useTailscale = false
			showAddressInput()
		}

		// "Über Tailscale VPN" Button
		binding.connectTailscale.setOnClickListener {
			Timber.d("User selected: Tailscale VPN connection")
			useTailscale = true
			// Buttons disablen + Loading-Indikator
			setButtonsEnabled(false)
			binding.connectTailscale.text = "Verbinde mit Tailscale..."
			startTailscaleFlow()
		}

		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		if (serverAddressArgument != null) {
			binding.address.setText(serverAddressArgument)
			binding.address.isEnabled = false
			submitAddress()
		}

		startupViewModel.state.onEach { state ->
			when (state) {
				is ConnectingState -> {
					binding.address.isEnabled = false
					binding.connectLocal.isEnabled = false
					binding.connectTailscale.isEnabled = false
					binding.error.text = getString(R.string.server_connecting, state.address)
				}

				is UnableToConnectState -> {
					binding.address.isEnabled = true
					setButtonsEnabled(true)
					binding.error.text = getString(
						R.string.server_connection_failed_candidates,
						state.addressCandidates
							.map { "${it.key} - ${it.value.getSummary(requireContext())}" }
							.joinToString(prefix = "\n", separator = "\n")
					)
				}

				is ConnectedState -> {
					// WICHTIG: Tailscale-Flag SOFORT setzen, bevor wir zum nächsten Screen wechseln!
					// Sonst wird der Status in den Settings falsch angezeigt.
					if (useTailscale) {
						runBlocking {
							serverRepository.setTailscaleEnabled(state.id, true)
							Timber.d("Set tailscaleEnabled=true for server ${state.id}")
						}
					}
					parentFragmentManager.commit {
						replace<StartupToolbarFragment>(R.id.content_view)
						add<ServerFragment>(
							R.id.content_view,
							null,
							bundleOf(ServerFragment.ARG_SERVER_ID to state.id.toString())
						)
					}
				}

				null -> Unit
			}
		}.launchIn(lifecycleScope)
	}

	override fun onDestroyView() {
		super.onDestroyView()
		currentDialog?.dismiss()
		currentDialog = null
		_binding = null
	}

	private fun setButtonsEnabled(enabled: Boolean) {
		binding.connectLocal.isEnabled = enabled
		binding.connectTailscale.isEnabled = enabled
		if (enabled) {
			binding.connectTailscale.text = getString(R.string.connect_tailscale_button)
		}
	}

	private fun showAddressInput() {
		// Buttons ausblenden
		binding.connectionTypeLabel.isVisible = false
		binding.connectLocal.isVisible = false
		binding.connectTailscale.isVisible = false

		// Server-Adresse anzeigen
		binding.addressLabel.isVisible = true
		binding.address.isVisible = true
		binding.address.requestFocus()
	}

	/**
	 * Tailscale VPN activation flow with login code
	 */
	private fun startTailscaleFlow() {
		Timber.d("=== VPN ACTIVATION START (ServerAddFragment) ===")
		viewLifecycleOwner.lifecycleScope.launch {
			try {
				// Step 1: VPN Permission
				binding.error.text = getString(R.string.tailscale_step_permission)
				val permissionGranted = ensureVpnPermission()
				if (!permissionGranted) {
					Timber.w("VPN permission not granted")
					setButtonsEnabled(true)
					binding.error.text = ""
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.tailscale_error_title)
						.setMessage(R.string.tailscale_vpn_permission_needed)
						.setPositiveButton(R.string.lbl_ok, null)
						.show()
					return@launch
				}

				// Step 2: Stop VPN if running (clean state)
				binding.error.text = getString(R.string.tailscale_step_stopping_vpn)
				TailscaleManager.stopVpn()
				kotlinx.coroutines.delay(1000)

				// Step 3: Request login code
				binding.error.text = getString(R.string.tailscale_step_requesting_code)
				val codeResult = TailscaleManager.requestLoginCode()
				if (codeResult.isFailure) {
					binding.error.text = ""
					throw codeResult.exceptionOrNull() ?: Exception("Failed to get login code")
				}
				val code = codeResult.getOrThrow()
				Timber.d("Got login code: $code")

				// Step 4: Show code dialog and wait for login
				binding.error.text = ""
				currentDialog = AlertDialog.Builder(requireContext())
					.setTitle(R.string.tailscale_connecting_title)
					.setMessage(getString(R.string.tailscale_connecting_message, code))
					.setCancelable(false)
					.show()

				val loginFinished = TailscaleManager.waitUntilLoginFinished(timeoutMs = 120_000L)
				currentDialog?.dismiss()
				currentDialog = null

				if (!loginFinished) {
					Timber.w("Login timeout - user did not authorize device")
					setButtonsEnabled(true)
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.tailscale_login_timeout_title)
						.setMessage(R.string.tailscale_login_timeout_message)
						.setPositiveButton(R.string.lbl_ok, null)
						.show()
					return@launch
				}

				// Step 5: Start VPN
				binding.error.text = getString(R.string.tailscale_step_starting_vpn)
				val vpnStarted = TailscaleManager.startVpn()
				if (!vpnStarted) {
					binding.error.text = ""
					throw Exception("Failed to start VPN service")
				}

				// Step 6: Wait for connection
				binding.error.text = getString(R.string.tailscale_step_connecting)
				val vpnConnected = TailscaleManager.waitUntilConnected(timeoutMs = 30_000L)
				binding.error.text = ""

				if (!vpnConnected) {
					Timber.w("VPN connection timeout after successful login")
					setButtonsEnabled(true)
					AlertDialog.Builder(requireContext())
						.setTitle(R.string.tailscale_vpn_timeout_title)
						.setMessage(R.string.tailscale_vpn_timeout_message)
						.setPositiveButton(R.string.lbl_ok, null)
						.show()
					return@launch
				}

				Timber.d("VPN connected successfully - showing address input")
				showAddressInput()
				Timber.d("=== VPN ACTIVATION END ===")

			} catch (e: Exception) {
				Timber.e(e, "VPN activation failed")
				currentDialog?.dismiss()
				currentDialog = null
				binding.error.text = ""
				setButtonsEnabled(true)

				try {
					TailscaleManager.stopVpn()
				} catch (stopError: Exception) {
					Timber.w(stopError, "Failed to stop VPN after error")
				}

				AlertDialog.Builder(requireContext())
					.setTitle(R.string.tailscale_error_title)
					.setMessage(getString(R.string.tailscale_error_generic, e.message))
					.setPositiveButton(R.string.lbl_ok, null)
					.show()
			}
		}
	}


	/**
	 * Zeigt den systemweiten VPN-Permission-Dialog an, falls nötig, und wartet auf das Ergebnis.
	 */
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
			Timber.d("normalizeServerAddress: Added http:// schema: $normalized")
		}

		// Entferne trailing slashes
		normalized = normalized.trimEnd('/')

		Timber.d("normalizeServerAddress: Final address: '$address' → '$normalized'")
		return normalized
	}

	private fun submitAddress() = when {
		binding.address.text.isNotBlank() -> {
			val address = binding.address.text.toString().trim()

			// Use centralized address normalization
			val normalizedAddress = normalizeServerAddress(address)
			startupViewModel.addServer(normalizedAddress)
		}
		else -> binding.error.setText(R.string.server_field_empty)
	}
}
