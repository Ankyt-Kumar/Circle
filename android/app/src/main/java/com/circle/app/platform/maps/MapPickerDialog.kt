package com.circle.app.platform.maps

import android.location.Address
import com.circle.app.platform.location.PlaceLookup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.circle.app.domain.model.*
import com.circle.app.platform.location.CurrentAreaButton
import java.util.UUID
import kotlinx.coroutines.*

@Composable
fun MapPickerDialog(venue:Boolean,initial:Area?,onDismiss:()->Unit,onChoose:(VenueDraft)->Unit,
    saving:Boolean=false,serverError:String?=null) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var center by remember { mutableStateOf(initial) }
    var lat by rememberSaveable { mutableStateOf(if(venue)null else initial?.latitude) }
    var lon by rememberSaveable { mutableStateOf(if(venue)null else initial?.longitude) }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf(if(venue)"" else initial?.name.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var requestId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var places by remember { mutableStateOf(emptyList<Address>()) }
    var finding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lookup by remember { mutableStateOf<Job?>(null) }
    var lookupVersion by remember { mutableIntStateOf(0) }
    fun cancelLookup() { lookupVersion++; lookup?.cancel(); finding=false }

    fun changed() { requestId=UUID.randomUUID().toString();error=null }
    fun fromAddress(result:Address,point:Area?=null) {
        lat=point?.latitude ?: result.latitude;lon=point?.longitude ?: result.longitude
        area=listOfNotNull(result.subLocality,result.locality).distinct().joinToString(", ").ifBlank { "Selected area" }.take(100)
        address=result.getAddressLine(0).orEmpty().take(300)
        if(venue) name=point?.name?.takeUnless { it=="Selected place" } ?: result.featureName.orEmpty().take(100)
        center=Area(area,lat!!,lon!!);places=emptyList();confirmed=false;changed()
    }
    fun pick(point:Area) {
        if(saving)return
        cancelLookup();val version=lookupVersion;places=emptyList();lat=point.latitude;lon=point.longitude;center=point
        name=if(venue && point.name!="Selected place") point.name.take(100) else ""
        address="";area="Selected area";confirmed=false;changed()
        lookup=scope.launch {
            finding=true
            try {
                val result=PlaceLookup.reverse(context,point.latitude,point.longitude)
                if(version!=lookupVersion)return@launch
                if(result!=null) fromAddress(result,point)
                else if(venue) error="Enter the public venue’s name and full address below."
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { if(venue)error="Address lookup unavailable. Enter the public venue address below." }
            finally { if(version==lookupVersion)finding=false }
        }
    }
    Dialog(onDismissRequest={if(!saving)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnBackPress=!saving,dismissOnClickOutside=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(venue) "Choose a public venue" else "Choose your location",style=MaterialTheme.typography.headlineMedium)
                Text(if(venue) "Search for a venue or tap a place on the map. Confirm its public address before saving."
                    else "Select the point to use for nearby circles. This location is private to you.")
                CurrentAreaButton({cancelLookup();places=emptyList();center=it;if(!venue){lat=it.latitude;lon=it.longitude;area=it.name;changed()}},enabled=!saving,selectionKey=requestId)
                OutlinedTextField(query,{query=it.take(200)},Modifier.fillMaxWidth(),label={Text(if(venue) "Venue or address" else "Area or city")},singleLine=true,enabled=!saving)
                OutlinedButton({
                    cancelLookup();changed();val version=lookupVersion;val term=query.trim();places=emptyList();lookup=scope.launch {
                        finding=true;error=null
                        try {
                            val results=PlaceLookup.search(context,term)
                            if(version!=lookupVersion)return@launch
                            places=results.filter { it.hasLatitude() && it.hasLongitude() }
                            if(places.isEmpty())error="No results. Try a more specific place, or select a point on the map."
                        } catch(e:CancellationException) {throw e}
                        catch(_:Exception) { error="Search unavailable. Select a point on the map and enter its address." }
                        finally { if(version==lookupVersion)finding=false }
                    }
                },enabled=query.trim().length>=3 && !saving && !finding) { Text("Search places") }
                places.forEach { result ->
                    OutlinedButton({cancelLookup();fromAddress(result)},Modifier.fillMaxWidth(),enabled=!saving) { Text(result.getAddressLine(0) ?: result.featureName ?: "Select place") }
                }
                MapSurface(center,if(validCoordinates(lat,lon)) Area(name.ifBlank { area },lat!!,lon!!) else null,
                    Modifier.fillMaxWidth().height(280.dp),if(saving)null else ::pick)
                if(finding)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(venue) {
                    OutlinedTextField(name,{name=it.take(100);changed()},Modifier.fillMaxWidth(),label={Text("Public venue name")},enabled=!saving && !finding)
                    OutlinedTextField(address,{address=it.take(300);changed()},Modifier.fillMaxWidth(),label={Text("Full public venue address")},minLines=2,enabled=!saving && !finding)
                }
                OutlinedTextField(area,{area=it.take(100);changed()},Modifier.fillMaxWidth(),label={Text("Area / city")},enabled=!saving && !finding)
                if(venue) {
                    Row { Checkbox(confirmed,{confirmed=it;changed()},enabled=!saving);Text("This is a public meeting place, not a home or private residence.",Modifier.padding(top=12.dp)) }
                    Text("Check the address matches the pin and the venue welcomes your group. Saving a venue does not make a booking.",style=MaterialTheme.typography.bodySmall)
                }
                (serverError ?: error)?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Button({onChoose(VenueDraft(requestId,name.trim(),address.trim(),area.trim(),lat!!,lon!!,confirmed))},
                    Modifier.fillMaxWidth(),enabled=!saving && !finding && validCoordinates(lat,lon) && area.isNotBlank() &&
                        (!venue || name.trim().length>=3 && address.trim().length>=8 && confirmed)) {
                    Text(if(saving) "Saving venue…" else if(venue) "Use this venue" else "Use this location")
                }
                TextButton(onDismiss,Modifier.fillMaxWidth(),enabled=!saving) { Text("Cancel") }
            }
        }
    }
}
