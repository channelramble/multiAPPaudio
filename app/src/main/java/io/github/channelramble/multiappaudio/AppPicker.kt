package io.github.channelramble.multiappaudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import java.util.concurrent.Executor

/** Multi-choice list of launchable apps; [current] ones start checked and are listed first. */
object AppPicker {

    fun show(activity: Activity, io: Executor, title: String, current: Set<String>, onDone: (Set<String>) -> Unit) {
        io.execute {
            val pm = activity.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != activity.packageName }
                .distinctBy { it.first }
                .toMutableList()
            for (pkg in current) if (apps.none { it.first == pkg }) apps += pkg to pkg
            apps.sortWith(compareByDescending<Pair<String, String>> { it.first in current }.thenBy { it.second.lowercase() })
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val checked = BooleanArray(apps.size) { apps[it].first in current }
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMultiChoiceItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray(), checked) { _, which, on ->
                        checked[which] = on
                    }
                    .setPositiveButton("OK") { _, _ ->
                        onDone(apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet())
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
}
