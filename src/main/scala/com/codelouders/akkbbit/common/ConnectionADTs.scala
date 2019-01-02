package com.codelouders.akkbbit.common

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection

import scala.concurrent.duration.FiniteDuration

final case class ActiveConnection(
    connection: Connection,
    channel: Channel,
    connectionParams: RabbitChannelConfig)

final case class ConnectionParams(
    host: String,
    port: Int,
    connectionTimeout: FiniteDuration,
    virtualHost: String,
    username: String,
    password: String)

final case class RabbitChannelConfig(
    queue: RabbitQueueConfig,
    exchange: Option[RabbitExchangeConfig] = None,
    binding: Option[RabbitBindingConfig] = None)

final case class RabbitQueueConfig(
    name: String,
    durable: Boolean = true,
    exclusive: Boolean = false,
    autoDelete: Boolean = false,
    arguments: Map[String, AnyRef] = Map.empty)

final case class RabbitExchangeConfig(name: String, exchangeType: String, durable: Boolean = true)

final case class RabbitBindingConfig(
    queue: RabbitQueueConfig,
    exchange: RabbitExchangeConfig,
    routingKey: String = "")
