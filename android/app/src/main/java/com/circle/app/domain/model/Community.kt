package com.circle.app.domain.model

import java.time.Instant

data class ChatMessage(
    val id: Long,
    val clientId: String,
    val authorId: String,
    val name: String,
    val body: String,
    val status: String,
    val createdAt: Instant,
)

data class ChatPage(
    val messages: List<ChatMessage>,
    val archived: Boolean,
    val privateAi: Boolean,
    val hasOlder: Boolean,
)

data class CircleNotice(
    val id: Long,
    val circleId: String,
    val title: String,
    val body: String,
    val read: Boolean,
)

data class NoticePreferences(
    val reminders: Boolean = true,
    val changes: Boolean = true,
    val pushEnabled: Boolean = false,
)

data class MemberStats(val joined: Int, val hosted: Int, val attended: Int)

data class ActivitySuggestion(
    val title: String,
    val description: String,
    val category: String,
    val venueId: String,
    val timeSuggestion: String,
)

data class ConversationGuide(
    val icebreakers: List<String>,
    val ended: Boolean,
    val joined: Int,
    val selfReportedAttendance: Int,
)

data class Recommendations(
    val circles: List<Circle>,
    val basis: String,
    val offline: Boolean = false,
    val savedAt: java.time.Instant? = null,
)
