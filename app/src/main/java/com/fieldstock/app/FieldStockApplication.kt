package com.fieldstock.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.fieldstock.app.diagnostics.AppDiagnostics
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FieldStockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(this)
        AppDiagnostics.event("process.started",mapOf("pid" to android.os.Process.myPid()))
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,failure ->
            AppDiagnostics.crash(thread,failure)
            if(previous!=null) previous.uncaughtException(thread,failure)
            else { android.os.Process.killProcess(android.os.Process.myPid());kotlin.system.exitProcess(10) }
        }
        val handler=Handler(Looper.getMainLooper())
        val resumed=AtomicInteger(0)
        val heartbeat=AtomicLong(SystemClock.uptimeMillis())
        var stalled=false
        registerActivityLifecycleCallbacks(object:ActivityLifecycleCallbacks {
            private fun log(activity:Activity,state:String)=AppDiagnostics.event("activity.$state",mapOf("activity" to activity.javaClass.simpleName))
            override fun onActivityCreated(a:Activity,s:Bundle?){log(a,"created")}
            override fun onActivityStarted(a:Activity){log(a,"started")}
            override fun onActivityResumed(a:Activity){resumed.incrementAndGet();heartbeat.set(SystemClock.uptimeMillis());log(a,"resumed")}
            override fun onActivityPaused(a:Activity){resumed.updateAndGet { maxOf(0,it-1) };log(a,"paused")}
            override fun onActivityStopped(a:Activity){log(a,"stopped")}
            override fun onActivityDestroyed(a:Activity){log(a,"destroyed")}
            override fun onActivitySaveInstanceState(a:Activity,s:Bundle){log(a,"state_saved")}
        })
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"fieldstock-ui-watchdog").apply { isDaemon=true } }.scheduleWithFixedDelay({
            handler.post { heartbeat.set(SystemClock.uptimeMillis()) }
            val age=SystemClock.uptimeMillis()-heartbeat.get()
            if(resumed.get()>0 && age>6000 && !stalled) {
                stalled=true
                AppDiagnostics.event("ui.unresponsive",mapOf("delay_ms" to age),"WARN",
                    Throwable("Main thread did not respond").apply { stackTrace=Looper.getMainLooper().thread.stackTrace })
            } else if(stalled && age<3000) { stalled=false;AppDiagnostics.event("ui.responsive_again") }
        },2,2,TimeUnit.SECONDS)
    }
}
