package app.struthio.nostr

import android.app.Application
import app.struthio.nostr.data.Core

class StruthioApp : Application() {
    val core: Core by lazy { Core(this) }
}
