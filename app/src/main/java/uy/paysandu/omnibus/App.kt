package uy.paysandu.omnibus

import android.app.Application
import org.osmdroid.config.Configuration
import uy.paysandu.omnibus.data.Repository

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid exige un User-Agent identificable para los tiles de OpenStreetMap
        Configuration.getInstance().apply {
            load(this@App, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "$packageName/${BuildConfigCompat.versionName(this@App)}"
        }
        Repository.init(this)
    }
}

object BuildConfigCompat {
    fun versionName(app: Application): String =
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
}
