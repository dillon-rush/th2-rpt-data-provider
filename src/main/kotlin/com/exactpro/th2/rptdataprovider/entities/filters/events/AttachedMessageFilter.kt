/*******************************************************************************
 * Copyright 2020-2020 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.rptdataprovider.entities.filters.events

import com.exactpro.cradle.messages.StoredMessageId
import com.exactpro.cradle.testevents.StoredTestEventId
import com.exactpro.th2.rptdataprovider.entities.exceptions.InvalidRequestException
import com.exactpro.th2.rptdataprovider.entities.filters.Filter
import com.exactpro.th2.rptdataprovider.entities.filters.info.FilterInfo
import com.exactpro.th2.rptdataprovider.entities.filters.info.FilterParameterType
import com.exactpro.th2.rptdataprovider.entities.filters.info.Parameter
import com.exactpro.th2.rptdataprovider.entities.filters.messages.AttachedEventFilters
import com.exactpro.th2.rptdataprovider.entities.responses.EventTreeNode
import com.exactpro.th2.rptdataprovider.services.cradle.CradleService
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AttachedMessageFilter(
    requestMap: Map<String, List<String>>,
    cradleService: CradleService
) : Filter<EventTreeNode>(requestMap, cradleService) {

    private lateinit var eventIds: Collection<StoredTestEventId>
    override var negative: Boolean = false

    init {
        negative = requestMap["${filterInfo.name}-negative"]?.first()?.toBoolean() ?: false
        runBlocking {
            eventIds = requestMap["${filterInfo.name}-values"]?.first()
                ?.let { cradleService.getEventIdsSuspend(StoredMessageId.fromString(it)) }
                ?: throw InvalidRequestException("'${filterInfo.name}-values' cannot be empty")
        }
    }

    companion object {
        val filterInfo = FilterInfo(
            "attachedMessageId",
            "matches events by one of the attached message id",
            mutableListOf<Parameter>().apply {
                add(Parameter("negative", FilterParameterType.BOOLEAN, false, null))
                add(
                    Parameter(
                        "values", FilterParameterType.STRING, null,
                        "arfq01fix01:second:1604492791034943949"
                    )
                )
            }
        )
    }


    override fun match(element: EventTreeNode): Boolean {
        return negative.xor(eventIds.contains(StoredTestEventId(element.eventId)))
    }

    override fun getInfo(): FilterInfo {
        return filterInfo
    }
}

