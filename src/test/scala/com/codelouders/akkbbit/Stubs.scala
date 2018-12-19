package com.codelouders.akkbbit
import akka.util.ByteString
import com.codelouders.akkbbit.common.{MQConnection, MQConnectionParams, MQService}
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

class StubMQService extends MQService[MQConnectionParams, StubConnection] with LazyLogging {

  var reconnect = true
  var isAliveState = true

  def disconnect(): Unit = {
    isAliveState = false
  }

  override def connect(connectionParams: MQConnectionParams): Option[StubConnection] = {
    logger.info(s"[connect] reconnect: $reconnect")
    isAliveState = reconnect
    if (isAliveState)
      Some(new StubConnection)
    else None
  }

  override def isAlive(connection: StubConnection): Boolean = {
    logger.info(s"[isAlive] isAliveState: $isAliveState")
    isAliveState
  }

  override def send(connection: StubConnection, data: ByteString): Boolean = {
    logger.info(s"[send]: isAliveState: $isAliveState, reconnect: $reconnect")
    isAliveState && reconnect
  }
}

class StubConnection extends MQConnection

class StubConnectionParams extends MQConnectionParams {
  override def host: String = ???

  override def port: Int = ???

  override def connectionTimeout: FiniteDuration = 300 millis
}
