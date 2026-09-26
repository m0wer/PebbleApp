package coredevices.coreapp.di

import coredevices.experimentalModule
import coredevices.pebble.watchModule

// Everything MainApplication starts Koin with, apart from the Android context.
val androidAppModules = listOf(
    androidDefaultModule,
    experimentalModule,
    apiModule,
    utilModule,
    watchModule,
)
