package app.struthio.nostr

import android.app.Application
import app.struthio.nostr.data.Core
import app.struthio.nostr.net.AppLog
import java.io.File

class ZinoApp : Application() {
    val core: Core by lazy { Core(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.start(File(filesDir, "log.txt"))
    }
}
