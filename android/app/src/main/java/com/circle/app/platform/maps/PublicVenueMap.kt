package com.circle.app.platform.maps

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.circle.app.domain.model.Area
import com.circle.app.domain.model.Circle
import androidx.core.net.toUri

@Composable
fun PublicVenueMap(circle:Circle) {
    val lat=circle.venueLatitude ?: return;val lon=circle.venueLongitude ?: return
    if(circle.venueFictional)return
    val context=LocalContext.current;val point=Area(circle.venue,lat,lon)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        MapSurface(point,point,Modifier.fillMaxWidth().height(240.dp))
        if(circle.venueAddress.isNotBlank()) Text(circle.venueAddress,style=MaterialTheme.typography.bodyMedium)
        OutlinedButton({
            try { context.startActivity(Intent(Intent.ACTION_VIEW,
                "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon".toUri())) }
            catch(_:ActivityNotFoundException) { android.widget.Toast.makeText(context,"No app is available to open directions",android.widget.Toast.LENGTH_SHORT).show() }
        },Modifier.fillMaxWidth()) { Text("Get directions in Google Maps") }
    }
}
