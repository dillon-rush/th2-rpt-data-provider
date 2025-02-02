/*******************************************************************************
 * Copyright (c) 2022-2022, Exactpro Systems LLC
 * www.exactpro.com
 * Build Software to Test Software
 *
 * All rights reserved.
 * This is unpublished, licensed software, confidential and proprietary
 * information which is the property of Exactpro Systems LLC or its licensors.
 ******************************************************************************/

package com.exactpro.th2.rptdataprovider.handlers.events

import com.exactpro.cradle.TimeRelation
import com.exactpro.cradle.testevents.StoredTestEventWrapper
import com.exactpro.th2.rptdataprovider.entities.internal.ProviderEventId
import com.exactpro.th2.rptdataprovider.entities.requests.SseEventSearchRequest
import com.exactpro.th2.rptdataprovider.getDayStart
import com.exactpro.th2.rptdataprovider.isDifferentDays
import com.exactpro.th2.rptdataprovider.maxInstant
import com.exactpro.th2.rptdataprovider.minInstant
import java.time.Instant
import java.time.temporal.ChronoUnit


class TimeIntervalGenerator(
    private val request: SseEventSearchRequest,
    private val resumeId: ProviderEventId?,
    private val resumeEventBatchOrSingle: StoredTestEventWrapper?
) : Iterable<SearchInterval> {

    private val initDatabaseRequestTimestamp = resumeEventBatchOrSingle?.let {
        if (request.searchDirection == TimeRelation.AFTER) {
            it.startTimestamp
        } else {
            it.endTimestamp
        }
    } ?: request.startTimestamp!!


    private val startTimestampRequest = request.startTimestamp ?: resumeEventBatchOrSingle?.startTimestamp!!


    private val comparator = if (request.searchDirection == TimeRelation.AFTER) {
        { timestamp: Instant -> timestamp.isBefore(request.endTimestamp ?: Instant.MAX) }
    } else {
        { timestamp: Instant -> timestamp.isAfter(request.endTimestamp ?: Instant.MIN) }
    }


    private fun changeOfDayProcessing(from: Instant, to: Instant): Iterable<SearchInterval> {
        val startCurrentDay = getDayStart(from)

        return if (isDifferentDays(from, to)) {
            val pivot = startCurrentDay
                .plusDays(1)
                .toInstant()

            listOf(
                SearchInterval(from, pivot.minusNanos(1), startTimestampRequest, request.endTimestamp),
                SearchInterval(pivot, to, startTimestampRequest, request.endTimestamp)
            )
        } else {
            listOf(SearchInterval(from, to, startTimestampRequest, request.endTimestamp))
        }
    }


    private fun getToTimestamp(currentTimestamp: Instant): Instant {
        return minInstant(
            minInstant(currentTimestamp.plus(1, ChronoUnit.DAYS), Instant.MAX),
            request.endTimestamp ?: Instant.MAX
        )
    }


    private fun getFromTimestamp(currentTimestamp: Instant): Instant {
        return maxInstant(
            maxInstant(currentTimestamp.minus(1, ChronoUnit.DAYS), Instant.MIN),
            request.endTimestamp ?: Instant.MIN
        )
    }


    override fun iterator(): Iterator<SearchInterval> {
        var currentTimestamp = initDatabaseRequestTimestamp

        return sequence {
            while (comparator.invoke(currentTimestamp)) {
                val timeIntervals = if (request.searchDirection == TimeRelation.AFTER) {
                    val toTimestamp = getToTimestamp(currentTimestamp)

                    changeOfDayProcessing(currentTimestamp, toTimestamp).also {
                        currentTimestamp = toTimestamp
                    }
                } else {
                    val fromTimestamp = getFromTimestamp(currentTimestamp)

                    changeOfDayProcessing(fromTimestamp, currentTimestamp)
                        .reversed()
                        .also { currentTimestamp = fromTimestamp }
                }
                yieldAll(timeIntervals)
            }
        }.mapIndexed { i, interval ->
            if (i == 0) interval.copy(providerResumeId = resumeId) else interval
        }.iterator()
    }
}

