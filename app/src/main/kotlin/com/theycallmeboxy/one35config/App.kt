package com.theycallmeboxy.one35config

import android.app.Application
import com.topjohnwu.superuser.Shell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Configure the root shell once, before anything calls Shell.getShell().
        // MOUNT_MASTER runs in the global mount namespace so /data/adb/modules is visible.
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10),
        )
    }
}
