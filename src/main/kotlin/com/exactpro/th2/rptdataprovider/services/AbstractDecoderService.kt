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

package com.exactpro.th2.rptdataprovider.services

import com.exactpro.th2.common.grpc.MessageGroupBatch
import com.exactpro.th2.common.message.getSessionAliasAndDirection
import com.exactpro.th2.common.message.sequence
import com.exactpro.th2.common.message.sessionAlias
import com.exactpro.th2.rptdataprovider.entities.configuration.Configuration
import com.exactpro.th2.rptdataprovider.entities.sse.StreamWriter
import com.exactpro.th2.rptdataprovider.handlers.PipelineStatus
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

abstract class AbstractDecoderService(configuration: Configuration) {

    private val responseTimeout = configuration.codecResponseTimeout.value.toLong()
    private val pendingRequests = ConcurrentHashMap<CodecRequestId, PendingCodecBatchRequest>()
    private val usePinAttributes = configuration.codecUsePinAttributes.value.toBoolean()
    private val maximumPendingRequests = configuration.codecPendingBatchLimit.value.toInt()

    private val requestSenderScope = CoroutineScope(
        Executors.newFixedThreadPool(configuration.codecRequestThreadPool.value.toInt()).asCoroutineDispatcher()
    )

    private val callbackScope = CoroutineScope(
        Executors.newFixedThreadPool(configuration.codecCallbackThreadPool.value.toInt()).asCoroutineDispatcher()
    )

    private val codecLatency = PipelineStatus.codecLatency

    protected fun handleResponse(decodedBatch: MessageGroupBatch) {
        callbackScope.launch {

            val response = MessageGroupBatchWrapper(decodedBatch)

            LOGGER.trace { "codec response with hash ${response.responseHash} has been received" }

            pendingRequests.remove(response.requestId)?.let {
                codecLatency.gaugeDec(listOf(it.streamName))
                codecLatency.setDuration(it.startTimestamp.toDouble(), listOf(it.streamName))
                it.completableDeferred.complete(response)
            }
                ?: LOGGER.trace {
                    val (firstSequence, lastSequence, stream) = getBatchProps(decodedBatch)
                    "codec response with hash ${response.responseHash} has no matching requests (stream=${stream} firstId=${firstSequence} lastId=${lastSequence} requestId=${response.requestId})"
                }
        }
    }

    suspend fun sendToCodec(request: CodecBatchRequest): CodecBatchResponse {

        LOGGER.trace { "Send batch before cycle ${request.requestId} with id to codec" }

        while (pendingRequests.keys.size > maximumPendingRequests) {
            delay(100)
        }

        LOGGER.trace { "Send batch ${request.requestId} with id to codec" }

        return pendingRequests.computeIfAbsent(request.requestId) {
            val pendingRequest = request.toPending()

            LOGGER.trace { "Get pending request for batch ${request.requestId}" }

            // launch timeout handler coroutine
            callbackScope.launch {
                measureTimeMillis {
                    delay(responseTimeout)

                    pendingRequest.completableDeferred.let {
                        if (pendingRequests[request.requestId]?.completableDeferred === pendingRequest.completableDeferred) {

                            pendingRequests.remove(request.requestId)
                            it.complete(null)

                            codecLatency.gaugeDec(listOf(request.streamName))
                            codecLatency.setDuration(
                                pendingRequest.startTimestamp.toDouble(),
                                listOf(request.streamName)
                            )

                            LOGGER.warn {
                                val (firstSequence, lastSequence, stream) = getBatchProps(request.protobufRawMessageBatch)
                                "codec request timed out after $responseTimeout ms (stream=${stream} firstId=${firstSequence} lastId=${lastSequence} hash=${request.requestHash}) requestId=${request.requestId}"
                            }
                        }
                    }
                }.also { LOGGER.debug { "${request.requestId} callbackScope ${it}ms" } }
            }

            LOGGER.trace { "Check timeout callback for batch ${request.requestId}" }
            LOGGER.trace { "Check requestSenderScope alive ${requestSenderScope.isActive}" }

            requestSenderScope.launch {

                LOGGER.trace { "Launch 'decode' coroutine ${request.requestId}" }

                measureTimeMillis {
                    try {
                        val sendAllTime = measureTimeMillis {
                            if (usePinAttributes) {
                                val sessionAlias = request.protobufRawMessageBatch.groupsList[0]
                                    .messagesList[0].rawMessage.sessionAlias

                                LOGGER.trace { "Session aliases for batch ${request.requestId}, alias $sessionAlias" }

                                codecLatency.gaugeInc(listOf(request.streamName))

                                LOGGER.trace { "Start 'decode' method for batch ${request.requestId}" }
                                measureTimeMillis {
                                    decode(request.protobufRawMessageBatch, sessionAlias)
                                }.also { LOGGER.debug { "messageRouterRawBatch ${request.requestId} 'decode' ${it}ms" } }
                            } else {
                                measureTimeMillis {
                                    decode(request.protobufRawMessageBatch)
                                }.also { LOGGER.debug { "messageRouterRawBatch ${request.requestId} 'decode' ${it}ms" } }
                            }

                            LOGGER.trace {
                                val (firstSequence, lastSequence, stream) = getBatchProps(request.protobufRawMessageBatch)
                                "codec request with hash ${request.requestHash} has been sent (stream=${stream} firstId=${firstSequence} lastId=${lastSequence} hash=${request.requestHash}) requestId=${request.requestId})"
                            }
                            LOGGER.debug { "codec request with hash ${request.requestHash.hashCode()} has been sent" }
                        }
                        StreamWriter.setSendToCodecTime(sendAllTime)
                    } catch (e: Exception) {
                        if (pendingRequests[request.requestId]?.completableDeferred === pendingRequest.completableDeferred) {
                            pendingRequest.completableDeferred.complete(null)
                            pendingRequests.remove(request.requestId)
                        }
                        LOGGER.error(e) { "Unexpected exception while trying to send a codec request" }
                    }
                }.also { LOGGER.info { "${request.requestId} requestSenderScope ${it}ms" } }
            }

            pendingRequest
        }.toResponse()
    }

    protected abstract fun decode(batch: MessageGroupBatch)
    protected abstract fun decode(batch: MessageGroupBatch, sessionAlias: String)

    private fun getBatchProps(batch: MessageGroupBatch): Triple<Long?, Long?, String?> {
        val firstMessage = batch.groupsList.firstOrNull()?.messagesList?.firstOrNull()

        return Triple(
            firstMessage?.sequence,
            batch.groupsList.lastOrNull()?.messagesList?.lastOrNull()?.sequence,
            firstMessage?.let { getSessionAliasAndDirection(it).joinToString(":") }
        )
    }

    companion object {
        private val LOGGER = KotlinLogging.logger {}
    }
}