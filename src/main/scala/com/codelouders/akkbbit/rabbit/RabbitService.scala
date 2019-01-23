package com.codelouders.akkbbit.rabbit

import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ActiveConnection,
  ConnectionParams,
  RabbitChannelConfig,
  ConnectionUpdate
}
import com.rabbitmq.client.{Connection, ConnectionFactory}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.util.Try

class RabbitService extends LazyLogging {

  def connect(connectionParams: ConnectionParams): Option[Connection] = {
    Try {
      val factory = new ConnectionFactory

      factory.setUsername(connectionParams.username)
      factory.setPassword(connectionParams.password)
      factory.setVirtualHost(connectionParams.virtualHost)
      factory.setHost(connectionParams.host)
      factory.setPort(connectionParams.port)
      factory.setConnectionTimeout(connectionParams.connectionTimeout.toMillis.toInt)

      // we do it on akka level - no need to reconnect here
      factory.setAutomaticRecoveryEnabled(false)
      //do we need to return channel return here?

      factory.newConnection
    }.fold(e ⇒ {
      logger.error("Cannot connect to rabbit", e)
      None
    }, Some(_))
  }

  def setUpChannel(
      rabbitConn: Connection,
      channelConfig: RabbitChannelConfig): Option[ActiveConnection] = {

    Try {
      val channel = rabbitConn.createChannel()
      channel.queueDeclare(
        channelConfig.queue.name,
        channelConfig.queue.durable,
        channelConfig.queue.exclusive,
        channelConfig.queue.autoDelete,
        channelConfig.queue.arguments.asJava
      )

      channelConfig.exchange.foreach { exchange ⇒
        channel.exchangeDeclare(exchange.name, exchange.exchangeType, exchange.durable)
      }

      channelConfig.binding.foreach { binding =>
        channel.queueBind(binding.queue.name, binding.exchange.name, binding.routingKey)
      }

      ActiveConnection(rabbitConn, channel, channelConfig)
    }.fold(
      { e ⇒
        logger.error(s"Cannot connect: ${e.getMessage}", e)
        None
      },
      Some(_)
    )

  }

  def isAlive(connection: ActiveConnection): Boolean =
    connection.connection.isOpen && connection.channel.isOpen

  //do we need channel here?
  def send(connection: ActiveConnection, data: ByteString): Boolean = {
    Try {
      val queue = connection.connectionParams.queue.name
      val exchange = connection.connectionParams.exchange.map(_.name)
      val routingKey =
        exchange
          .flatMap(_ => connection.connectionParams.binding.map(_.routingKey))
          .getOrElse(queue)
      connection.channel.basicPublish(exchange.getOrElse(""), routingKey, null, data.toArray)
    }.fold(
      { e ⇒
        logger.error(s"Cannot connect: ${e.getMessage}", e)
        false
      },
      _ ⇒ true
    )
  }
}
