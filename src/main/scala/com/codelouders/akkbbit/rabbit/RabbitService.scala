package com.codelouders.akkbbit.rabbit

import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ActiveConnection,
  ConnectionParams,
  RabbitChannel,
  RabbitConnection
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
      rabbitConn: RabbitConnection,
      connectionParams: RabbitChannel): Option[ActiveConnection] = {

    rabbitConn match {

      case RabbitConnection.Connected(connection) ⇒
        val channel = connection.createChannel()

        Try {
          channel.queueDeclare(
            connectionParams.queue.name,
            connectionParams.queue.durable,
            connectionParams.queue.exclusive,
            connectionParams.queue.autoDelete,
            connectionParams.queue.arguments.asJava
          )

          connectionParams.exchange.foreach { exchange ⇒
            channel.exchangeDeclare(exchange.name, exchange.exchangeType, exchange.durable)
          }

          connectionParams.binding.foreach { binding =>
            channel.queueBind(binding.queue.name, binding.exchange.name, binding.routingKey)
          }

          ActiveConnection(connection, channel, connectionParams)
        }.fold(
          { e ⇒
            logger.error(s"Cannot connect: ${e.getMessage}", e)
            None
          },
          Some(_)
        )

      case RabbitConnection.NotConnected ⇒ None
    }
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
