package org.eve.trader.core.queue

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eve.trader.core.model.QueuedRequest
import org.eve.trader.core.model.RequestStatus

object RequestQueueManager {

    private val _requests = MutableStateFlow<List<QueuedRequest>>(emptyList())
    val requests: StateFlow<List<QueuedRequest>> = _requests.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val queue = ArrayDeque<QueuedRequest>()

    fun enqueue(request: QueuedRequest) {
        queue.add(request)
        updateState()
    }

    fun enqueueMultiple(requests: List<QueuedRequest>) {
        queue.addAll(requests)
        updateState()
    }

    fun dequeue(): QueuedRequest? {
        return if (queue.isNotEmpty()) {
            val request = queue.removeFirst()
            updateState()
            request
        } else null
    }

    fun updateProgress(requestId: String, progress: Float) {
        val updated = _requests.value.map {
            if (it.id == requestId) it.copy(progress = progress) else it
        }
        _requests.value = updated
    }

    fun completeRequest(requestId: String, error: String? = null) {
        val updated = _requests.value.map {
            if (it.id == requestId) {
                it.copy(
                    status = if (error != null) RequestStatus.FAILED else RequestStatus.COMPLETED,
                    progress = 1f,
                    endTime = System.currentTimeMillis(),
                    error = error,
                )
            } else it
        }
        _requests.value = updated
    }

    fun markInProgress(requestId: String) {
        val updated = _requests.value.map {
            if (it.id == requestId) {
                it.copy(
                    status = RequestStatus.IN_PROGRESS,
                    startTime = System.currentTimeMillis(),
                    progress = 0.1f,
                )
            } else it
        }
        _requests.value = updated
    }

    fun clearCompleted() {
        _requests.value = _requests.value.filter { it.status != RequestStatus.COMPLETED && it.status != RequestStatus.FAILED }
        queue.removeIf { it.status == RequestStatus.COMPLETED || it.status == RequestStatus.FAILED }
    }

    fun clearAll() {
        queue.clear()
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

    private fun updateState() {
        _requests.value = queue.toList() + _requests.value.filter { it.status == RequestStatus.IN_PROGRESS || it.status == RequestStatus.COMPLETED || it.status == RequestStatus.FAILED }
    }
}
