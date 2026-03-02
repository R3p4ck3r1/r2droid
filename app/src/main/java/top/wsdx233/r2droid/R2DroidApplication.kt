package top.wsdx233.r2droid

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import cat.ereza.customactivityoncrash.config.CaocConfig
import top.wsdx233.r2droid.activity.CrashActivity
import top.wsdx233.r2droid.feature.plugin.PluginManager

@HiltAndroidApp
class R2DroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CaocConfig.Builder.create()
            .errorActivity(CrashActivity::class.java)
            .apply()

        PluginManager.initialize(this)
    }
}
