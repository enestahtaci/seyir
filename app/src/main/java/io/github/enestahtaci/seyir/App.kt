package io.github.enestahtaci.seyir

import android.app.Application
import io.github.enestahtaci.seyir.net.HttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Turkish results and Turkey's content (trending, recommendations) without any account.
        NewPipe.init(HttpDownloader, Localization("tr", "TR"), ContentCountry("TR"))
    }
}
