package com.circle.app.presentation.discover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.model.Area
import com.circle.app.domain.model.Circle
import com.circle.app.domain.model.CircleQuery
import com.circle.app.domain.model.UserPreferences
import com.circle.app.domain.usecase.GetNearbyCircles
import com.circle.app.presentation.common.toUiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration

class DiscoverViewModel(
    private val getNearbyCircles: GetNearbyCircles,
    private val areas: List<Area>,
    private val savedState: SavedStateHandle,
    initialPreferences: UserPreferences? = null,
    preferences: Flow<UserPreferences?> = flowOf(null),
    private val events: com.circle.app.domain.usecase.EventActions? = null,
    private val updateLocation: com.circle.app.domain.usecase.UpdateDiscoveryPreferences? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val initialArea = initialPreferences?.area() ?: areas.firstOrNull() ?: Area("", 0.0, 0.0)
    private val mutable = MutableStateFlow(DiscoverUiState(
        area = initialArea, hasLocation = initialPreferences?.area() != null || areas.isNotEmpty(),
        radius = initialPreferences?.radiusKm ?: 5,
        category = savedState.get<String>("category") ?: "",
    ))
    val state = mutable.asStateFlow()
    private var loadJob: Job? = null
    private var lastDefaults = initialPreferences

    init {
        viewModelScope.launch { preferences.collect { if (!state.value.savingLocation) applyPreferences(it) } }
        if (events != null) viewModelScope.launch {
            try { mutable.update { it.copy(categories = events.categories()) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Built-in filters remain available offline. */ }
        }
    }

    fun setForYou(value: Boolean) {
        if (state.value.savingLocation) return
        mutable.update { it.copy(forYou = value, circles = emptyList(), nextCursor = "", offline = false) }
        refresh()
    }

    fun applyPreferences(preferences: UserPreferences?) {
        if (preferences == null || !preferences.valid() || preferences == lastDefaults) return
        val area = preferences.area() ?: return
        lastDefaults = preferences
        mutable.update { it.copy(area = area, hasLocation = true, radius = preferences.radiusKm,
            circles = emptyList(), nextCursor = "", offline = false) }
        refresh()
    }

    fun chooseArea(area: Area) { saveLocation(area, state.value.radius) }
    fun chooseRadius(radius: Int) { if (state.value.hasLocation) saveLocation(state.value.area, radius.coerceIn(1,10)) }

    private fun saveLocation(area: Area, radius: Int) {
        if (state.value.savingLocation || !com.circle.app.domain.model.validCoordinates(area.latitude, area.longitude)) return
        if (area == state.value.area && radius == state.value.radius && state.value.hasLocation) return
        loadJob?.cancel(); loadMoreJob?.cancel(); version++
        mutable.update { it.copy(savingLocation = true, loading = false, loadingMore = false, error = null) }
        viewModelScope.launch {
            try {
                val updated = updateLocation?.invoke(area, radius)
                if (updated != null) lastDefaults = updated.preferences
                mutable.update { it.copy(area = updated?.preferences?.area() ?: area,
                    radius = updated?.preferences?.radiusKm ?: radius, hasLocation = true,
                    savingLocation = false, updatedAccount = updated, circles = emptyList(), nextCursor = "", offline = false) }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(savingLocation = false, error = e.toUiMessage()) } }
        }
    }

    fun accountUpdateHandled() { mutable.update { it.copy(updatedAccount = null) } }

    fun chooseCategory(category: String) {
        val value = category.trim()
        if (value.isNotEmpty() && !com.circle.app.domain.model.validCategory(value)) return
        savedState["category"] = value
        mutable.update { it.copy(category = value, circles = emptyList(), nextCursor = "", offline = false) }
        refresh()
    }

    private var loadMoreJob: Job? = null
    private var version = 0

    private fun upcoming(circles: List<Circle>): List<Circle> {
        val now = clock.instant()
        return circles.filter { it.isDiscoverableAt(now) }
    }

    fun removeInactiveCircles() {
        mutable.update { it.copy(circles = upcoming(it.circles)) }
    }

    fun nextExpiryDelayMillis(): Long {
        val next = state.value.circles.minOfOrNull { minOf(it.startsAt, it.endsAt) }
            ?: return 60_000L
        // Check wall-clock changes at least once a minute while Discover is visible.
        return Duration.between(clock.instant(), next).toMillis().coerceIn(1L, 60_000L)
    }

    fun refresh() {
        removeInactiveCircles()
        if (state.value.savingLocation) return
        if (!state.value.hasLocation) { mutable.update { it.copy(loading = false) }; return }
        loadJob?.cancel()
        loadMoreJob?.cancel()
        version++
        val query = state.value.let { CircleQuery(it.area, it.radius, it.category) }
        val recommended = state.value.forYou
        loadJob =
            viewModelScope.launch {
                mutable.update {
                    it.copy(loading = true, loadingMore = false, error = null, moreError = null)
                }
                try {
                    val recommendations = if (recommended) events?.recommend(query) else null
                    val page =
                        if (recommendations != null)
                            com.circle.app.domain.model.CircleFeed(
                                recommendations.circles,
                                isOffline = recommendations.offline,
                                savedAt = recommendations.savedAt,
                            )
                        else getNearbyCircles.page(query)
                    mutable.update {
                        it.copy(
                            circles = upcoming(page.circles),
                            categories = (it.categories + page.circles.map { circle -> circle.category }).distinct(),
                            nextCursor = page.nextCursor,
                            offline = page.isOffline,
                            savedAt = page.savedAt,
                            loading = false,
                            basis = recommendations?.basis ?: "nearby",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only retain in-memory data for a network failure, never for revoked access.
                    val network =
                        e is com.circle.app.domain.error.CircleException &&
                            e.reason == com.circle.app.domain.error.FailureReason.NETWORK
                    mutable.update {
                        it.copy(
                            circles = if (network) upcoming(it.circles) else emptyList(),
                            loading = false,
                            offline = network,
                            error = e.toUiMessage(),
                            nextCursor = "",
                        )
                    }
                }
            }
    }

    fun loadMore() {
        removeInactiveCircles()
        val s = state.value
        if (s.loading || s.loadingMore || s.offline || s.nextCursor.isBlank()) return
        val requestVersion = version
        val query = CircleQuery(s.area, s.radius, s.category)
        mutable.update { it.copy(loadingMore = true, moreError = null) }
        loadMoreJob =
            viewModelScope.launch {
                try {
                    val page = getNearbyCircles.page(query, s.nextCursor)
                    if (version == requestVersion)
                        mutable.update {
                            it.copy(
                                circles = upcoming((it.circles + page.circles).distinctBy { c -> c.id }),
                                categories = (it.categories + page.circles.map { circle -> circle.category }).distinct(),
                                nextCursor = page.nextCursor,
                                loadingMore = false,
                            )
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (version == requestVersion)
                        mutable.update { it.copy(circles = upcoming(it.circles), loadingMore = false, moreError = e.toUiMessage()) }
                }
            }
    }
}
