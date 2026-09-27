package com.circle.app.domain.repository

import com.circle.app.domain.model.*

interface CommunityRepository {
    suspend fun suggestionsAvailable(): Boolean = false
    suspend fun venues(query: String = ""): List<PublicVenue>
    suspend fun createVenue(draft: VenueDraft): PublicVenue { error("Venue selection unavailable") }
    suspend fun categories(): List<String> = PreferenceOptions.interests

    suspend fun messages(circleId: String, before: Long = 0): ChatPage

    suspend fun send(circleId: String, clientId: String, body: String): ChatMessage

    suspend fun guide(circleId: String): ConversationGuide

    suspend fun edit(circleId: String, revision: Int, draft: CircleDraft)

    suspend fun cancel(circleId: String, revision: Int)

    suspend fun feedback(circleId: String, attended: Boolean, rating: String)

    suspend fun notices(): List<CircleNotice>

    suspend fun readNotice(id: Long)

    suspend fun noticePreferences(): NoticePreferences

    suspend fun saveNoticePreferences(value: NoticePreferences)

    suspend fun registerDevice(token: String)

    suspend fun removeDevice(token: String)

    suspend fun stats(): MemberStats

    suspend fun deleteAccount()

    suspend fun suggest(prompt: String): ActivitySuggestion

    suspend fun recommend(query: CircleQuery): Recommendations
}
