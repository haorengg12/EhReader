/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.preference

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import com.hippo.ehviewer.R
import com.hippo.ehviewer.download.DownloadMaintenance
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.util.runSuspendCatching
import com.hippo.util.withUIContext
import kotlinx.coroutines.launch

class RestoreDownloadPreference(context: Context, attrs: AttributeSet? = null) : TaskPreference(context, attrs) {
    override val jobTitle = context.getString(R.string.settings_download_restore_download_items)

    override fun launchJob() {
        launch {
            val result = runSuspendCatching { DownloadMaintenance.run(restore = true) }
                .onFailure { Log.e("RestoreDownloadPreference", "Cannot restore downloads", it) }.getOrNull()
            withUIContext {
                if (result != null && result.restored > 0 && !DownloadManager.hasActiveDownloads()) DownloadManager.reloadFromDatabase()
                val message = when {
                    result == null -> context.getString(R.string.settings_download_restore_failed)
                    result.restored == 0 && result.failed == 0 && result.skipped == 0 && !result.stopped -> context.getString(R.string.settings_download_restore_not_found)
                    else -> context.getString(R.string.settings_download_restore_report, result.restored, result.skipped, result.failed) +
                        if (result.stopped) "\n" + context.getString(R.string.settings_download_maintenance_stopped) else ""
                }
                showTip(message)
                mDialog.dismiss()
            }
        }
    }
}
