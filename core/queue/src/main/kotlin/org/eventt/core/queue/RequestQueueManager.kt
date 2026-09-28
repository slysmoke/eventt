package org.eventt.core.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eventt.core.model.QueuedRequest
import org.eventt.core.model.RequestSource
import org.eventt.core.model.RequestStatus

object RequestQueueManager {
    private const val MAX_COMPLETED = 500
    private const val MAX_BODY_CHARS = 20_000

    private val _requests = MutableStateFlow<List<QueuedRequest>>(emptyList())
    val requests: StateFlow<List<QueuedRequest>> = _requests.asStateFlow()

    @Synchronized
    fun enqueue(request: QueuedRequest) {
        _requests.value = _requests.value + request
    }

    /**
     * Logs a request answered from the local cache — no network call, so it goes straight in as
     * COMPLETED. These are frequent (every sweep re-reads its cached books), so only the newest
     * [MAX_COMPLETED] completed entries are kept.
     */
    @Synchronized
    fun recordCacheHit(
        endpoint: String,
        description: String,
    ) {
        val now = System.currentTimeMillis()
        val list =
            _requests.value +
                QueuedRequest(
                    endpoint = endpoint,
                    description = description,
                    source = RequestSource.CACHE,
                    status = RequestStatus.COMPLETED,
                    progress = 1f,
                    startTime = now,
                    endTime = now,
                )
        val excess = list.count { it.status == RequestStatus.COMPLETED } - MAX_COMPLETED
        _requests.value = if (excess > 0) dropOldestCompleted(list, excess) else list
    }

    private fun dropOldestCompleted(
        list: List<QueuedRequest>,
        n: Int,
    ): List<QueuedRequest> {
        var toDrop = n
        return list.filter { r ->
            if (toDrop > 0 && r.status == RequestStatus.COMPLETED) {
                toDrop--
                false
            } else {
                true
            }
        }
    }

    @Synchronized
    fun enqueueMultiple(requests: List<QueuedRequest>) {
        _requests.value = _requests.value + requests
    }

    @Synchronized
    fun markInProgress(requestId: String) {
        _requests.value =
            _requests.value.map {
                if (it.id == requestId) {
                    it.copy(status = RequestStatus.IN_PROGRESS, startTime = System.currentTimeMillis(), progress = 0.1f)
                } else {
                    it
                }
            }
    }

    @Synchronized
    fun updateProgress(
        requestId: String,
        progress: Float,
    ) {
        _requests.value =
            _requests.value.map {
                if (it.id == requestId) it.copy(progress = progress) else it
            }
    }

    @Synchronized
    fun completeRequest(
        requestId: String,
        error: String? = null,
        httpCode: Int? = null,
        responseBody: String? = null,
    ) {
        _requests.value =
            _requests.value.map {
                if (it.id == requestId) {
                    // A request can be completed twice (status first, then the thrown exception's
                    // message) — keep details from the first call instead of wiping them.
                    it.copy(
                        status = if (error != null) RequestStatus.FAILED else RequestStatus.COMPLETED,
                        progress = 1f,
                        endTime = System.currentTimeMillis(),
                        error = error,
                        httpCode = httpCode ?: it.httpCode,
                        responseBody = responseBody?.take(MAX_BODY_CHARS) ?: it.responseBody,
                    )
                } else {
                    it
                }
            }
    }

    // Only drops successful requests — failed ones stay visible (and counted) until the user
    // reviews them and clears everything explicitly via clearAll().
    @Synchronized
    fun clearCompleted() {
        _requests.value = _requests.value.filter { it.status != RequestStatus.COMPLETED }
    }

    @Synchronized
    fun clearAll() {
        _requests.value = emptyList()
    }

    val totalRequests: Int get() = _requests.value.size
    val completedRequests: Int get() = _requests.value.count { it.status == RequestStatus.COMPLETED }
    val failedRequests: Int get() = _requests.value.count { it.status == RequestStatus.FAILED }
    val inProgressRequests: Int get() = _requests.value.count { it.status == RequestStatus.IN_PROGRESS }
    val overallProgress: Float
        get() {
            val list = _requests.value
            if (list.isEmpty()) return 0f
            return list.sumOf { it.progress.toDouble() }.toFloat() / list.size
        }
}
