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
    fun `clearCompleted removes only COMPLETED entries, leaving queued and failed ones for review`() {
        val queued = QueuedRequest(endpoint = "/queued/", description = "queued")
        val done = QueuedRequest(endpoint = "/done/", description = "done")
        val failed = QueuedRequest(endpoint = "/failed/", description = "failed")
        RequestQueueManager.enqueueMultiple(listOf(queued, done, failed))
        RequestQueueManager.completeRequest(done.id)
        RequestQueueManager.completeRequest(failed.id, error = "boom")

        RequestQueueManager.clearCompleted()

        val remaining = RequestQueueManager.requests.value
        remaining.map { it.id }.toSet() shouldBe setOf(queued.id, failed.id)
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

    @Test
    fun `cache hits are completed and capped without dropping failures`() {
        val failed = QueuedRequest(endpoint = "/f/", description = "f")
        RequestQueueManager.enqueue(failed)
        RequestQueueManager.completeRequest(failed.id, error = "HTTP 500", httpCode = 500, responseBody = "{\"error\":\"boom\"}")
        // Second completion (the thrown exception's message) must keep the HTTP details.
        RequestQueueManager.completeRequest(failed.id, error = "ESI request failed: 500")

        repeat(600) { RequestQueueManager.recordCacheHit("/c/$it", "c$it") }

        val list = RequestQueueManager.requests.value
        list.count { it.status == RequestStatus.COMPLETED } shouldBe 500
        list.last().description shouldBe "c599"
        val f = list.single { it.id == failed.id }
        f.httpCode shouldBe 500
        f.responseBody shouldBe "{\"error\":\"boom\"}"
        f.error shouldBe "ESI request failed: 500"
    }
}
