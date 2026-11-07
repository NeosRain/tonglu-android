package cn.tonglu.app

import android.app.Application
import cn.tonglu.app.data.memory.FairMemory

class TaimiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FairMemory.initialize(this)
    }
}
