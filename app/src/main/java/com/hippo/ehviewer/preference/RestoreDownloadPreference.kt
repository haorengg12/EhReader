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
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadStorage
import com.hippo.util.withUIContext
import kotlinx.coroutines.launch

class RestoreDownloadPreference(context: Context, attrs: AttributeSet? = null) : TaskPreference(context, attrs) {
    override val jobTitle = context.getString(R.string.settings_download_restore_download_items)

    override fun launchJob() {
        launch {
            var count = 0
            var attempted = 0
            val selected = Settings.downloadLocation
            val busy = DownloadManager.hasActiveDownloads()
            if (!busy && selected != null) {
                EhDB.allDownloadDirname.forEach { old ->
                    if (old.phase == "complete") {
                        attempted++
                        val candidate = old.copy(rootUri = selected.uri.toString())
                        if (DownloadStorage.verify(candidate)) {
                            EhDB.putDownloadLocation(candidate)
                            DownloadManager.getDownloadInfo(old.gid)?.let { info ->
                                info.state = com.hippo.ehviewer.dao.DownloadInfo.STATE_FINISH
                                info.legacy = 0
                                EhDB.putDownloadInfo(info)
                            }
                            count++
                        }
                    }
                }
            }
            withUIContext {
                if (!DownloadManager.hasActiveDownloads()) DownloadManager.reloadFromDatabase()
                showTip(
                    when {
                        count > 0 -> context.getString(R.string.settings_download_restore_successfully, count)
                        busy || selected == null || attempted > 0 -> context.getString(R.string.settings_download_restore_failed)
                        else -> context.getString(R.string.settings_download_restore_not_found)
                    },
                )
                mDialog.dismiss()
            }
        }
    }
}
