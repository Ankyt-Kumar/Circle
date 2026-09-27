package com.circle.app.domain.usecase

import com.circle.app.domain.error.CircleException
import com.circle.app.domain.error.FailureReason
import com.circle.app.domain.repository.AccountRepository

class GetAccount(private val repository: AccountRepository) {
    suspend operator fun invoke() = repository.getAccount()
}
class SaveFirstName(private val repository: AccountRepository) {
    suspend operator fun invoke(raw: String): com.circle.app.domain.model.Account {
        val name = raw.trim()
        if (!validFirstName(name)) {
            throw CircleException(FailureReason.INVALID_INPUT)
        }
        return repository.saveFirstName(name)
    }
}

internal fun validFirstName(name: String): Boolean = name.codePointCount(0, name.length) in 1..60 &&
    name.codePoints().anyMatch { Character.isLetter(it) } &&
    name.codePoints().allMatch { Character.isLetter(it) || Character.getType(it) in listOf(6, 7, 8) || it in listOf(32, 39, 45, 8217) }
