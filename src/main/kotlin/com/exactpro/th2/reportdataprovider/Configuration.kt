package com.exactpro.th2.reportdataprovider

import mu.KotlinLogging


class Variable(
    name: String,
    defaultValue: String,
    showInLog: Boolean = true
) {
    private val logger = KotlinLogging.logger { }

    val value: String = System.getenv(name)
        .also {
            logger.info {
                val valueToLog = if (showInLog) it ?: defaultValue else "*****"

                if (it == null)
                    "environment variable '$name' is not set - defaulting to '$valueToLog'"
                else
                    "environment variable '$name' is set to '$valueToLog'"
            }
        }
        ?: defaultValue
}

class Configuration(
    val hostname: Variable = Variable("HTTP_HOST", "localhost"),
    val port: Variable = Variable("HTTP_PORT", "8080"),
    val cassandraDatacenter: Variable = Variable("CASSANDRA_DATA_CENTER", "kos"),
    val cassandraHost: Variable = Variable("CASSANDRA_HOST", "cassandra"),
    val cassandraPort: Variable = Variable("CASSANDRA_PORT", "9042"),
    val cassandraKeyspace: Variable = Variable("CASSANDRA_KEYSPACE", "demo"),
    val cassandraUsername: Variable = Variable("CASSANDRA_USERNAME", "guest"),
    val cassandraPassword: Variable = Variable("CASSANDRA_PASSWORD", "guest", false),
    val cassandraInstance: Variable = Variable("CRADLE_INSTANCE_NAME", "instance1")
)
