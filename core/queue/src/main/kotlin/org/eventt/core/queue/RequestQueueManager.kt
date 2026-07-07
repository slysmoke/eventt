package org.eventt.core.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eventt.core.model.QueuedRequest
import org.eventt.core.model.RequestStatus

object RequestQueueManager {
    private val _requests = MutableStateFlow<List<QueuedRequest>>(emptyList())
    val requests: StateFlow<List<QueuedRequest>> = _requests.asStateFlow()

    @Synchronized
    fun enqueue(request: QueuedRequest) {
        _requests.value = _requests.value + request
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
    ) {
        _requests.value =
            _requests.value.map {
                if (it.id == requestId) {
                    it.copy(
                        status = if (error != null) RequestStatus.FAILED else RequestStatus.COMPLETED,
                        progress = 1f,
                        endTime = System.currentTimeMillis(),
                        error = error,
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
