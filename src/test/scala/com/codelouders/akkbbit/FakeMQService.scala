package com.codelouders.akkbbit
import akka.util.ByteString

class FakeMQService extends MQService[MQConnectionParams, FakeConnection] {

  override def connect(connectionParams: MQConnectionParams): FakeConnection = {
    new FakeConnection
  }

  override def isAlive(connection: FakeConnection): Boolean = {
    connection.isAlive
  }

  override def send(connection: FakeConnection, data: ByteString): Boolean = {
    if (connection.failNextSendAttempt) {
      connection.disconnect
      false
    }
    else
      true
  }
}

class FakeConnection extends MQConnection {
  var isAlive = true
  var failNextSendAttempt = false

  def disconnect: Unit = {
    isAlive = false
  }

  def failNext = {
    failNextSendAttempt = true
  }
}
