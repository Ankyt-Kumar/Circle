package com.circle.app.domain.usecase

import com.circle.app.domain.model.*
import com.circle.app.domain.repository.CommunityRepository

class ChatActions(private val repository: CommunityRepository) {
    suspend fun load(id: String, before: Long = 0) = repository.messages(id, before)

    suspend fun send(id: String, clientId: String, body: String) =
        repository.send(id, clientId, body)

    suspend fun guide(id: String) = repository.guide(id)
}

class EventActions(private val repository: CommunityRepository) {
    suspend fun suggestionsAvailable() = repository.suggestionsAvailable()
    suspend fun venues(query: String = "") = repository.venues(query)
    suspend fun createVenue(draft: VenueDraft) = repository.createVenue(draft)
    suspend fun categories() = repository.categories()

    suspend fun edit(id: String, revision: Int, draft: CircleDraft) =
        repository.edit(id, revision, draft)

    suspend fun cancel(id: String, revision: Int) = repository.cancel(id, revision)

    suspend fun feedback(id: String, attended: Boolean, rating: String) =
        repository.feedback(id, attended, rating)

    suspend fun suggest(prompt: String) = repository.suggest(prompt)

    suspend fun recommend(query: CircleQuery) = repository.recommend(query)
}

class MemberActions(private val repository: CommunityRepository) {
    suspend fun notices() = repository.notices()

    suspend fun read(id: Long) = repository.readNotice(id)

    suspend fun preferences() = repository.noticePreferences()

    suspend fun save(value: NoticePreferences) = repository.saveNoticePreferences(value)

    suspend fun stats() = repository.stats()

    suspend fun deleteAccount() = repository.deleteAccount()

    suspend fun registerDevice(token: String) = repository.registerDevice(token)

    suspend fun removeDevice(token: String) = repository.removeDevice(token)
}
