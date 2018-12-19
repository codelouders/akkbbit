package com.codelouders.akkbbit.rabbit

import com.codelouders.akkbbit.common.{MQConnection, MQConnectionParams}
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection

import scala.concurrent.duration.FiniteDuration

final case class RabbitMQConnection(
    connection: Connection,
    channel: Channel,
    connectionParams: RabbitConnectionParams)
    extends MQConnection

final case class RabbitConnectionParams(
    host: String,
    port: Int,
    connectionTimeout: FiniteDuration,
    virtualHost: String,
    username: String,
    password: String,
    queue: Option[RabbitQueue],
    exchangeName: Option[String])
    extends MQConnectionParams {
  require(queue.orElse(exchangeName).isDefined, "Either queue or exchange need to be defined")
}

final case class RabbitQueue(
    name: String,
    durable: Boolean = true,
    exclusive: Boolean = false,
    autoDelete: Boolean = false)
