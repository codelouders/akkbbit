package com.codelouders.akkbbit
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging

class FakeMQService extends MQService[MQConnectionParams, FakeConnection] with LazyLogging {

  var reconnect = true
  var isAliveState = true

  def disconnect(): Unit = {
    isAliveState = false
  }

  override def connect(connectionParams: MQConnectionParams): FakeConnection = {
    isAliveState = reconnect
    new FakeConnection
  }

  override def isAlive(connection: FakeConnection): Boolean = {
    logger.info(s"Is alive: $isAliveState")
    isAliveState
  }

  override def send(connection: FakeConnection, data: ByteString): Boolean = {
    isAliveState && reconnect
  }
}

class FakeConnection extends MQConnection
