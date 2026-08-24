package de.lobianco.saftssh.remotedesktop

import android.app.Application
import de.lobianco.saftssh.remotedesktop.data.logging.LogFileManager

/** Only job: start log capture as early as possible, before anything else in the plugin runs. */
class RemoteDesktopPluginApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogFileManager.init(this)
    }
}
