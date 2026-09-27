package com.circle.app.domain.repository

import com.circle.app.domain.model.SavedCircleForm

interface DraftRepository {
    suspend fun load(): SavedCircleForm?

    suspend fun save(form: SavedCircleForm)

    suspend fun clear()
}
