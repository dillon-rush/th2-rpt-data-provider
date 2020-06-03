package com.exactpro.th2.reportdataprovider

import com.exactpro.cradle.messages.StoredMessage
import com.exactpro.th2.infra.grpc.Message
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger { }

class InstantSerializer : JsonSerializer<Instant>() {
    override fun serialize(value: Instant?, generator: JsonGenerator?, serializers: SerializerProvider?) {
        generator?.writeString(formatter.format(value))
    }
}

class InstantDeserializer : JsonDeserializer<Instant>() {
    override fun deserialize(parser: JsonParser?, context: DeserializationContext?): Instant {
        return Instant.from(formatter.parse(parser?.text))
    }
}

fun String.toInstant(): Instant? {
    return try {
        Instant.from(formatter.parse(this))
    } catch (e: Exception) {
        logger.error(e) { "unable to parse instant from string '$this'" }
        null
    }
}

fun <T, R> Sequence<T>.optionalFilter(value: R?, filter: (R, Sequence<T>) -> Sequence<T>): Sequence<T> {
    return if (value == null) this else filter(value, this)
}

fun StoredMessage.getMessageType(): String {
    try {
        return Message.parseFrom(this.content).metadata.messageType
    } catch (e: Exception) {
        logger.error(e) { "unable to get message type (id=${this.id})" }
    }

    return "unknown"
}
