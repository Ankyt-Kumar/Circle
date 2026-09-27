package com.circle.app.platform.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Best-effort address lookup; a slow provider must not hold the location UI open. */
internal object PlaceLookup {
    private val legacy = ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(8), { task -> Thread(task, "circle-geocoder").apply { isDaemon = true } })

    suspend fun reverse(context: Context, latitude: Double, longitude: Double): Address? =
        lookup(context, null, latitude, longitude).firstOrNull()

    suspend fun search(context: Context, query: String): List<Address> = lookup(context, query, 0.0, 0.0)

    private suspend fun lookup(context: Context, query: String?, latitude: Double, longitude: Double): List<Address> {
        if (!Geocoder.isPresent()) return emptyList()
        return withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                val finished = AtomicBoolean(false)
                fun finish(value: List<Address>) {
                    if (finished.compareAndSet(false, true) && continuation.isActive) continuation.resume(value)
                }
                val geocoder = Geocoder(context.applicationContext)
                if (Build.VERSION.SDK_INT >= 33) {
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = finish(addresses)
                        override fun onError(errorMessage: String?) = finish(emptyList())
                    }
                    continuation.invokeOnCancellation { finished.set(true) }
                    try {
                        if (query == null) geocoder.getFromLocation(latitude, longitude, 1, listener)
                        else geocoder.getFromLocationName(query, 5, listener)
                    } catch (_: Exception) { finish(emptyList()) }
                } else {
                    try {
                        val future = legacy.submit {
                            val addresses = try {
                                @Suppress("DEPRECATION")
                                if (query == null) geocoder.getFromLocation(latitude, longitude, 1)
                                else geocoder.getFromLocationName(query, 5)
                            } catch (_: Exception) { null }
                            finish(addresses.orEmpty())
                        }
                        continuation.invokeOnCancellation { finished.set(true); future.cancel(true) }
                    } catch (_: Exception) { finish(emptyList()) }
                }
            }
        }.orEmpty()
    }
}
