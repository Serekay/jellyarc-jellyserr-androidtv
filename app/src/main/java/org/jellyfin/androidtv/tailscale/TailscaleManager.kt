package org.jellyfin.androidtv.tailscale

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.tailscale.ipn.App
import com.tailscale.ipn.IPNService
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libtailscale.LocalAPIResponse
import org.jellyfin.androidtv.auth.model.Server
import timber.log.Timber
import org.json.JSONObject
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manager backed by the echte Tailscale-Android Backend (App + LocalAPI + Notifier).
 * Unterstützt per-Server Flag (tailscaleEnabled) und liefert Login-Code über Notifier.browseToURL.
 */
object TailscaleManager {
	private var appContext: Context? = null
	private var app: App? = null
	private var client: Client? = null
	@Volatile private var lastInitError: String? = null
	@Volatile private var started = false

	fun init(context: Context) {
		appContext = context.applicationContext
	}

	/**
	 * Checks if the user is likely logged in to Tailscale.
	 * This is a heuristic: if state is not NeedsLogin, we assume yes.
	 */
	suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
		ensureApp().getOrNull() ?: return@withContext false
		val state = Notifier.state.value
		// If the state is anything other than NeedsLogin, we assume some form of authentication exists.
		return@withContext state != Ipn.State.NeedsLogin
	}

	/**
	 * Synchronously checks if the VPN is considered active.
	 * @return true if the state is Running.
	 */
	fun isVpnActive(): Boolean {
		return Notifier.state.value == Ipn.State.Running
	}


	/**
	 * Prüft, ob Tailscale aktuell verbunden ist (State == Running)
	 */
	suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
		ensureApp().onFailure { return@withContext false }
		// Warte kurz auf State-Update
		val state = withTimeoutOrNull(2000L) {
			Notifier.state.first { it != Ipn.State.NoState }
		}
		state == Ipn.State.Running
	}

	/**
	 * Wartet, bis der Login abgeschlossen ist.
	 * Beobachtet BEIDE Events: loginFinished UND State (wie die offizielle App!)
	 *
	 * Android 10 Kompatibilität: Defensive error handling für StateFlow crashes
	 */
	suspend fun waitUntilLoginFinished(timeoutMs: Long = 120_000L): Boolean = withContext(Dispatchers.IO) {
		try {
			ensureApp().onFailure {
				Timber.e("waitUntilLoginFinished: App not initialized")
				return@withContext false
			}

			Timber.d("waitUntilLoginFinished: Waiting for login completion (timeout=${timeoutMs}ms)")

			// Aktuellen State und loginFinished-Wert loggen - mit defensive null checks
			val currentState = try { Notifier.state.value } catch (e: Exception) {
				Timber.w(e, "Failed to get current state")
				Ipn.State.NoState
			}
			val currentLoginFinished = try { Notifier.loginFinished.value } catch (e: Exception) {
				Timber.w(e, "Failed to get loginFinished")
				""
			}
			Timber.d("waitUntilLoginFinished: Current state: $currentState, loginFinished: '$currentLoginFinished'")

			// Warte auf ENTWEDER loginFinished Event ODER State wird Running
			// (Die offizielle App wartet nur auf State.Running, aber wir beobachten beides)
			var success = false
			val result = withTimeoutOrNull(timeoutMs) {
				try {
					// Beobachte beide Flows gleichzeitig
					kotlinx.coroutines.flow.combine(
						Notifier.state,
						Notifier.loginFinished
					) { state, loginFinished ->
						Timber.d("waitUntilLoginFinished: Update - state=$state, loginFinished='$loginFinished'")

						// Login ist erfolgreich wenn:
						// 1. State ist Running ODER
						// 2. loginFinished hat einen neuen nicht-leeren Wert
						val stateIsRunning = state == Ipn.State.Running
						val loginFinishedReceived = !loginFinished.isNullOrEmpty() && loginFinished != currentLoginFinished

						if (stateIsRunning || loginFinishedReceived) {
							Timber.d("waitUntilLoginFinished: Login complete! (stateRunning=$stateIsRunning, loginFinished=$loginFinishedReceived)")
							success = true
							true
						} else {
							false
						}
					}.first { it }
				} catch (e: Exception) {
					Timber.e(e, "waitUntilLoginFinished: Error in Flow.combine (Android 10 compatibility issue?)")
					// Fallback: Nur auf State warten
					try {
						Notifier.state.first { state ->
							val isRunning = state == Ipn.State.Running
							if (isRunning) {
								success = true
								Timber.d("waitUntilLoginFinished: Fallback - State is Running")
							}
							isRunning
						}
					} catch (e2: Exception) {
						Timber.e(e2, "waitUntilLoginFinished: Fallback also failed")
					}
				}
			}

			if (success) {
				Timber.d("waitUntilLoginFinished: SUCCESS")
			} else {
				Timber.e("waitUntilLoginFinished: TIMEOUT")
				try {
					val finalState = Notifier.state.value
					val finalLoginFinished = Notifier.loginFinished.value
					Timber.e("waitUntilLoginFinished: Final state=$finalState, loginFinished='$finalLoginFinished'")
				} catch (e: Exception) {
					Timber.e(e, "waitUntilLoginFinished: Could not get final state")
				}
			}

			success
		} catch (e: Exception) {
			Timber.e(e, "waitUntilLoginFinished: Unexpected error")
			false
		}
	}

	/**
	 * Wartet, bis Tailscale den State "Running" erreicht (max 60 Sekunden)
	 * Für Auto-Start beim App-Start (nicht für initialen Login!)
	 */
	suspend fun waitUntilConnected(timeoutMs: Long = 60_000L): Boolean = withContext(Dispatchers.IO) {
		ensureApp().onFailure {
			Timber.e("waitUntilConnected: App not initialized")
			return@withContext false
		}

		Timber.d("waitUntilConnected: Waiting for State.Running (timeout=${timeoutMs}ms)")

		val result = withTimeoutOrNull(timeoutMs) {
			Notifier.state.first { state ->
				Timber.d("waitUntilConnected: State update: $state")
				state == Ipn.State.Running
			}
		}

		if (result != null) {
			Timber.d("waitUntilConnected: SUCCESS - State is Running")
		} else {
			Timber.e("waitUntilConnected: TIMEOUT")
			val finalState = Notifier.state.value
			Timber.e("waitUntilConnected: Final state: $finalState")
		}

		result != null
	}

	private fun ensureApp(): Result<Unit> {
		return try {
			val a = App.get()
			// init backend if needed
			a.getLibtailscaleApp()
			app = a
			client = Client(a.applicationScope)
			lastInitError = null
			Result.success(Unit)
		} catch (t: Throwable) {
			lastInitError = t.message ?: t.toString()
			Timber.e(t, "Failed to init Tailscale App")
			Result.failure(t)
		}
	}

	private fun localApi(
		method: String,
		path: String,
		body: ByteArray? = null,
		timeoutMs: Long = 10_000L,
	): LocalAPIResponse? {
		val go = app?.getLibtailscaleApp() ?: return null
		val fullPath = if (path.startsWith("/")) "$LOCAL_API_PREFIX${path.removePrefix("/")}" else "$LOCAL_API_PREFIX$path"
		return try {
			go.callLocalAPI(timeoutMs, method, fullPath, body?.let { TailscaleStreams.inputStream(it) })
		} catch (t: Throwable) {
			Timber.e(t, "LocalAPI call failed for $fullPath")
			null
		}
	}

	/**
	 * Send a minimal "start" to bring up the LocalAPI backend. Idempotent.
	 * WICHTIG: Setzt KEINE Prefs, damit bestehende VPN-Verbindungen nicht unterbrochen werden!
	 */
	private suspend fun ensureStarted(): Result<Unit> {
		if (started) return Result.success(Unit)
		val c = client ?: return Result.failure(IllegalStateException("Client not ready"))
		val opts = Ipn.Options(
			FrontendLogID = "jellyfin-androidtv",
			UpdatePrefs = null  // KEINE Prefs setzen! Sonst wird WantRunning überschrieben!
		)
		return runCatching {
			suspendCancellableCoroutine { cont ->
				c.start(opts) { result ->
					if (result.isSuccess) cont.resume(Unit)
					else cont.resumeWithException(result.exceptionOrNull() ?: Exception("start failed"))
				}
			}
		}.onSuccess { started = true }.map { }
	}

	/**
	 * Fordert einen Login-Code an, indem der offizielle Flow verwendet wird:
	 * 1. Logout + reset-auth (um frischen State zu haben)
	 * 2. WantRunning=false, LoggedOut=true setzen
	 * 3. Client.start() aufrufen
	 * 4. Client.startLoginInteractive() aufrufen
	 * 5. Auf Notifier.browseToURL warten (dort kommt die Login-URL mit Code)
	 */
	suspend fun requestLoginCode(): Result<String> = withContext(Dispatchers.IO) {
		// Defensive check für Android 10 Kompatibilität
		val appInstance = try {
			ensureApp().getOrThrow()
			app
		} catch (e: Exception) {
			Timber.e(e, "requestLoginCode: Failed to initialize Tailscale app")
			return@withContext Result.failure(IllegalStateException("Tailscale not initialized: ${e.message}"))
		}

		if (appInstance == null) {
			Timber.e("requestLoginCode: App instance is null after initialization")
			return@withContext Result.failure(IllegalStateException("Tailscale app instance is null"))
		}

		// Fresh login vorbereiten (logout + reset-auth)
		Timber.d("requestLoginCode: preparing fresh login (logout + reset-auth)")
		try {
			logout()
			resetAuth()
		} catch (e: Exception) {
			Timber.w(e, "requestLoginCode: logout/resetAuth failed (continuing anyway)")
		}

		// Prefs setzen: LoggedOut=true, WantRunning=false
		val c = client ?: return@withContext Result.failure(IllegalStateException("Client not ready"))
		val prefsResult = runCatching {
			suspendCancellableCoroutine<Unit> { cont ->
				c.editPrefs(Ipn.MaskedPrefs().apply {
					LoggedOut = true
					WantRunning = false
				}) { result ->
					if (result.isSuccess) cont.resume(Unit)
					else cont.resumeWithException(result.exceptionOrNull() ?: Exception("editPrefs failed"))
				}
			}
		}
		if (prefsResult.isFailure) {
			Timber.e(prefsResult.exceptionOrNull(), "Failed to set prefs for fresh login")
		}

		// Client.start() aufrufen (bringt Backend in korrekten State)
		val opts = Ipn.Options(
			FrontendLogID = "jellyfin-androidtv",
			UpdatePrefs = null  // Keine Prefs hier, haben wir schon gesetzt
		)
		val startResult = runCatching {
			suspendCancellableCoroutine<Unit> { cont ->
				c.start(opts) { result ->
					if (result.isSuccess) cont.resume(Unit)
					else cont.resumeWithException(result.exceptionOrNull() ?: Exception("start failed"))
				}
			}
		}
		startResult.onFailure {
			Timber.e(it, "Client.start() failed")
			return@withContext Result.failure(it)
		}
		started = true
		Timber.d("requestLoginCode: Client.start() succeeded")

		// WICHTIG: Vor startLoginInteractive den browseToURL Flow schon beobachten
		// (sonst verpassen wir ggf. den Event)
		// Defensive: Nutze CoroutineScope statt app.applicationScope für bessere Android 10 Kompatibilität
		val loginUrlDeferred = CoroutineScope(Dispatchers.IO).async {
			Timber.d("Waiting for Notifier.browseToURL...")
			try {
				withTimeoutOrNull(30_000L) {
					// Warte auf einen nicht-null Wert
					Notifier.browseToURL.first { it != null }
				}
			} catch (e: Exception) {
				Timber.e(e, "Error waiting for browseToURL")
				null
			}
		}

		// startLoginInteractive aufrufen (triggert browseToURL-Event im Backend)
		Timber.d("requestLoginCode: calling startLoginInteractive()")
		val loginResult = runCatching {
			suspendCancellableCoroutine<Unit> { cont ->
				c.startLoginInteractive { result ->
					if (result.isSuccess) cont.resume(Unit)
					else cont.resumeWithException(result.exceptionOrNull() ?: Exception("startLoginInteractive failed"))
				}
			}
		}
		loginResult.onFailure {
			Timber.e(it, "startLoginInteractive failed")
			return@withContext Result.failure(it)
		}
		Timber.d("requestLoginCode: startLoginInteractive() succeeded, waiting for browseToURL...")

		// Warte auf die Login-URL vom Notifier
		val loginUrl = loginUrlDeferred.await()
		if (loginUrl == null) {
			Timber.e("Timeout waiting for login URL from Notifier.browseToURL")
			return@withContext Result.failure(LoginCodeTimeoutException())
		}

		Timber.d("requestLoginCode: received login URL: $loginUrl")

		// Extrahiere den Code aus der URL (z.B. https://login.tailscale.com/a/ABC123DEF)
		val code = extractCodeFromUrl(loginUrl)
		if (code.isEmpty()) {
			Timber.e("Could not extract code from login URL: $loginUrl")
			return@withContext Result.failure(IllegalStateException("No Code in Login-URL gefunden: $loginUrl"))
		}

		Timber.d("requestLoginCode: extracted code: $code")
		Result.success(code)
	}

	private fun extractCodeFromUrl(url: String): String {
		// Tailscale Login-URLs haben das Format: https://login.tailscale.com/a/CODE
		// oder können den Code direkt enthalten
		return Regex("https?://[^/]+/a/(\\w+)").find(url)?.groupValues?.getOrNull(1)
			?: Regex("([A-Z0-9]{6,12})").find(url)?.value
			?: ""
	}

	/**
	 * Startet den Tailscale VPN.
	 * Gibt true zurück wenn erfolgreich, false wenn VPN-Permission benötigt wird.
	 */
	suspend fun startVpn(): Boolean = withContext(Dispatchers.IO) {
		val ctx = appContext ?: app?.applicationContext
			?: run {
				Timber.w("startVpn: No application context available yet")
				return@withContext false
			}
		ensureApp().onFailure { return@withContext false }
		ensureStarted().onFailure { return@withContext false }

		// Request VPN permission if needed
		val prepareIntent = VpnService.prepare(ctx)
		if (prepareIntent != null) {
			Timber.d("startVpn: VPN permission needed, launching permission dialog")
			prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			withContext(Dispatchers.Main) {
				ctx.startActivity(prepareIntent)
			}
			return@withContext false
		}

		// Setze WantRunning=true UND CorpDNS=true (für MagicDNS!)
		val c = client ?: return@withContext false
		val result = runCatching {
			suspendCancellableCoroutine<Unit> { cont ->
				c.editPrefs(Ipn.MaskedPrefs().apply {
					WantRunning = true
					CorpDNS = true  // MagicDNS aktivieren!
					RouteAll = false // Split-Tunnel: Nur Tailscale-IPs durch VPN, Rest lokal
					LoggedOut = false // Sicherstellen, dass wir nach Login nicht im Logout-Status hängen
				}) { result ->
					if (result.isSuccess) cont.resume(Unit)
					else cont.resumeWithException(result.exceptionOrNull() ?: Exception("editPrefs failed"))
				}
			}
		}

		if (result.isSuccess) {
			Timber.d("startVpn: Prefs set successfully (WantRunning=true, CorpDNS=true, RouteAll=false)")
			// Verwende NUR die offizielle API - KEIN direkter Service-Start!
			// Das SDK kümmert sich um den VPN-Service-Start
			app?.startVPN()
			Timber.d("startVpn: Called app.startVPN(), VPN service should start now")
		} else {
			Timber.e(result.exceptionOrNull(), "startVpn: failed to set prefs")
		}

		result.isSuccess
	}

	fun stopVpn() {
		app?.setWantRunning(false)
		app?.stopVPN()
	}

	/**
	 * Startet den VPN automatisch, wenn für diesen Server Tailscale aktiviert ist.
	 * Prüft erst ob schon verbunden, startet nur wenn nötig.
	 */
	suspend fun autoStartIfEnabled(server: Server?): Boolean {
		if (server?.tailscaleEnabled != true) {
			Timber.d("autoStartIfEnabled: Server has Tailscale disabled, skipping")
			return false
		}

		Timber.d("autoStartIfEnabled: Starting Tailscale VPN for server ${server.name}")

		// Erst prüfen ob schon verbunden
		val alreadyConnected = isConnected()
		if (alreadyConnected) {
			Timber.d("autoStartIfEnabled: Already connected, nothing to do!")
			return true
		}

		Timber.d("autoStartIfEnabled: Not connected, starting VPN...")
		val started = startVpn()

		// Warte bis verbunden (max 60 Sek beim Auto-Start)
		// Wenn startVpn() false liefert (z.B. weil VPN-Permission-Dialog offen ist),
		// warten wir trotzdem, damit ein anschließendes Akzeptieren noch erfasst wird.
		Timber.d("autoStartIfEnabled: Waiting for VPN connection... (startSuccess=$started)")
		val connected = waitUntilConnected(timeoutMs = 60_000L)
		if (connected) {
			Timber.d("autoStartIfEnabled: SUCCESS - VPN auto-started")
		} else {
			Timber.e("autoStartIfEnabled: TIMEOUT - VPN connection failed to establish")
		}
		return connected
	}

	private const val LOCAL_API_PREFIX = "/localapi/v0/"

	private fun resetAuth(): Result<Unit> {
		val resp = localApi("POST", "reset-auth")
			?: return Result.failure(IllegalStateException("reset-auth: no response"))
		val body = runCatching { resp.bodyBytes() }.getOrNull()?.toString(Charsets.UTF_8).orEmpty()
		return if (resp.statusCode().toInt() in 200..299) {
			Result.success(Unit)
		} else {
			Timber.e("reset-auth failed status=${resp.statusCode()} body=$body")
			Result.failure(IllegalStateException("reset-auth failed (${resp.statusCode()}): $body"))
		}
	}

	suspend fun resetAuthPublic(): Result<Unit> = withContext(Dispatchers.IO) {
		resetAuth()
	}

	/**
	 * Sucht einen Peer anhand des Hostnamens und gibt dessen primäre Tailscale-IP zurück.
	 * Verwendet die LocalAPI /status Antwort.
	 *
	 * @param hostname Der Hostname des Peers
	 * @return Die IP-Adresse des Peers oder null wenn nicht gefunden
	 */
	suspend fun getPeerIpByHostname(hostname: String): String? = withContext(Dispatchers.IO) {
		ensureApp().onFailure {
			Timber.e("getPeerIpByHostname: App not initialized")
			return@withContext null
		}

		val statusResp = localApi("GET", "status")
		if (statusResp == null) {
			Timber.e("getPeerIpByHostname: LocalAPI /status returned null")
			return@withContext null
		}

		val bodyBytes = runCatching { statusResp.bodyBytes() }.getOrNull() ?: ByteArray(0)
		val bodyStr = bodyBytes.toString(Charsets.UTF_8)

		if (statusResp.statusCode().toInt() !in 200..299) {
			Timber.e("getPeerIpByHostname: LocalAPI /status failed with code ${statusResp.statusCode()}")
			return@withContext null
		}

		runCatching {
			val json = JSONObject(bodyStr)
			val peers = json.optJSONObject("Peer") ?: return@withContext null

			// Suche durch alle Peers
			for (peerKey in peers.keys()) {
				val peer = peers.optJSONObject(peerKey) ?: continue
				val peerHostName = peer.optString("HostName", "")

				if (peerHostName.equals(hostname, ignoreCase = true)) {
					// Gefunden! Hole die erste IP
					val ips = peer.optJSONArray("TailscaleIPs")
					if (ips != null && ips.length() > 0) {
						val ip = ips.getString(0)
						Timber.d("getPeerIpByHostname: Found peer '$hostname' with IP: $ip")
						return@withContext ip
					}
				}
			}

			Timber.w("getPeerIpByHostname: Peer '$hostname' not found in network")
			null
		}.getOrElse { error ->
			Timber.e(error, "getPeerIpByHostname: Failed to parse status JSON")
			null
		}
	}

	/**
	 * Gibt alle verfügbaren Peers mit ihren Hostnamen und IPs zurück.
	 * Nützlich für eine Auswahlliste.
	 *
	 * @return Liste von Pairs (hostname, ip) oder leere Liste bei Fehler
	 */
	suspend fun getAllPeers(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
		ensureApp().onFailure {
			Timber.e("getAllPeers: App not initialized")
			return@withContext emptyList()
		}

		val statusResp = localApi("GET", "status")
		if (statusResp == null) {
			Timber.e("getAllPeers: LocalAPI /status returned null")
			return@withContext emptyList()
		}

		val bodyBytes = runCatching { statusResp.bodyBytes() }.getOrNull() ?: ByteArray(0)
		val bodyStr = bodyBytes.toString(Charsets.UTF_8)

		if (statusResp.statusCode().toInt() !in 200..299) {
			Timber.e("getAllPeers: LocalAPI /status failed with code ${statusResp.statusCode()}")
			return@withContext emptyList()
		}

		runCatching {
			val json = JSONObject(bodyStr)
			val peers = json.optJSONObject("Peer") ?: return@withContext emptyList()
			val result = mutableListOf<Pair<String, String>>()

			// Sammle alle Peers
			for (peerKey in peers.keys()) {
				val peer = peers.optJSONObject(peerKey) ?: continue
				val peerHostName = peer.optString("HostName", "")
				val ips = peer.optJSONArray("TailscaleIPs")

				if (peerHostName.isNotEmpty() && ips != null && ips.length() > 0) {
					val ip = ips.getString(0)
					result.add(Pair(peerHostName, ip))
					Timber.d("getAllPeers: Found peer '$peerHostName' -> $ip")
				}
			}

			result
		}.getOrElse { error ->
			Timber.e(error, "getAllPeers: Failed to parse status JSON")
			emptyList()
		}
	}

	private fun logout(): Result<Unit> {
		val resp = localApi("POST", "logout")
			?: return Result.failure(IllegalStateException("logout: no response"))
		val body = runCatching { resp.bodyBytes() }.getOrNull()?.toString(Charsets.UTF_8).orEmpty()
		return if (resp.statusCode().toInt() in 200..299) {
			Result.success(Unit)
		} else {
			Timber.e("logout failed status=${resp.statusCode()} body=$body")
			Result.failure(IllegalStateException("logout failed (${resp.statusCode()}): $body"))
		}
	}

}

/**
 * Exception für Login-Code Timeout (Android TV Bug - TV muss neugestartet werden)
 */
class LoginCodeTimeoutException : Exception("Login code timeout - TV restart required")
