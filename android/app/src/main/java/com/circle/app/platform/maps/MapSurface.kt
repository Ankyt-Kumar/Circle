package com.circle.app.platform.maps

import android.content.ComponentCallbacks
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.circle.app.domain.model.Area
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*

@Composable
internal fun MapSurface(center:Area?,selected:Area?,modifier:Modifier=Modifier,onPick:((Area)->Unit)?=null) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
    val apiKey=remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA)
            .metaData?.getString("com.google.android.geo.API_KEY").orEmpty()
    }
    if(apiKey.isBlank()) {
        Card(modifier) { Column(Modifier.padding(20.dp)) {
            Text("Map unavailable",style=MaterialTheme.typography.titleMedium)
            Text("You can still search for a place or open it in Google Maps.",style=MaterialTheme.typography.bodySmall)
        } };return
    }
    val view=remember(context) { MapView(context).apply { onCreate(null) } }
    var google by remember { mutableStateOf<GoogleMap?>(null) }
    val pick by rememberUpdatedState(onPick)
    val dark=MaterialTheme.colorScheme.background.luminance()<0.5f
    DisposableEffect(view,lifecycle) {
        var started=false;var resumed=false;var active=true
        fun sync() {
            val wantsStart=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val wantsResume=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(resumed && !wantsResume) { view.onPause();resumed=false }
            if(started && !wantsStart) { view.onStop();started=false }
            if(!started && wantsStart) { view.onStart();started=true }
            if(!resumed && wantsResume) { view.onResume();resumed=true }
        }
        val observer=LifecycleEventObserver { _,_->sync() }
        val callbacks=object:ComponentCallbacks {
            override fun onConfigurationChanged(newConfig:Configuration) {}
            override fun onLowMemory() { view.onLowMemory() }
        }
        lifecycle.addObserver(observer);context.registerComponentCallbacks(callbacks);sync()
        view.getMapAsync { map ->
            if (!active) return@getMapAsync
            google=map
            map.uiSettings.isMyLocationButtonEnabled=false
            map.setOnMapClickListener { p->pick?.invoke(Area("Selected place",p.latitude,p.longitude)) }
            map.setOnPoiClickListener { p->pick?.invoke(Area(p.name,p.latLng.latitude,p.latLng.longitude)) }
        }
        onDispose {
            active=false
            lifecycle.removeObserver(observer);context.unregisterComponentCallbacks(callbacks)
            if(resumed)view.onPause();if(started)view.onStop();view.onDestroy();google=null
        }
    }
    LaunchedEffect(google,center) {
        google?.moveCamera(if(center==null) CameraUpdateFactory.newLatLngZoom(LatLng(0.0,0.0),1f)
            else CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude,center.longitude),15f))
    }
    LaunchedEffect(google,selected) {
        google?.let { map ->map.clear();selected?.let { map.addMarker(MarkerOptions().position(LatLng(it.latitude,it.longitude)).title(it.name)) } }
    }
    LaunchedEffect(google,dark,onPick==null) {
        google?.let { map ->
            map.uiSettings.isZoomControlsEnabled=true;map.uiSettings.isScrollGesturesEnabled=onPick!=null
            map.setMapStyle(if(dark) MapStyleOptions("""[{"elementType":"geometry","stylers":[{"color":"#242b29"}]},{"elementType":"labels.text.fill","stylers":[{"color":"#e1e5df"}]},{"elementType":"labels.text.stroke","stylers":[{"color":"#242b29"}]},{"featureType":"water","elementType":"geometry","stylers":[{"color":"#152421"}]}]""") else null)
        }
    }
    AndroidView(factory={view},modifier=modifier)
}
