﻿/*******************************************************************************
 * Copyright 2021-2021 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.rptdataprovider.handlers.messages

import com.exactpro.cradle.Order
import com.exactpro.cradle.TimeRelation
import com.exactpro.cradle.messages.StoredMessage
import com.exactpro.cradle.messages.StoredMessageBatch
import com.exactpro.cradle.messages.StoredMessageFilterBuilder
import com.exactpro.cradle.messages.StoredMessageId
import com.exactpro.th2.rptdataprovider.Context
import com.exactpro.th2.rptdataprovider.entities.internal.EmptyPipelineObject
import com.exactpro.th2.rptdataprovider.entities.internal.PipelineRawBatch
import com.exactpro.th2.rptdataprovider.entities.requests.SseMessageSearchRequest
import com.exactpro.th2.rptdataprovider.entities.requests.StreamPointer
import com.exactpro.th2.rptdataprovider.entities.responses.StoredMessageBatchWrapper
import com.exactpro.th2.rptdataprovider.entities.sse.StreamWriter
import com.exactpro.th2.rptdataprovider.handlers.PipelineComponent
import com.exactpro.th2.rptdataprovider.handlers.PipelineStatus
import com.exactpro.th2.rptdataprovider.handlers.StreamName
import com.exactpro.th2.rptdataprovider.isAfterOrEqual
import com.exactpro.th2.rptdataprovider.isBeforeOrEqual
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.Instant


class MessageExtractor(
    context: Context,
    val request: SseMessageSearchRequest,
    stream: StreamName,
    externalScope: CoroutineScope,
    messageFlowCapacity: Int,
    private val pipelineStatus: PipelineStatus
) : PipelineComponent(
    context, request, externalScope, stream, messageFlowCapacity = messageFlowCapacity
) {

    companion object {
        private val logger = KotlinLogging.logger { }
    }

    private val sendEmptyDelay = context.configuration.sendEmptyDelay.value.toLong()

    private var isStreamEmpty: Boolean = false

    private var lastElement: StoredMessageId? = null
    private var lastTimestamp: Instant? = null

    private val order = if (request.searchDirection == TimeRelation.AFTER) {
        Order.DIRECT
    } else {
        Order.REVERSE
    }

    init {
        externalScope.launch {
            try {
                processMessage()
            } catch (e: CancellationException) {
                logger.debug { "message extractor for stream $streamName has been stopped" }
            } catch (e: Exception) {
                logger.error(e) { "unexpected exception" }
                throw e
            }
        }
    }

    private fun getMessagesFromBatch(batch: StoredMessageBatch): Collection<StoredMessage> {
        return if (order == Order.DIRECT) {
            batch.messages
        } else {
            batch.messagesReverse
        }
    }

    private fun trimMessagesListHead(
        messages: Collection<StoredMessage>,
        resumeFromId: StreamPointer?
    ): List<StoredMessage> {
        // trim messages if resumeFromId is present
        return if (resumeFromId?.sequence != null) {
            val start = resumeFromId.sequence
            messages.dropWhile {
                if (order == Order.DIRECT) {
                    it.index < start
                } else {
                    it.index > start
                }
            }
        } else {
            //trim messages that do not strictly match time filter
            messages.dropWhile {
                request.startTimestamp?.let { startTimestamp ->
                    if (order == Order.DIRECT) {
                        it.timestamp.isBefore(startTimestamp)
                    } else {
                        it.timestamp.isAfter(startTimestamp)
                    }
                } ?: false
            }
        }
    }

    private fun trimMessagesListTail(message: StoredMessage): Boolean {
        return request.endTimestamp?.let { endTimestamp ->
            if (order == Order.DIRECT) {
                message.timestamp.isAfterOrEqual(endTimestamp)
            } else {
                message.timestamp.isBeforeOrEqual(endTimestamp)
            }
        } ?: false
    }

    override suspend fun processMessage() {
        coroutineScope {
            launch {
                while (this@coroutineScope.isActive) {

                    //FIXME: replace delay-based stream updates with synchronous updates from iterator
                    lastTimestamp?.also {
                        sendToChannel(EmptyPipelineObject(isStreamEmpty, lastElement, it).also { msg ->
                            logger.trace { "Extractor has sent EmptyPipelineObject downstream: (lastProcessedId${msg.lastProcessedId} lastScannedTime=${msg.lastScannedTime} streamEmpty=${msg.streamEmpty} hash=${msg.hashCode()})" }
                        })
                    }
                    delay(sendEmptyDelay)
                }
            }

            streamName!!

            val resumeFromId = request.resumeFromIdsList.firstOrNull {
                it.streamName == streamName.name && it.direction == streamName.direction
            }

            logger.debug { "acquiring cradle iterator for stream $streamName" }

            resumeFromId?.let { logger.debug { "resume sequence for stream $streamName is set to ${it.sequence}" } }
            request.startTimestamp?.let { logger.debug { "start timestamp for stream $streamName is set to $it" } }

            if (resumeFromId == null || resumeFromId.hasStarted) {
                val cradleMessageIterable = context.cradleService.getMessagesBatchesSuspend(
                    StoredMessageFilterBuilder().streamName().isEqualTo(streamName.name).direction()
                        .isEqualTo(streamName.direction).order(order)

                        // timestamps will be ignored if resumeFromId is present
                        .also { builder ->
                            if (resumeFromId != null) {
                                builder.index().let {
                                    if (order == Order.DIRECT) {
                                        it.isGreaterThanOrEqualTo(resumeFromId.sequence)
                                    } else {
                                        it.isLessThanOrEqualTo(resumeFromId.sequence)
                                    }
                                }
                            } else {
                                if (order == Order.DIRECT) {
                                    request.startTimestamp?.let { builder.timestampFrom().isGreaterThanOrEqualTo(it) }
                                } else {
                                    request.startTimestamp?.let { builder.timestampTo().isLessThanOrEqualTo(it) }
                                }
                            }
                            if (order == Order.DIRECT) {
                                request.endTimestamp?.let { builder.timestampTo().isLessThan(it) }
                            } else {
                                request.endTimestamp?.let { builder.timestampFrom().isGreaterThan(it) }
                            }
                        }.build()
                )

                logger.debug { "cradle iterator has been built for stream $streamName" }

                var isLastMessageTrimmed = false

                for (batch in cradleMessageIterable) {
                    if (externalScope.isActive && !isLastMessageTrimmed) {

                        val timeStart = System.currentTimeMillis()

                        pipelineStatus.fetchedStart(streamName.toString())
                        logger.trace { "batch ${batch.id.index} of stream $streamName with ${batch.messageCount} messages (${batch.batchSize} bytes) has been extracted" }

                        val trimmedMessages = getMessagesFromBatch(batch)
                            .let { trimMessagesListHead(it, resumeFromId) }
                            .dropLastWhile { message ->
                                trimMessagesListTail(message).also {
                                    isLastMessageTrimmed = isLastMessageTrimmed || it
                                }
                            }

                        val firstMessage = if (order == Order.DIRECT) batch.messages.first() else batch.messages.last()
                        val lastMessage = if (order == Order.DIRECT) batch.messages.last() else batch.messages.first()

                        logger.trace {
                            "batch ${batch.id.index} of stream $streamName has been trimmed (targetStartTimestamp=${request.startTimestamp} targetEndTimestamp=${request.endTimestamp} targetId=${resumeFromId?.sequence}) - ${trimmedMessages.size} of ${batch.messages.size} messages left (firstId=${firstMessage.id.index} firstTimestamp=${firstMessage.timestamp} lastId=${lastMessage.id.index} lastTimestamp=${lastMessage.timestamp})"
                        }

                        pipelineStatus.fetchedEnd(streamName.toString())

                        try {
                            trimmedMessages.last().let { message ->
                                sendToChannel(PipelineRawBatch(
                                    false,
                                    message.id,
                                    message.timestamp,
                                    StoredMessageBatchWrapper(batch.id, trimmedMessages)
                                ).also {
                                    it.info.startExtract = timeStart
                                    it.info.endExtract = System.currentTimeMillis()
                                    StreamWriter.setExtract(it.info)
                                })
                                lastElement = message.id
                                lastTimestamp = message.timestamp

                                logger.trace { "batch ${batch.id.index} of stream $streamName has been sent downstream" }

                            }
                        } catch (e: NoSuchElementException) {
                            logger.trace { "skipping batch ${batch.id.index} of stream $streamName - no messages left after trimming" }
                            pipelineStatus.countSkippedBatches(streamName.toString())
                        }
                        pipelineStatus.fetchedSendDownstream(streamName.toString())

                        pipelineStatus.countFetchedBytes(streamName.toString(), batch.batchSize)
                        pipelineStatus.countFetchedBatches(streamName.toString())
                        pipelineStatus.countFetchedMessages(streamName.toString(), trimmedMessages.size.toLong())
                        pipelineStatus.countSkippedMessages(
                            streamName.toString(), batch.messageCount - trimmedMessages.size.toLong()
                        )
                    } else {
                        logger.debug { "Exiting $streamName loop. External scope active: '${externalScope.isActive}', LastMessageTrimmed: '$isLastMessageTrimmed'" }
                        break
                    }
                }
            }

            isStreamEmpty = true
            lastTimestamp = if (order == Order.DIRECT) {
                Instant.ofEpochMilli(Long.MAX_VALUE)
            } else {
                Instant.ofEpochMilli(Long.MIN_VALUE)
            }

            logger.debug { "no more data for stream $streamName (lastId=${lastElement.toString()} lastTimestamp=${lastTimestamp})" }
        }
    }
}
