package org.jellyfin.androidtv.ui.jellyseerr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.JellyseerrCast
import org.jellyfin.androidtv.data.repository.JellyseerrRepository
import org.jellyfin.androidtv.data.repository.JellyseerrRequest
import org.jellyfin.androidtv.data.repository.JellyseerrSearchItem
import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.jellyseerr.JellyseerrOverlayEntry.Detail
import org.jellyfin.androidtv.ui.jellyseerr.JellyseerrOverlayEntry.Person

	internal class JellyseerrDetailActions(
	private val repository: JellyseerrRepository,
	private val state: MutableStateFlow<JellyseerrUiState>,
	private val scope: CoroutineScope,
	private val requestActions: JellyseerrRequestActions,
	private val context: Context,
	private val loadRecentRequests: suspend () -> Unit,
) {
	fun clearOverlays() {
		state.update {
			it.copy(
				selectedItem = null,
				selectedMovie = null,
				selectedPerson = null,
				personCredits = emptyList(),
				overlayStack = emptyList(),
				requestStatusMessage = null,
				errorMessage = null,
				isLoading = false,
			)
		}
	}

	private fun currentOverlayEntry(current: JellyseerrUiState): JellyseerrOverlayEntry? = when {
		current.selectedItem != null -> Detail(current.selectedItem, current.selectedMovie)
		current.selectedPerson != null -> Person(current.selectedPerson, current.personCredits)
		else -> null
	}

	fun snapshotOverlay(): JellyseerrOverlayEntry? = currentOverlayEntry(state.value)

	fun restoreOverlay(entry: JellyseerrOverlayEntry) {
		state.update {
			when (entry) {
				is Detail -> it.copy(
					selectedItem = entry.item,
					selectedMovie = entry.details,
					selectedPerson = null,
					personCredits = emptyList(),
					overlayStack = emptyList(),
					isLoading = false,
					errorMessage = null,
					requestStatusMessage = null,
				)

				is Person -> it.copy(
					selectedItem = null,
					selectedMovie = null,
					selectedPerson = entry.person,
					personCredits = entry.credits,
					overlayStack = emptyList(),
					isLoading = false,
					errorMessage = null,
					requestStatusMessage = null,
				)
			}
		}
	}

	private fun restorePreviousOverlay(): Boolean {
		val current = state.value
		val stack = current.overlayStack
		if (stack.isEmpty()) return false

		val previous = stack.last()
		val newStack = stack.dropLast(1)

		state.update {
			when (previous) {
				is Detail -> it.copy(
					selectedItem = previous.item,
					selectedMovie = previous.details,
					selectedPerson = null,
					personCredits = emptyList(),
					overlayStack = newStack,
					isLoading = false,
					errorMessage = null,
					requestStatusMessage = null,
				)

				is Person -> it.copy(
					selectedItem = null,
					selectedMovie = null,
					selectedPerson = previous.person,
					personCredits = previous.credits,
					overlayStack = newStack,
					isLoading = false,
					errorMessage = null,
					requestStatusMessage = null,
				)
			}
		}

		return true
	}

	fun loadSeasonEpisodes(tmdbId: Int, seasonNumber: Int) {
		val key = SeasonKey(tmdbId, seasonNumber)
		val current = state.value

		if (current.seasonEpisodes.containsKey(key) || current.loadingSeasonKeys.contains(key)) {
			return
		}

		scope.launch {
			state.update {
				it.copy(
					loadingSeasonKeys = it.loadingSeasonKeys + key,
					seasonErrors = it.seasonErrors - key,
				)
			}

			val result = repository.getSeasonEpisodes(tmdbId, seasonNumber)

			state.update { updated ->
				val updatedLoading = updated.loadingSeasonKeys - key
				val updatedEpisodes = result.getOrNull()?.let { updated.seasonEpisodes + (key to it) } ?: updated.seasonEpisodes
				val updatedErrors = if (result.isFailure) {
					updated.seasonErrors + (key to (result.exceptionOrNull()?.message.orEmpty()))
				} else {
					updated.seasonErrors - key
				}

				updated.copy(
					loadingSeasonKeys = updatedLoading,
					seasonEpisodes = updatedEpisodes,
					seasonErrors = updatedErrors,
				)
			}
		}
	}

	fun request(item: JellyseerrSearchItem, seasons: List<Int>? = null) {
		if (seasons.isNullOrEmpty() && item.isRequested) return

		scope.launch {
			state.update { it.copy(errorMessage = null, requestStatusMessage = null) }

			repository.createRequest(item, seasons)
				.onSuccess {
					if (!seasons.isNullOrEmpty()) {
						markSelectedSeasonsAsRequested(item, seasons)
					}
					requestActions.refreshOwnRequests()
					refreshCurrentDetails()
					state.update {
						it.copy(requestStatusMessage = context.getString(R.string.jellyseerr_request_sended))
					}
				}
				.onFailure { error ->
					state.update {
						it.copy(errorMessage = error.message, requestStatusMessage = context.getString(R.string.jellyseerr_request_error))
					}
				}
		}
	}

	private fun markSelectedSeasonsAsRequested(item: JellyseerrSearchItem, seasons: List<Int>) {
		if (seasons.isEmpty()) return

		val requestedSeasonNumbers = seasons.toSet()

		state.update { current ->
			val updatedMovie = current.selectedMovie?.copy(
				seasons = current.selectedMovie.seasons.map { season ->
					if (season.seasonNumber in requestedSeasonNumbers) season.copy(status = 1) else season
				},
			)

			val updatedItem = current.selectedItem?.takeIf { it.id == item.id }?.copy(
				isRequested = true,
				requestStatus = 1,
			)

			current.copy(
				selectedMovie = updatedMovie ?: current.selectedMovie,
				selectedItem = updatedItem ?: current.selectedItem,
			)
		}
	}

	fun showDetailsForItem(item: JellyseerrSearchItem) {
		val current = state.value
		val newStack = currentOverlayEntry(current)?.let { current.overlayStack + it } ?: emptyList()
		state.update {
			it.copy(
				isLoading = true,
				errorMessage = null,
				selectedItem = item,
				selectedMovie = null,
				selectedPerson = null,
				personCredits = emptyList(),
				overlayStack = newStack,
			)
		}

		scope.launch {
			val result = when (item.mediaType) {
				"movie" -> repository.getMovieDetails(item.id)
				"tv" -> repository.getTvDetails(item.id)
				else -> {
					state.update { it.copy(isLoading = false) }
					return@launch
				}
			}

			result
				.onSuccess { details ->
					val updatedItem = if (item.mediaType == "tv") {
						val seasons = details.seasons.filter { it.seasonNumber > 0 }
						val availableSeasons = seasons.count { it.status == 5 }
						val totalSeasons = seasons.size

						val isPartiallyAvailable = availableSeasons > 0 && availableSeasons < totalSeasons
						val isFullyAvailable = availableSeasons == totalSeasons && totalSeasons > 0

						item.copy(
							isPartiallyAvailable = isPartiallyAvailable,
							isAvailable = isFullyAvailable
						)
					} else {
						item
					}

					state.update {
						it.copy(
							isLoading = false,
							selectedMovie = details,
							selectedItem = updatedItem,
							overlayStack = newStack,
						)
					}
				}
				.onFailure { error ->
					state.update {
						it.copy(
							isLoading = false,
							errorMessage = error.message,
						)
					}
				}
		}
	}

	fun showDetailsForItemFromPerson(item: JellyseerrSearchItem) {
		showDetailsForItem(item)
	}

	fun showDetailsForRequest(request: JellyseerrRequest) {
		val current = state.value
		val newStack = currentOverlayEntry(current)?.let { current.overlayStack + it } ?: emptyList()
		state.update {
			it.copy(
				isLoading = true,
				errorMessage = null,
				selectedItem = null,
				selectedMovie = null,
				selectedPerson = null,
				personCredits = emptyList(),
				overlayStack = newStack,
			)
		}

		val tmdbId = request.tmdbId ?: return

		scope.launch {
			val mediaType = request.mediaType ?: "movie"

			val result = when (mediaType) {
				"movie" -> repository.getMovieDetails(tmdbId)
				"tv" -> repository.getTvDetails(tmdbId)
				else -> {
					state.update { it.copy(isLoading = false) }
					return@launch
				}
			}

			result
				.onSuccess { details ->
					state.update {
						it.copy(
							isLoading = false,
							selectedMovie = details,
						)
					}
				}
				.onFailure { error ->
					state.update {
						it.copy(
							isLoading = false,
							errorMessage = error.message,
						)
					}
				}
		}
	}

	fun refreshCurrentDetails() {
		val currentItem = state.value.selectedItem ?: return

		scope.launch {
			val result = when (currentItem.mediaType) {
				"movie" -> repository.getMovieDetails(currentItem.id)
				"tv" -> repository.getTvDetails(currentItem.id)
				else -> return@launch
			}

			result.onSuccess { details ->
				val updatedItem = if (currentItem.mediaType == "tv") {
					val seasons = details.seasons.filter { it.seasonNumber > 0 }
					val availableSeasons = seasons.count { it.status == 5 }
					val totalSeasons = seasons.size

					val isPartiallyAvailable = availableSeasons > 0 && availableSeasons < totalSeasons
					val isFullyAvailable = availableSeasons == totalSeasons && totalSeasons > 0

					currentItem.copy(
						isPartiallyAvailable = isPartiallyAvailable,
						isAvailable = isFullyAvailable
					)
				} else {
					currentItem
				}

				state.update {
					it.copy(
						selectedMovie = details,
						selectedItem = updatedItem,
					)
				}
			}
		}
	}

	fun closeDetails() {
		if (!restorePreviousOverlay()) {
			state.update {
				it.copy(
					selectedItem = null,
					selectedMovie = null,
					requestStatusMessage = null,
					overlayStack = emptyList(),
				)
			}
			scope.launch {
				requestActions.refreshOwnRequests()
				loadRecentRequests()
			}
		}
	}

	fun showPerson(person: JellyseerrCast) {
		scope.launch {
			val current = state.value
			val newStack = currentOverlayEntry(current)?.let { current.overlayStack + it } ?: emptyList()

			state.update {
				it.copy(
					isLoading = true,
					errorMessage = null,
					overlayStack = newStack,
				)
			}

			val detailsResult = repository.getPersonDetails(person.id)
			val creditsResult = repository.getPersonCredits(person.id)

			if (detailsResult.isFailure || creditsResult.isFailure) {
				val error = detailsResult.exceptionOrNull() ?: creditsResult.exceptionOrNull()
				state.update {
					it.copy(isLoading = false, errorMessage = error?.message)
				}
				return@launch
			}

			val details = detailsResult.getOrThrow()
			val credits = creditsResult.getOrThrow()
			val withAvailability = repository.markAvailableInJellyfin(credits).getOrElse { credits }
			val marked = JellyseerrRequestMarkers.markItemsWithRequests(withAvailability, state.value.ownRequests)

			state.update {
				it.copy(
					isLoading = false,
					selectedPerson = details,
					personCredits = marked,
					selectedItem = null,
					selectedMovie = null,
					overlayStack = newStack,
				)
			}
		}
	}

	fun showPersonFromSearchItem(item: JellyseerrSearchItem) {
		showPerson(
			JellyseerrCast(
				id = item.id,
				name = item.title,
				profilePath = item.profilePath,
			),
		)
	}

	fun closePerson() {
		if (!restorePreviousOverlay()) {
			state.update {
				it.copy(
					selectedPerson = null,
					personCredits = emptyList(),
					overlayStack = emptyList(),
				)
			}
			scope.launch {
				requestActions.refreshOwnRequests()
				loadRecentRequests()
			}
		}
	}
}
