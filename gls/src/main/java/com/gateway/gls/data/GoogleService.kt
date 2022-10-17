package com.gateway.gls.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentSender
import android.location.Location
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.gateway.gls.domain.interfaces.LocationService
import com.gateway.gls.domain.models.Resource
import com.gateway.gls.domain.models.ServiceFailure
import com.gateway.gls.utils.extenstions.isGpsProviderEnabled
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import timber.log.Timber

@SuppressLint("MissingPermission")
class GoogleService(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val locationRequest: LocationRequest
) : LocationService {
    override suspend fun lastLocation(): Resource<Location> = safeCall {
        val location = fusedLocationClient.lastLocation.await()
        getLocationResult(context = context, location = location)
    }

    override fun configureLocationRequest(
        priority: Int,
        interval: Long,
        fastestInterval: Long,
        numUpdates: Int
    ) {
        locationRequest.apply {
            this.priority = priority
            this.interval = interval
            this.fastestInterval = fastestInterval
            this.numUpdates = numUpdates
        }
    }

    override fun requestLocationUpdatesAsFlow(): Flow<Resource<Location>> = callbackFlow {
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    trySendBlocking(Resource.Success(data = location))
                        .onFailure {
                            trySendBlocking(
                                Resource.Fail(
                                    error = ServiceFailure.UnknownError(
                                        message = it?.message
                                    )
                                )
                            )
                        }
                }
            }
        }
        if (context.isGpsProviderEnabled().not())
            trySend(Resource.Fail(error = ServiceFailure.GpsProviderIsDisabled()))
        else
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

        awaitClose { fusedLocationClient.removeLocationUpdates(locationCallback) }
    }

    override suspend fun requestLocationUpdates(): Resource<List<Location>> {
        var results: Resource<List<Location>> = Resource.Init
        var isRunning = true

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                runCatching {
                    results = Resource.Success(data = result.locations)
                }.onFailure {
                    results = Resource.Fail(
                        error = ServiceFailure.UnknownError(
                            message = it.message
                        )
                    )
                }

                isRunning = false
            }
        }


        if (context.isGpsProviderEnabled().not())
            results = Resource.Fail(error = ServiceFailure.GpsProviderIsDisabled())
        else
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

        while (isRunning) {
            delay(100)
        }

        fusedLocationClient.removeLocationUpdates(locationCallback)
        return results
    }

    override fun locationSettings(resultContracts: ActivityResultLauncher<IntentSenderRequest>) {
        // Define a device setting client.

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        // Create a settingsClient object.
        val client = LocationServices.getSettingsClient(context)
        val task = client.checkLocationSettings(builder.build())

        task.addOnFailureListener { exception ->
            Timber.d("Location settings request failed")
            if (exception is ResolvableApiException) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().

                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution)
                        .build()
                    resultContracts.launch(intentSenderRequest)
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                    Timber.d(sendEx.message)
                }
            }
        }
    }
}
