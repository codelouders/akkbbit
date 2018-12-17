package com.codelouders.akkbbit.rabbit

import com.codelouders.akkbbit.{MQConnection, MQConnectionParams}
import com.rabbitmq.client.Connection

import scala.concurrent.duration.FiniteDuration

final case class RabbitMQConnection(connection: Connection) extends MQConnection

final case class RabbitConnectionParams(
    host: String,
    port: Int,
    connectionTimeout: FiniteDuration,
    virtualHost: String,
    username: String,
    password: String)
    extends MQConnectionParams
