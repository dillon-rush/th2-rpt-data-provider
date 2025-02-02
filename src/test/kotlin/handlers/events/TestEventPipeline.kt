/*******************************************************************************
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
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
 ******************************************************************************/

package handlers.events

import com.exactpro.cradle.Order
import com.exactpro.cradle.TimeRelation
import com.exactpro.cradle.testevents.*
import com.exactpro.th2.rptdataprovider.*
import com.exactpro.th2.rptdataprovider.entities.filters.FilterPredicate
import com.exactpro.th2.rptdataprovider.entities.internal.PipelineFilteredMessage
import com.exactpro.th2.rptdataprovider.entities.internal.ProviderEventId
import com.exactpro.th2.rptdataprovider.entities.requests.SseEventSearchRequest
import com.exactpro.th2.rptdataprovider.entities.responses.Event
import com.exactpro.th2.rptdataprovider.entities.responses.EventTreeNode
import com.exactpro.th2.rptdataprovider.entities.responses.StreamInfo
import com.exactpro.th2.rptdataprovider.entities.sse.LastScannedObjectInfo
import com.exactpro.th2.rptdataprovider.entities.sse.StreamWriter
import com.exactpro.th2.rptdataprovider.handlers.SearchEventsHandler
import com.exactpro.th2.rptdataprovider.producers.EventProducer
import com.exactpro.th2.rptdataprovider.services.cradle.CradleService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestEventPipeline {

    private val startTimestamp = Instant.parse("2022-04-21T01:05:00Z")
    private val endTimestamp = Instant.parse("2022-04-21T01:15:00Z")
    private val eventsFromStartToEnd11 = createEvents("1", startTimestamp, endTimestamp)


    data class EventData(val id: StoredTestEventId, val startTimestamp: Instant, val endTimestamp: Instant)

    data class EventsParameters(
        val startTimestamp: Instant,
        val endTimestamp: Instant?,
        val resumeId: ProviderEventId?,
        val events: List<List<EventData>>,
        val expectedResult: List<String>
    )


    private fun changeTimestamp(timestamp: Instant, minutes: Long): Instant {
        return if (minutes > 0) {
            timestamp.plus(minutes, ChronoUnit.MINUTES)
        } else {
            timestamp.minus(abs(minutes), ChronoUnit.MINUTES)
        }
    }


    private fun getSearchRequest(
        startTimestamp: Instant,
        endTimestamp: Instant?,
        searchDirection: TimeRelation = TimeRelation.AFTER,
        resumeId: ProviderEventId? = null
    ): SseEventSearchRequest {
        val parameters = mutableMapOf(
            "startTimestamp" to listOf(startTimestamp.toEpochMilli().toString())
        )

        if (endTimestamp != null) {
            parameters["endTimestamp"] = listOf(endTimestamp.toEpochMilli().toString())
        }

        if (resumeId != null) {
            parameters["resumeFromId"] = listOf(resumeId.toString())
        }

        return SseEventSearchRequest(parameters, FilterPredicate(emptyList()))
            .copy(searchDirection = searchDirection)
    }

    private fun mockContextWithCradleService(
        batches: List<StoredTestEventWrapper>,
        resumeId: ProviderEventId?
    ): Context {
        val context: Context = mockk()

        every { context.configuration.sendEmptyDelay.value } answers { "10" }
        every { context.configuration.sseEventSearchStep.value } answers { "10" }
        every { context.configuration.eventSearchChunkSize.value } answers { "1" }
        every { context.configuration.keepAliveTimeout.value } answers { "1" }

        val cradle = mockk<CradleService>()

        coEvery { cradle.getEventsSuspend(any<Instant>(), any<Instant>(), any<Order>()) } answers {
            val start = firstArg<Instant>()
            val end = secondArg<Instant>()
            val order = thirdArg<Order>()
            batches.filter {
                maxInstant(it.startTimestamp, start).isBeforeOrEqual(minInstant(it.endTimestamp, end))
            }.let {
                if (order == Order.DIRECT) it else it.reversed()
            }
        }

        coEvery { cradle.getEventsSuspend(any<StoredTestEventId>(), any<Instant>(), any<Order>()) } answers {
            val start = firstArg<StoredTestEventId>()
            val end = secondArg<Instant>()
            val order = thirdArg<Order>()

            batches.let {
                if (order == Order.DIRECT) it else it.reversed()
            }.dropWhile { batch -> batch.id != start }
        }



        coEvery { cradle.getEventsSuspend(any<Instant>(), any<StoredTestEventId>(), any<Order>()) } answers {
            val start = firstArg<Instant>()
            val end = secondArg<StoredTestEventId>()
            val order = thirdArg<Order>()

            batches.let {
                if (order == Order.DIRECT) it else it.reversed()
            }.dropWhile { batch -> batch.id != end }
        }

        if (resumeId != null) {
            val batch = batches.find { it.asBatch().testEvents.map { it.id }.contains(resumeId.eventId) }
            coEvery { cradle.getEventSuspend(any()) } answers { batch }
        }

        every { context.cradleService } answers { cradle }
        every { context.eventProducer } answers { EventProducer(cradle, ObjectMapper()) }
        return context
    }

    private fun createBatch(batches: List<List<EventData>>): List<StoredTestEventWrapper> {
        return batches.map { events ->
            val storedId = events.first().id
            val batch = StoredTestEventBatch(
                TestEventBatchToStore.builder()
                    .id(StoredTestEventId(storedId.toString().split("-").first()))
                    .parentId(StoredTestEventId("parent"))
                    .build()
            ).also {
                for (event in events) {
                    it.addTestEvent(
                        TestEventToStore.builder()
                            .id(event.id)
                            .name("name")
                            .parentId(it.parentId)
                            .startTimestamp(event.startTimestamp)
                            .content(ByteArray(1))
                            .endTimestamp(event.endTimestamp)
                            .build()
                    )
                }
            }
            StoredTestEventWrapper(batch)
        }
    }

    private fun mockWriter(events: MutableList<EventTreeNode>): StreamWriter {
        return object : StreamWriter {

            override suspend fun write(event: EventTreeNode, counter: AtomicLong) {
                events.add(event)
            }

            override suspend fun write(message: PipelineFilteredMessage, counter: AtomicLong) {
            }

            override suspend fun write(event: Event, lastEventId: AtomicLong) {
            }

            override suspend fun write(streamInfo: List<StreamInfo>) {
            }

            override suspend fun write(lastScannedObjectInfo: LastScannedObjectInfo, counter: AtomicLong) {
            }

            override suspend fun closeWriter() {
            }
        }
    }

    private fun createEvents(batchId: String, startTimestamp: Instant, endTimestamp: Instant): List<EventData> {
        var start = startTimestamp
        var index = 1
        return mutableListOf<EventData>().apply {
            while (start.isBeforeOrEqual(endTimestamp)) {
                val end = start.plus(1, ChronoUnit.MINUTES)
                add(
                    EventData(StoredTestEventId("$batchId-$index"), start, end)
                )
                start = end
                index++
            }
        }
    }


    private fun getIdRange(batchId: String, start: Int, end: Int): List<String> {
        return (start..end).map { "$batchId-$it" }
    }


    private fun baseTestCase(
        testCase: EventsParameters,
        searchDirection: TimeRelation = TimeRelation.AFTER,
        intersects: Boolean = false
    ) {
        val startTimestamp = testCase.startTimestamp
        val endTimestamp = testCase.endTimestamp
        val resumeId = testCase.resumeId

        val request = getSearchRequest(startTimestamp, endTimestamp, searchDirection, resumeId)
        request.checkRequest()

        val batch = createBatch(testCase.events)
        val context = mockContextWithCradleService(batch, resumeId)
        val searchEvent = SearchEventsHandler(context)

        val resultEvents = mutableListOf<EventTreeNode>()
        val writer = mockWriter(resultEvents)

        runBlocking {
            searchEvent.searchEventsSse(request, writer)
            coroutineContext.cancelChildren()
        }

        val expectedResult = if (searchDirection == TimeRelation.AFTER) {
            testCase.expectedResult
        } else {
            testCase.expectedResult.reversed()
        }

        if (!intersects) {
            assertArrayEquals(
                expectedResult.toTypedArray(),
                resultEvents.map { it.id.eventId.toString() }.toTypedArray()
            )
        } else {
            assertEquals(
                expectedResult.toSet(),
                resultEvents.map { it.id.eventId.toString() }.toSet()
            )
        }
    }


    @Test
    fun `testAllInterval`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, -1),
            changeTimestamp(endTimestamp, 1),
            null,
            events = listOf(eventsFromStartToEnd11),
            expectedResult = getIdRange("1", 1, 11)
        )

        baseTestCase(testData)
    }


    @Test
    fun `testStartInterval`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, -1),
            changeTimestamp(startTimestamp, 1),
            null,
            events = listOf(eventsFromStartToEnd11),
            expectedResult = getIdRange("1", 1, 1)
        )

        baseTestCase(testData)
    }


    @Test
    fun `baseTestBatches`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, -100),
            changeTimestamp(startTimestamp, 100),
            null,
            events = listOf(
                createEvents("1", startTimestamp, startTimestamp.plus(5, ChronoUnit.MINUTES)),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 5),
                    changeTimestamp(startTimestamp, 10)
                )
            ),
            expectedResult = getIdRange("1", 1, 6) + getIdRange("2", 1, 6)
        )

        baseTestCase(testData)
    }

    @Test
    fun `testIntersectedBatches`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, -100),
            changeTimestamp(startTimestamp, 100),
            null,
            events = listOf(
                createEvents(
                    "1",
                    startTimestamp,
                    changeTimestamp(startTimestamp, 5)
                ),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 3),
                    changeTimestamp(startTimestamp, 8)
                )
            ),
            expectedResult = getIdRange("1", 1, 6) + getIdRange("2", 1, 6)
        )

        baseTestCase(testData, intersects = true)
    }

    @Test
    fun `baseResumeTest`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, -100),
            changeTimestamp(startTimestamp, 100),
            ProviderEventId(StoredTestEventId("1"), StoredTestEventId("1-4")),
            events = listOf(
                createEvents("1", startTimestamp, startTimestamp.plus(5, ChronoUnit.MINUTES)),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 5),
                    changeTimestamp(startTimestamp, 10)
                )
            ),
            expectedResult = getIdRange("1", 5, 6) + getIdRange("2", 1, 6)
        )

        baseTestCase(testData)
    }


    @Test
    fun `baseResumeTestSecondBatch`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, -100),
            changeTimestamp(startTimestamp, 100),
            ProviderEventId(StoredTestEventId("2"), StoredTestEventId("2-1")),
            events = listOf(
                createEvents("1", startTimestamp, startTimestamp.plus(5, ChronoUnit.MINUTES)),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 5),
                    changeTimestamp(startTimestamp, 10)
                )
            ),
            expectedResult = getIdRange("2", 2, 6)
        )

        baseTestCase(testData)
    }


    @Test
    fun `testIntersectedBatchesResume`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, -100),
            changeTimestamp(startTimestamp, 100),
            ProviderEventId(StoredTestEventId("1"), StoredTestEventId("1-4")),
            events = listOf(
                createEvents("1", startTimestamp, startTimestamp.plus(5, ChronoUnit.MINUTES)),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 3),
                    changeTimestamp(startTimestamp, 8)
                )
            ),
            expectedResult = getIdRange("1", 5, 6) + getIdRange("2", 1, 6)
        )

        baseTestCase(testData, intersects = true)
    }

    @Test
    fun `testReverseInterval`() {
        val testData = EventsParameters(
            endTimestamp,
            startTimestamp,
            null,
            events = listOf(eventsFromStartToEnd11),
            expectedResult = getIdRange("1", 2, 11)
        )

        baseTestCase(testData, TimeRelation.BEFORE)
    }


    @Test
    fun `testReverseAllInterval`() {
        val testData = EventsParameters(
            endTimestamp,
            changeTimestamp(startTimestamp, -1),
            null,
            events = listOf(eventsFromStartToEnd11),
            expectedResult = getIdRange("1", 1, 11)
        )

        baseTestCase(testData, TimeRelation.BEFORE)
    }


    @Test
    fun `testReverseResume`() {
        val testData = EventsParameters(
            endTimestamp,
            startTimestamp,
            ProviderEventId(StoredTestEventId("1"), StoredTestEventId("1-10")),
            events = listOf(eventsFromStartToEnd11),
            expectedResult = getIdRange("1", 2, 9)
        )

        baseTestCase(testData, TimeRelation.BEFORE)
    }

    @Test
    fun `testIntersectedBatchesResumeReverse`() {
        val testData = EventsParameters(
            startTimestamp = changeTimestamp(startTimestamp, 100),
            endTimestamp = changeTimestamp(startTimestamp, -100),
            resumeId = ProviderEventId(StoredTestEventId("2"), StoredTestEventId("2-3")),
            events = listOf(
                createEvents("1", startTimestamp, changeTimestamp(startTimestamp, 5)),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 3),
                    changeTimestamp(startTimestamp, 8)
                )
            ),
            expectedResult = getIdRange("1", 1, 6)
                    + getIdRange("2", 1, 2)
        )

        baseTestCase(testData, TimeRelation.BEFORE, intersects = true)
    }


    @Test
    fun `testTimestamp`() {
        val testData = EventsParameters(
            startTimestamp = startTimestamp,
            endTimestamp = changeTimestamp(startTimestamp, 10),
            resumeId = null,
            events = listOf(
                createEvents(
                    "1",
                    changeTimestamp(startTimestamp, -2),
                    changeTimestamp(startTimestamp, 2)
                ),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 2),
                    changeTimestamp(startTimestamp, 8)
                ),
                createEvents(
                    "3",
                    changeTimestamp(startTimestamp, 8),
                    changeTimestamp(startTimestamp, 11)
                )
            ),
            expectedResult = getIdRange("1", 3, 5)
                    + getIdRange("2", 1, 7)
                    + getIdRange("3", 1, 2)
        )

        baseTestCase(testData)
    }

    @Test
    fun `testTimestampReverse`() {
        val testData = EventsParameters(
            changeTimestamp(startTimestamp, 10),
            startTimestamp,
            null,
            events = listOf(
                createEvents(
                    "1",
                    changeTimestamp(startTimestamp, -2),
                    changeTimestamp(startTimestamp, 2)
                ),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, 2),
                    changeTimestamp(startTimestamp, 8)
                ),
                createEvents(
                    "3",
                    changeTimestamp(startTimestamp, 8),
                    changeTimestamp(startTimestamp, 11)
                )
            ),
            expectedResult = getIdRange("1", 4, 5)
                    + getIdRange("2", 1, 7)
                    + getIdRange("3", 1, 3)
        )

        baseTestCase(testData, TimeRelation.BEFORE)
    }


    @Test
    fun `testTrimming`() {
        val testData = EventsParameters(
            startTimestamp,
            changeTimestamp(startTimestamp, 10),
            null,
            events = listOf(
                createEvents(
                    "1",
                    changeTimestamp(startTimestamp, -2),
                    changeTimestamp(startTimestamp, 2)
                ),
                createEvents(
                    "2",
                    changeTimestamp(startTimestamp, -2),
                    changeTimestamp(startTimestamp, 12)
                ),
                createEvents(
                    "3",
                    changeTimestamp(startTimestamp, 8),
                    changeTimestamp(startTimestamp, 12)
                )
            ),
            expectedResult = getIdRange("1", 3, 5)
                    + getIdRange("2", 3, 12)
                    + getIdRange("3", 1, 2)
        )

        baseTestCase(testData, TimeRelation.AFTER)
    }
}