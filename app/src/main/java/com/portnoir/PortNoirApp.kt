package com.portnoir

import android.app.Application
import com.portnoir.data.ChatDatabase
import com.portnoir.data.DeviceRepository
import com.portnoir.net.ConnectionManager

class PortNoirApp : Application() {
    lateinit var repo: DeviceRepository
    lateinit var connections: ConnectionManager
    lateinit var chatDatabase: ChatDatabase

    override fun onCreate() {
        super.onCreate()
        repo = DeviceRepository(this)
        chatDatabase = ChatDatabase.get(this)
        connections = ConnectionManager(this, repo, chatDatabase.messages())
    }
}
