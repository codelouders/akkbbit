package com.codelouders.akkbbit.rabbit

import akka.util.ByteString
import com.codelouders.akkbbit.common.MQService
import com.rabbitmq.client.ConnectionFactory
import com.typesafe.scalalogging.LazyLogging

import scala.util.Try

class RabbitMQService
    extends MQService[RabbitConnectionParams, RabbitMQConnection]
    with LazyLogging {

  override def connect(connectionParams: RabbitConnectionParams): Option[RabbitMQConnection] = {
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

    val connection = factory.newConnection
    val channel = connection.createChannel()

    Try {
      connectionParams.exchange.foreach { exchange ⇒
        channel.exchangeDeclare(exchange.name, exchange.exchangeType, exchange.durable)
      }

      connectionParams.queue.foreach { queue ⇒
        channel.queueDeclare(queue.name, queue.durable, queue.exclusive, queue.autoDelete, null)
      }

      for {
        binding <- connectionParams.binding
      } yield channel.queueBind(binding.queue.name, binding.exchange.name, binding.routingKey)

      RabbitMQConnection(connection, channel, connectionParams)
    }.fold(
      { e ⇒
        logger.error(s"Cannot connect: ${e.getMessage}", e)
        None
      },
      Some(_)
    )
  }

  override def isAlive(connection: RabbitMQConnection): Boolean =
    connection.connection.isOpen && connection.channel.isOpen

  //do we need channel here?
  override def send(connection: RabbitMQConnection, data: ByteString): Boolean = {
    Try {
      val exchange = connection.connectionParams.exchange.map(_.name).getOrElse("")
      val queue = connection.connectionParams.queue.map(_.name).getOrElse("")
      connection.channel.basicPublish(exchange, queue, null, data.toArray)
    }.fold(
      { e ⇒
        logger.error(s"Cannot connect: ${e.getMessage}", e)
        false
      },
      _ ⇒ true
    )
  }
}
