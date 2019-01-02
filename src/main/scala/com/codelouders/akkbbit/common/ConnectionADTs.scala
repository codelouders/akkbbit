package com.codelouders.akkbbit.common

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection

import scala.concurrent.duration.FiniteDuration

final case class ActiveConnection(
    connection: Connection,
    channel: Channel,
    connectionParams: RabbitChannel)

final case class ConnectionParams(
    host: String,
    port: Int,
    connectionTimeout: FiniteDuration,
    virtualHost: String,
    username: String,
    password: String)

final case class RabbitChannel(
    queue: RabbitQueue,
    exchange: Option[RabbitExchange] = None,
    binding: Option[RabbitBinding] = None)

final case class RabbitQueue(
    name: String,
    durable: Boolean = true,
    exclusive: Boolean = false,
    autoDelete: Boolean = false,
    arguments: Map[String, AnyRef] = Map.empty)

final case class RabbitExchange(name: String, exchangeType: String, durable: Boolean = true)

final case class RabbitBinding(
    queue: RabbitQueue,
    exchange: RabbitExchange,
    routingKey: String = "")
