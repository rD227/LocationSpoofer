package com.vincenthzr.locationspoofer.viewmodel

import android.app.DownloadManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.data.model.GithubRelease
import com.vincenthzr.locationspoofer.utils.GithubUpdateSource
import com.vincenthzr.locationspoofer.utils.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class UpdateUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val releases: List<GithubRelease> = emptyList(),
    val activeDownloadId: Long? = null,
    val activeDownloadUrl: String? = null,
    val downloadProgress: Int = 0,
    val downloadStatus: Int = DownloadManager.STATUS_PENDING
)

class UpdateViewModel(private val context: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val updateManager = UpdateManager(context)
    private val okHttpClient = OkHttpClient()

    fun fetchReleases() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                fun fetchJson(url: String): String {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                    return okHttpClient.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "Failed to fetch updates: ${response.code}" }
                        response.body?.string() ?: error("Empty release response")
                    }
                }

                val latest = GithubUpdateSource.parseRelease(
                    JSONObject(fetchJson("${GithubUpdateSource.API_URL}/latest")),
                    isLatest = true
                )
                // 历史记录不可用时，仍保留已获取的最新稳定版和更新说明。
                val history = try {
                    val array = JSONArray(fetchJson(GithubUpdateSource.API_URL))
                    (0 until array.length()).mapNotNull { index ->
                        val obj = array.getJSONObject(index)
                        if (obj.optBoolean("draft") || obj.optString("tag_name") == latest.versionName) null
                        else GithubUpdateSource.parseRelease(obj)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                // Beta 开关仍按发布时间选择候选版本；稳定版始终以 /latest 为准。
                val releaseList = history.filter { it.isPrerelease && it.publishedAt > latest.publishedAt } +
                    latest + history.filterNot { it.isPrerelease && it.publishedAt > latest.publishedAt }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, releases = releaseList) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            }
        }
    }

    fun startDownload(url: String, versionName: String) {
        val fileName = "LocationSpoofer_$versionName.apk"
        val downloadId = updateManager.downloadApk(url, fileName)
        _uiState.update {
            it.copy(
                activeDownloadId = downloadId,
                activeDownloadUrl = url,
                downloadProgress = 0,
                downloadStatus = DownloadManager.STATUS_PENDING
            )
        }
        monitorDownload(downloadId)
    }

    private fun monitorDownload(downloadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val status = updateManager.getDownloadStatus(downloadId)
                val progress = updateManager.getDownloadProgress(downloadId)

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            downloadStatus = status,
                            downloadProgress = progress
                        )
                    }
                }

                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    break
                }
                delay(500)
            }
        }
    }

    fun installApk() {
        _uiState.value.activeDownloadId?.let { downloadId ->
            updateManager.installApk(downloadId)
        }
    }

    fun cancelDownload() {
        _uiState.value.activeDownloadId?.let { downloadId ->
            updateManager.cancelDownload(downloadId)
            _uiState.update {
                it.copy(
                    activeDownloadId = null,
                    activeDownloadUrl = null,
                    downloadProgress = 0,
                    downloadStatus = DownloadManager.STATUS_PENDING
                )
            }
        }
    }
}
