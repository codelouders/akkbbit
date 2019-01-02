package com.codelouders.akkbbit
import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.common.RabbitConnection.Connected
import com.codelouders.akkbbit.common._
import com.codelouders.akkbbit.rabbit.RabbitService
import com.rabbitmq.client.Connection
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

class StubService extends RabbitService with LazyLogging {

  var reconnect = true
  var isAliveState = true

  def disconnect(): Unit = {
    isAliveState = false
  }

  override def connect(connectionParams: ConnectionParams): Option[Connection] =
    Some(null)

  override def setUpChannel(
      connection: RabbitConnection,
      connectionParams: RabbitChannel): Option[ActiveConnection] = {
    logger.info(s"[connect] reconnect: $reconnect")
    isAliveState = reconnect
    if (isAliveState)
      Some(ActiveConnection(null, null, connectionParams))
    else None
  }

  override def isAlive(connection: ActiveConnection): Boolean = {
    logger.info(s"[isAlive] isAliveState: $isAliveState")
    isAliveState
  }

  override def send(connection: ActiveConnection, data: ByteString): Boolean = {
    logger.info(s"[send]: isAliveState: $isAliveState, reconnect: $reconnect")
    isAliveState && reconnect
  }
}

class StubConnectionProvider(service: RabbitService)(implicit ac: ActorMaterializer)
    extends ConnectionProvider(ConnectionParams("", 1, 1 second, "", "", ""), service) {

  override lazy val (controlIn, connectionOut) =
    MergeHub
      .source[ControlMsg](8)
      .map { _ ⇒
        logger.info("Getting connection")
        Connected(null)
      }
      .toMat(BroadcastHub.sink(8))(Keep.both)
      .run()
}
