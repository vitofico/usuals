package io.theficos.usuals.di

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** A launcher chosen by the person, without payment SDKs or access to wallet contents. */
class AndroidWallet(private val context: Context) {
    private val preferences = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
    fun picker(title: String) = Intent(Intent.ACTION_PICK_ACTIVITY)
        .putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
        .putExtra(Intent.EXTRA_TITLE, title)

    fun selected(): Intent? = preferences.getString("component", null)?.let(ComponentName::unflattenFromString)?.let(::launcher)

    fun choose(component: ComponentName): Intent? {
        val intent = launcher(component) ?: return null
        check(preferences.edit().putString("component", component.flattenToString()).commit())
        return intent
    }

    private fun launcher(component: ComponentName): Intent? {
        if (component.packageName == context.packageName) return null
        val intent = Intent.makeMainActivity(component)
        val info = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.packageName), 0)
            .firstOrNull { it.activityInfo.name == component.className }?.activityInfo ?: return null
        return intent.takeIf { info.exported && info.enabled && info.applicationInfo.enabled }
    }
}
