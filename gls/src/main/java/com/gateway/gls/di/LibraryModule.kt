package com.gateway.gls.di

import android.app.Application
import com.gateway.gls.data.LocationServiceAvailability

object LibraryModule {
    @Volatile
    lateinit var application: Application

    /**
     * This function should called in MainActivity

     * to provide an application instance for the service.
     *
     * @author Ahmed Mones
     */
    fun initializeService(application: Application) {
        if (this::application.isInitialized.not()) {
            synchronized(application) { this.application = application }
            LocationServiceAvailability.initializeService()
        }
    }
}