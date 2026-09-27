package com.circle.app

import android.app.Application
import com.circle.app.di.AppContainer

class CircleApplication : Application() {
    val container by lazy { AppContainer(this) }
}
