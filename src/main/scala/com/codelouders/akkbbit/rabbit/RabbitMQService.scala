package com.codelouders.akkbbit.rabbit

import akka.util.ByteString
import com.codelouders.akkbbit.MQService
import com.rabbitmq.client.ConnectionFactory

class RabbitMQService extends MQService[RabbitConnectionParams, RabbitMQConnection] {

  override def connect(connectionParams: RabbitConnectionParams): RabbitMQConnection = {
    val factory = new ConnectionFactory

    factory.setUsername(connectionParams.username)
    factory.setPassword(connectionParams.password)
    factory.setVirtualHost(connectionParams.virtualHost)
    factory.setHost(connectionParams.host)
    factory.setPort(connectionParams.port)
    factory.setConnectionTimeout(connectionParams.connectionTimeout.toMillis.toInt)

    //do we need to return channel return here?
    RabbitMQConnection(factory.newConnection)
  }

  override def isAlive(connection: RabbitMQConnection): Boolean =
    connection.connection.isOpen

  //do we need channel here?
  override def send(connection: RabbitMQConnection, data: ByteString): Boolean = {
    true
  }
}
