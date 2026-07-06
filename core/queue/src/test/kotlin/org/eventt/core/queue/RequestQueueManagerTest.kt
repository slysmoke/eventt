package org.eventt.core.queue

import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.eventt.core.model.QueuedRequest
import org.eventt.core.model.RequestStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class RequestQueueManagerTest {
    // RequestQueueManager is a process-wide singleton — without resetting it, state from one
    // test would leak into the next.
    @AfterEach
    fun resetQueue() {
        RequestQueueManager.clearAll()
    }

    @Test
    fun `enqueue adds a request and emits the updated list`() =
        runTest {
            RequestQueueManager.requests.test {
                awaitItem() shouldBe emptyList()

                val request = QueuedRequest(endpoint = "/test/", description = "test")
                RequestQueueManager.enqueue(request)

                awaitItem() shouldBe listOf(request)
            }
        }

    @Test
    fun `markInProgress updates only the matching request`() =
        runTest {
            val a = QueuedRequest(endpoint = "/a/", description = "a")
            val b = QueuedRequest(endpoint = "/b/", description = "b")
            RequestQueueManager.enqueueMultiple(listOf(a, b))

            RequestQueueManager.markInProgress(a.id)

            val updated = RequestQueueManager.requests.value
            updated.first { it.id == a.id }.status shouldBe RequestStatus.IN_PROGRESS
            updated.first { it.id == b.id }.status shouldBe RequestStatus.QUEUED
        }

    @Test
    fun `completeRequest without an error marks it COMPLETED`() {
        val request = QueuedRequest(endpoint = "/ok/", description = "ok")
        RequestQueueManager.enqueue(request)

        RequestQueueManager.completeRequest(request.id)

        RequestQueueManager.requests.value
            .first()
            .status shouldBe RequestStatus.COMPLETED
    }

    @Test
    fun `completeRequest with an error marks it FAILED`() {
        val request = QueuedRequest(endpoint = "/fail/", description = "fail")
        RequestQueueManager.enqueue(request)

        RequestQueueManager.completeRequest(request.id, error = "boom")

        val result = RequestQueueManager.requests.value.first()
        result.status shouldBe RequestStatus.FAILED
        result.error shouldBe "boom"
    }

    @Test
    fun `clearCompleted removes only COMPLETED and FAILED entries`() {
        val queued = QueuedRequest(endpoint = "/queued/", description = "queued")
        val done = QueuedRequest(endpoint = "/done/", description = "done")
        RequestQueueManager.enqueueMultiple(listOf(queued, done))
        RequestQueueManager.completeRequest(done.id)

        RequestQueueManager.clearCompleted()

        val remaining = RequestQueueManager.requests.value
        remaining.map { it.id } shouldBe listOf(queued.id)
    }

    @Test
    fun `overallProgress averages progress across all requests`() {
        val a = QueuedRequest(endpoint = "/a/", description = "a")
        val b = QueuedRequest(endpoint = "/b/", description = "b")
        RequestQueueManager.enqueueMultiple(listOf(a, b))

        RequestQueueManager.updateProgress(a.id, 1.0f)
        RequestQueueManager.updateProgress(b.id, 0.0f)

        RequestQueueManager.overallProgress shouldBe 0.5f
    }

    @Test
    fun `overallProgress is zero for an empty queue`() {
        RequestQueueManager.overallProgress shouldBe 0f
    }
}
