package io.github.conichonhaa.ilo

import android.app.Application
import io.github.conichonhaa.ilo.data.ServerRepository

class IloApp : Application() {
    val repository: ServerRepository by lazy { ServerRepository(this) }
}
