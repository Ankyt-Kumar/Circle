package com.circle.app.data.remote

data class CredentialSnapshot(val token: String? = null, val generation: Long = 0)
interface RequestCredentials {
    suspend fun snapshot(): CredentialSnapshot
    fun isCurrent(snapshot: CredentialSnapshot): Boolean
    suspend fun rejected(snapshot: CredentialSnapshot, status: Int)
}
object DemoCredentials : RequestCredentials {
    override suspend fun snapshot() = CredentialSnapshot()
    override fun isCurrent(snapshot: CredentialSnapshot) = true
    override suspend fun rejected(snapshot: CredentialSnapshot, status: Int) = Unit
}
