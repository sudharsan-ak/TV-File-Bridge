package com.tvfilebridge.a11ywatchdog

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Plain Views, not Compose - this app has one screen with a handful of
 * checkboxes, and pulling in Compose's runtime for that would work against
 * the "as lightweight as possible" goal for no real benefit.
 */
class MainActivity : Activity() {

    private lateinit var store: WatchedServicesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WatchedServicesStore(this)
        // Started here too, not just after Save, so the watchdog is live as
        // soon as the app's been opened once - not dependent on the user
        // having already picked services and tapped Save first.
        WakeWatcherService.start(this)
        PeriodicCheckScheduler.schedule(this)

        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val installedServices = accessibilityManager.getInstalledAccessibilityServiceList()
        val watched = store.getWatchedServices()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        container.addView(
            TextView(this).apply {
                text = "Select which accessibility services should be automatically re-enabled if they get turned off (checked on boot, screen wake, and periodically)."
                textSize = 16f
                setPadding(0, 0, 0, 32)
            },
        )

        val checkboxes = installedServices.map { serviceInfo ->
            val componentName = serviceInfo.componentNameString()
            CheckBox(this).apply {
                text = serviceInfo.resolveInfo.loadLabel(packageManager)
                isChecked = watched.contains(componentName)
                textSize = 18f
                setPadding(0, 16, 0, 16)
                tag = componentName
            }
        }
        checkboxes.forEach { container.addView(it) }

        val statusText = TextView(this).apply {
            setPadding(0, 32, 0, 0)
        }
        container.addView(statusText)

        val saveButton = android.widget.Button(this).apply {
            text = "Save"
            setOnClickListener {
                val selected = checkboxes.filter { it.isChecked }.map { it.tag as String }.toSet()
                store.setWatchedServices(selected)
                WakeWatcherService.start(this@MainActivity)
                PeriodicCheckScheduler.schedule(this@MainActivity)
                val fixed = AccessibilityFixer.fixIfNeeded(this@MainActivity)
                statusText.text = if (selected.isEmpty()) {
                    "Saved. Nothing selected to watch."
                } else if (fixed) {
                    "Saved. Fixed ${selected.size} service(s) just now."
                } else {
                    "Saved. Watching ${selected.size} service(s), all already enabled."
                }
            }
        }
        container.addView(saveButton)

        setContentView(ScrollView(this).apply { addView(container) })
    }
}

private fun AccessibilityServiceInfo.componentNameString(): String {
    val resolveInfo = this.resolveInfo.serviceInfo
    return "${resolveInfo.packageName}/${resolveInfo.name}"
}
