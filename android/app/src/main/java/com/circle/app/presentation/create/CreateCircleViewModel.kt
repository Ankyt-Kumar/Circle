package com.circle.app.presentation.create

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.model.CircleDraft
import com.circle.app.domain.model.PublicVenue
import com.circle.app.domain.usecase.CreateCircle
import com.circle.app.presentation.common.toUiMessage
import java.time.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

class CreateCircleViewModel(
    private val createCircle: CreateCircle,
    venues: List<PublicVenue>,
    private val saved: SavedStateHandle,
    private val events: com.circle.app.domain.usecase.EventActions? = null,
    private val getCircle: com.circle.app.domain.usecase.GetCircle? = null,
    private val editId: String? = null,
    private val drafts: com.circle.app.domain.repository.DraftRepository? = null,
    private val getAccount: com.circle.app.domain.usecase.GetAccount? = null,
) : ViewModel() {

    private var zone = runCatching {
        ZoneId.of(saved.get<String>("timeZone") ?: ZoneId.systemDefault().id)
    }.getOrDefault(ZoneId.systemDefault())

    private val mutableState = MutableStateFlow(
        CreateCircleUiState(
            editing = editId != null,
            title = saved["title"] ?: "",
            minimumAge = saved["minimumAge"] ?: 18,
            maximumAge = (saved.get<Int>("maximumAge") ?: 75).coerceAtMost(75),
            audience = saved["audience"] ?: "everyone",
            timeZone = zone.id,
            description = saved["description"] ?: "",
            category = saved["category"] ?: "coffee",
            venueId = saved["venueId"] ?: venues.firstOrNull()?.id.orEmpty(),
            date = saved["date"] ?: LocalDate.now(zone).plusDays(1).toString(),
            time = saved["time"] ?: "18:00",
            capacity = saved["capacity"] ?: 6,
            durationMinutes = saved["duration"] ?: 90,
            venues = (listOfNotNull(restoredVenue(saved)) + venues)
                .distinctBy { it.id },
            catalogReady = events == null,
        )
    )

    val state = mutableState.asStateFlow()

    private var revision = saved.get<Int>("editRevision") ?: 1
    private var setupJob: kotlinx.coroutines.Job? = null
    private var editLoaded = editId == null
    private var pendingDraft: kotlinx.coroutines.Job? = null
    private val draftLock = kotlinx.coroutines.sync.Mutex()
    private var restoringDraft = drafts != null && editId == null

    init {
        reload()

        // Optional requests must never overwrite the latest form state.
        if (events != null) {
            viewModelScope.launch {
                try {
                    val available = events.suggestionsAvailable()

                    mutableState.update { current ->
                        current.copy(aiAvailable = available)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Manual creation remains available.
                }
            }

            viewModelScope.launch {
                try {
                    val center = getAccount?.invoke()?.preferences?.area()

                    mutableState.update { current ->
                        current.copy(locationCenter = center)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The map can still be searched or moved.
                }
            }
        }
    }

    private fun savedForm(): com.circle.app.domain.model.SavedCircleForm {
        val current = state.value
        val requestId = saved.get<String>("requestId")
            ?: UUID.randomUUID().toString().also {
                saved["requestId"] = it
            }

        return com.circle.app.domain.model.SavedCircleForm(
            current.title,
            current.description,
            current.category,
            current.venueId,
            current.date,
            current.time,
            current.capacity,
            current.durationMinutes,
            requestId,
            current.minimumAge,
            current.maximumAge,
            current.audience,
            current.venues.firstOrNull { it.id == current.venueId },
            zone.id,
        )
    }

    fun close(onClosed: () -> Unit) {
        pendingDraft?.cancel()

        viewModelScope.launch {
            if (!restoringDraft && editId == null && state.value.createdId == null) {
                draftLock.withLock {
                    drafts?.save(savedForm())
                }
            }

            onClosed()
        }
    }

    fun discard() {
        if (
            state.value.saving ||
            state.value.loading ||
            restoringDraft ||
            editId != null
        ) {
            return
        }

        pendingDraft?.cancel()

        viewModelScope.launch {
            draftLock.withLock {
                drafts?.clear()
            }

            saved["requestId"] = UUID.randomUUID().toString()

            mutableState.value = state.value.copy(
                title = "",
                description = "",
                venueId = "",
                category = "coffee",
                minimumAge = 18,
                maximumAge = 75,
                audience = "everyone",
                date = LocalDate.now(zone).plusDays(1).toString(),
                time = "18:00",
                durationMinutes = 90,
                capacity = 6,
                error = null,
                draftNotice = "Draft cleared",
            )

            persistForm()
        }
    }

    fun reload(discardEdits: Boolean = false) {
        if (state.value.saving || state.value.savingVenue) {
            return
        }

        setupJob?.cancel()

        mutableState.value = state.value.copy(
            loading = true,
            catalogReady = false,
            error = null,
        )

        setupJob = viewModelScope.launch {
            try {
                if (restoringDraft) {
                    val draft = drafts?.load()

                    if (draft != null && !saved.contains("title")) {
                        saved["requestId"] = draft.requestId

                        zone = runCatching {
                            ZoneId.of(draft.timeZone)
                        }.getOrDefault(zone)

                        mutableState.value = state.value.copy(
                            title = draft.title,
                            description = draft.description,
                            minimumAge = draft.minimumAge.coerceIn(18, 75),
                            maximumAge = draft.maximumAge.coerceIn(18, 75),
                            audience = draft.audience,
                            category = draft.category,
                            venues = (
                                    listOfNotNull(draft.selectedVenue) + state.value.venues
                                    ).distinctBy { it.id },
                            timeZone = zone.id,
                            venueId = draft.venueId,
                            date = draft.date,
                            time = draft.time,
                            capacity = draft.capacity,
                            durationMinutes = draft.durationMinutes,
                            draftNotice = "Saved draft restored",
                        )
                    }

                    if (
                        draft?.selectedVenue != null &&
                        state.value.venueId == draft.venueId
                    ) {
                        mutableState.value = state.value.copy(
                            venues = (state.value.venues + draft.selectedVenue)
                                .distinctBy { it.id }
                        )
                    }

                    restoringDraft = false
                }

                if (editId != null) {
                    val existing = checkNotNull(getCircle)(editId)

                    if (!existing.isHost) {
                        mutableState.value = state.value.copy(
                            loading = false,
                            error = "You are no longer hosting this circle. " +
                                    "Go back to see its latest details.",
                        )
                        return@launch
                    }

                    val keepDraft =
                        saved.get<Boolean>("editDirty") == true && !discardEdits

                    if (keepDraft && revision != existing.revision) {
                        mutableState.value = state.value.copy(
                            loading = false,
                            editConflict = true,
                            error = "This circle changed while you were editing. " +
                                    "Reload the latest plan to discard these edits and continue.",
                        )
                        return@launch
                    }

                    revision = existing.revision
                    saved["editRevision"] = revision

                    if (!keepDraft) {
                        val start = existing.startsAt.atZone(zone)

                        val venue = PublicVenue(
                            existing.venueId,
                            existing.venue,
                            existing.neighborhood,
                            existing.venueLatitude,
                            existing.venueLongitude,
                            existing.venueFictional,
                            address = existing.venueAddress,
                        )

                        mutableState.value = state.value.copy(
                            title = existing.title,
                            description = existing.description,
                            category = existing.category,
                            minimumAge = existing.minimumAge.coerceIn(18, 75),
                            maximumAge = existing.maximumAge.coerceIn(18, 75),
                            audience = existing.audience,
                            venueId = existing.venueId,
                            venues = listOf(venue),
                            date = start.toLocalDate().toString(),
                            time = start.toLocalTime()
                                .withSecond(0)
                                .withNano(0)
                                .toString(),
                            capacity = existing.capacity,
                            durationMinutes = Duration.between(
                                existing.startsAt,
                                existing.endsAt,
                            ).toMinutes().toInt(),
                        )

                        saved["editDirty"] = false
                        persistForm()
                    }

                    editLoaded = true
                }

                mutableState.value = state.value.copy(
                    loading = false,
                    catalogReady = true,
                    editConflict = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(
                    loading = false,
                    catalogReady = false,
                    error = e.toUiMessage(),
                )
            }
        }
    }

    private fun persistForm() {
        val current = state.value

        saved["title"] = current.title
        saved["description"] = current.description
        saved["category"] = current.category
        saved["venueId"] = current.venueId
        saved["date"] = current.date
        saved["time"] = current.time
        saved["capacity"] = current.capacity
        saved["duration"] = current.durationMinutes
        saved["minimumAge"] = current.minimumAge
        saved["maximumAge"] = current.maximumAge
        saved["audience"] = current.audience
        saved["timeZone"] = zone.id

        val venue = current.venues.firstOrNull {
            it.id == current.venueId
        }

        saved["venueName"] = venue?.name
        saved["venueArea"] = venue?.neighborhood
        saved["venueAddress"] = venue?.address
        saved["venueLatitude"] = venue?.latitude
        saved["venueLongitude"] = venue?.longitude
        saved["venueFictional"] = venue?.fictional
        saved["venueSource"] = venue?.sourceUrl
        saved["venueNote"] = venue?.meetingNote
    }

    fun showVenuePicker() {
        if (
            !state.value.saving &&
            !state.value.loading &&
            !state.value.suggesting &&
            state.value.catalogReady &&
            !restoringDraft
        ) {
            mutableState.value = state.value.copy(
                venuePickerOpen = true,
                venueError = null,
            )
        }
    }

    fun hideVenuePicker() {
        if (!state.value.savingVenue) {
            mutableState.value = state.value.copy(
                venuePickerOpen = false,
                venueError = null,
            )
        }
    }

    fun saveVenue(draft: com.circle.app.domain.model.VenueDraft) {
        val service = events ?: return

        if (
            state.value.savingVenue ||
            state.value.saving ||
            state.value.loading ||
            !state.value.catalogReady
        ) {
            return
        }

        mutableState.value = state.value.copy(
            savingVenue = true,
            venueError = null,
        )

        viewModelScope.launch {
            try {
                val venue = service.createVenue(draft)

                edit { current ->
                    current.copy(
                        venueId = venue.id,
                        venues = (current.venues + venue).distinctBy { it.id },
                        catalogReady = editLoaded,
                    )
                }

                mutableState.value = state.value.copy(
                    savingVenue = false,
                    venuePickerOpen = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(
                    savingVenue = false,
                    venueError = if (
                        e is CircleException &&
                        e.reason == FailureReason.INVALID_INPUT
                    ) {
                        "Select a map pin, enter the public venue’s name and address, " +
                                "and confirm it is public."
                    } else {
                        e.toUiMessage()
                    },
                )
            }
        }
    }

    fun suggest(prompt: String) {
        val service = events ?: return

        if (
            state.value.suggesting ||
            state.value.saving ||
            state.value.loading ||
            !state.value.catalogReady ||
            !state.value.aiAvailable
        ) {
            return
        }

        mutableState.value = state.value.copy(
            suggesting = true,
            error = null,
        )

        viewModelScope.launch {
            try {
                val draft = service.suggest(prompt)

                edit { current ->
                    current.copy(
                        title = draft.title,
                        description = draft.description,
                        category = draft.category,
                        // The member must explicitly choose the venue.
                        venueId = state.value.venueId,
                        aiNote = "Suggested time: ${draft.timeSuggestion}. " +
                                "Choose and confirm the actual date/time below.",
                    )
                }

                mutableState.value = state.value.copy(
                    suggesting = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(
                    suggesting = false,
                    error = e.toUiMessage(),
                )
            }
        }
    }

    fun edit(transform: (CreateCircleUiState) -> CreateCircleUiState) {
        if (
            restoringDraft ||
            state.value.loading ||
            state.value.saving ||
            !state.value.catalogReady ||
            state.value.createdId != null
        ) {
            return
        }

        val next = transform(state.value).copy(error = null)
        mutableState.value = next
        persistForm()

        if (editId != null) {
            saved["editDirty"] = true
        }

        saved["requestId"] = UUID.randomUUID().toString()

        if (editId == null) {
            val snapshot = savedForm()
            pendingDraft?.cancel()

            pendingDraft = viewModelScope.launch {
                kotlinx.coroutines.delay(300.milliseconds)

                draftLock.withLock {
                    drafts?.save(snapshot)
                }
            }
        }
    }

    fun submit() {
        val form = state.value

        if (
            restoringDraft ||
            !editLoaded ||
            form.saving ||
            form.loading ||
            form.savingVenue ||
            form.suggesting ||
            !form.catalogReady ||
            form.createdId != null
        ) {
            return
        }

        pendingDraft?.cancel()

        val start = try {
            LocalDateTime.of(
                LocalDate.parse(form.date),
                LocalTime.parse(form.time),
            ).atZone(zone).toInstant()
        } catch (_: DateTimeException) {
            mutableState.value = form.copy(
                error = "Choose a valid date and time."
            )
            return
        }

        val requestId = saved.get<String>("requestId")
            ?: UUID.randomUUID().toString().also {
                saved["requestId"] = it
            }

        mutableState.value = form.copy(
            saving = true,
            error = null,
        )

        viewModelScope.launch {
            try {
                val draft = CircleDraft(
                    requestId,
                    form.title,
                    form.description,
                    form.category,
                    form.venueId,
                    start,
                    start.plusSeconds(form.durationMinutes * 60L),
                    form.capacity,
                    form.minimumAge,
                    form.maximumAge,
                    form.audience,
                )

                if (editId == null) {
                    draftLock.withLock {
                        drafts?.save(savedForm())
                    }
                }

                val id = if (editId != null) {
                    checkNotNull(events).edit(editId, revision, draft)
                    editId
                } else {
                    createCircle(draft).id
                }

                if (editId == null) {
                    draftLock.withLock {
                        drafts?.clear()
                    }
                }

                mutableState.value = state.value.copy(
                    saving = false,
                    createdId = id,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = state.value.copy(
                    saving = false,
                    editConflict = editId != null &&
                            e is CircleException &&
                            e.reason == FailureReason.CONFLICT,
                    catalogReady = !(
                            editId != null &&
                                    e is CircleException &&
                                    e.reason == FailureReason.CONFLICT
                            ),
                    error = when (e) {
                        is IllegalArgumentException -> e.message
                        is CircleException if e.reason == FailureReason.NOT_FOUND ->
                            "That circle was deleted. Go back and start a new create form."

                        is CircleException if e.reason == FailureReason.NETWORK ->
                            "${e.toUiMessage()} If the connection dropped, " +
                                    "retry without changing the form."

                        else -> e.toUiMessage()
                    },
                )
            }
        }
    }
}

// Save only Bundle-compatible primitives so venues survive process recreation.
private fun restoredVenue(saved: SavedStateHandle): PublicVenue? {
    val id = saved.get<String>("venueId")
        ?.takeIf { it.isNotBlank() }
        ?: return null

    val name = saved.get<String>("venueName") ?: return null

    return PublicVenue(
        id,
        name,
        saved["venueArea"] ?: "",
        saved.get<Double>("venueLatitude"),
        saved.get<Double>("venueLongitude"),
        saved["venueFictional"] ?: false,
        saved["venueSource"] ?: "",
        saved["venueNote"] ?: "",
        saved["venueAddress"] ?: "",
    )
}