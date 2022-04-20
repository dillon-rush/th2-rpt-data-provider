/*******************************************************************************
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.rptdataprovider.services.rabbitmq

import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.rptdataprovider.entities.configuration.Configuration
import com.exactpro.th2.rptdataprovider.services.AbstractDecoderService

class RabbitMqService(
    configuration: Configuration,
    messageRouterParsedBatch: MessageRouter<MessageGroupBatch>,
    private val messageRouterRawBatch: MessageRouter<MessageGroupBatch>
) : AbstractDecoderService(configuration) {

    init {
        messageRouterParsedBatch.subscribeAll({ _, decodedBatch -> handleResponse(decodedBatch) }, "from_codec")
    }

    override fun decode(messageGroupBatch: MessageGroupBatch) = messageRouterRawBatch.sendAll(messageGroupBatch)
    override fun decode(messageGroupBatch: MessageGroupBatch, sessionAlias: String) =
        messageRouterRawBatch.sendAll(messageGroupBatch, sessionAlias)
}