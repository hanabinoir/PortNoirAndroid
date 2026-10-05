package com.portnoir

import android.app.Application
import com.portnoir.data.DeviceRepository
import com.portnoir.net.ConnectionManager

class PortNoirApp : Application() {
    lateinit var repo: DeviceRepository
    lateinit var connections: ConnectionManager

    override fun onCreate() {
        super.onCreate()
        repo = DeviceRepository(this)
        connections = ConnectionManager(this, repo)
    }
}
