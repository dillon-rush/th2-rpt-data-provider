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

import com.exactpro.cradle.BookId
import com.exactpro.cradle.TimeRelation
import com.exactpro.cradle.messages.MessageFilterBuilder
import com.exactpro.cradle.messages.StoredMessage
import com.exactpro.cradle.messages.StoredMessageBatch
import com.exactpro.cradle.messages.StoredMessageId
import com.exactpro.th2.rptdataprovider.Context
import com.exactpro.th2.rptdataprovider.entities.requests.SseMessageSearchRequest
import com.exactpro.th2.rptdataprovider.handlers.StreamName
import com.exactpro.th2.rptdataprovider.isAfterOrEqual
import com.exactpro.th2.rptdataprovider.isBeforeOrEqual
import mu.KotlinLogging
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset


class StreamInitializer(
    val context: Context,
    val request: SseMessageSearchRequest,
    val stream: StreamName
) {

    companion object {
        private val logger = KotlinLogging.logger { }
    }


    private fun nextDay(timestamp: Instant, timelineDirection: TimeRelation): Instant {
        val utcTimestamp = timestamp.atOffset(ZoneOffset.UTC)
        return if (timelineDirection == TimeRelation.AFTER) {
            utcTimestamp.plusDays(1)
                .with(LocalTime.of(0, 0, 0, 0))
        } else {
            utcTimestamp.with(LocalTime.of(0, 0, 0, 0))
                .minusNanos(1)
        }.toInstant()
    }


    private fun getNearestMessage(
        messageBatch: Collection<StoredMessage>,
        timelineDirection: TimeRelation,
        timestamp: Instant
    ): StoredMessage? {
        messageBatch.ifEmpty { return null }

        return if (timelineDirection == TimeRelation.AFTER) {
            messageBatch.find { it.timestamp.isAfterOrEqual(timestamp) } ?: messageBatch.lastOrNull()
        } else {
            messageBatch.findLast { it.timestamp.isBeforeOrEqual(timestamp) } ?: messageBatch.firstOrNull()
        }
    }


    private suspend fun getFirstMessageCurrentDay(timestamp: Instant, stream: StreamName): StoredMessageId? {
        for (timeRelation in listOf(TimeRelation.BEFORE, TimeRelation.AFTER)) {
            context.cradleService.getFirstMessageIdSuspend(timestamp, stream, timeRelation)?.let {
                return it
            }
        }
        return null
    }


    private suspend fun getFirstMessageInStream(sessionAlias: StreamName,bookId: BookId,timestamp: Instant): StoredMessage? {
        val sequence = context.cradleService.getFirstMessageSequence(sessionAlias.name, sessionAlias.direction)
        if (sequence == -1L) {
            return null
        }

        return StoredMessageId(bookId,sessionAlias.name, sessionAlias.direction, timestamp, sequence).let {
            context.cradleService.getMessageSuspend(it)
        }
    }


    private fun searchInFutureLimit(endTimestamp: Instant?): (Instant) -> Boolean {
        val futureSearchLimit = nextDay(Instant.now(), TimeRelation.AFTER)
        if (endTimestamp == null) {
            return { timestamp: Instant -> timestamp.isBefore(futureSearchLimit) }
        } else {
            val minimumLimit = minOf(endTimestamp, futureSearchLimit)
            return { timestamp: Instant -> timestamp.isBefore(minimumLimit) }
        }
    }


    private fun searchInPastLimit(endTimestamp: Instant?, lastTimestamp: Instant?): (Instant) -> Boolean {
        if (endTimestamp != null) {
            return { timestamp: Instant ->
                timestamp.isAfter(endTimestamp)
                        || lastTimestamp?.let { timestamp.isAfterOrEqual(it) } ?: true
            }
        } else {
            return { timestamp: Instant -> lastTimestamp?.let { timestamp.isAfterOrEqual(it) } ?: true }
        }
    }


    private suspend fun getTimeSearchLimit(bookId: BookId,timestamp: Instant): ((Instant) -> Boolean) {
        return if (request.searchDirection == TimeRelation.AFTER) {
            searchInFutureLimit(request.endTimestamp)
        } else {
            val firstMessageInStream = getFirstMessageInStream(stream,bookId,timestamp)
            searchInPastLimit(request.endTimestamp, firstMessageInStream?.timestamp)
        }
    }


    private suspend fun getFirstMessageIdDifferentDays(startTimestamp: Instant, sessionAlias: StreamName, bookId: BookId): StoredMessageId? {
        var isCurrentDay = true
        var timestamp = startTimestamp
        var messageId: StoredMessageId? = null
        var daysChecking = request.lookupLimitDays

        val timeLimit = getTimeSearchLimit(bookId,timestamp)

        while (messageId == null && timeLimit(timestamp) && daysChecking?.let { it >= 0 } != false) {
            messageId =
                if (isCurrentDay) {
                    getFirstMessageCurrentDay(timestamp, sessionAlias)
                } else {
                    context.cradleService.getFirstMessageIdSuspend(timestamp, sessionAlias, request.searchDirection)
                }
            daysChecking = daysChecking?.dec()
            isCurrentDay = false
            timestamp = nextDay(timestamp, request.searchDirection)
        }
        return messageId
    }


    private suspend fun getStartMessageFromTime(sessionAlias: StreamName, timestamp: Instant,bookId: BookId): StoredMessage? {
        val storedMessageId = getFirstMessageIdDifferentDays(timestamp, sessionAlias,bookId)

        return storedMessageId?.let {
            val filter = MessageFilterBuilder()
                .bookId(it.bookId)
                .direction(it.direction)
                .sessionAlias(it.sessionAlias)
                .build()
            val messageBatch = context.cradleService.getMessagesSuspend(filter).toList()
            getNearestMessage(messageBatch, request.searchDirection, timestamp)
        }
    }


    suspend fun initStream(startTimestamp: Instant,bookId: BookId): StoredMessage? {
        return getStartMessageFromTime(stream, startTimestamp,bookId)
    }


    suspend fun tryToGetStartId(startTimestamp: Instant): StoredMessageId? {
        return context.cradleService.getFirstMessageIdSuspend(
            startTimestamp,
            stream,
            request.searchDirection
        )
    }
}