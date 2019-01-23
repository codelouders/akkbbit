package com.codelouders.akkbbit
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.common.ConnectionUpdate.NewConnection
import com.codelouders.akkbbit.common.ControlMsg.GetConnection
import com.codelouders.akkbbit.common._
import com.codelouders.akkbbit.rabbit.RabbitService
import com.rabbitmq.client.Connection
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._

class StubService extends RabbitService with LazyLogging {

  var isAliveState = true

  def disconnect(): Unit = {
    isAliveState = false
  }

  override def connect(connectionParams: ConnectionParams): Option[Connection] =
    Some(null)

  override def setUpChannel(
      connection: Connection,
      connectionParams: RabbitChannelConfig): Option[ActiveConnection] = {
    isAliveState = true
    Some(ActiveConnection(null, null, connectionParams))
  }

  override def isAlive(connection: ActiveConnection): Boolean = {
    logger.info(s"[isAlive] isAliveState: $isAliveState")
    isAliveState
  }

  override def send(connection: ActiveConnection, data: ByteString): Boolean = {
    logger.info(s"[send]: isAliveState: $isAliveState")
    isAliveState
  }
}

class StubConnectionProvider(service: RabbitService)(implicit ac: ActorMaterializer)
    extends ConnectionProvider(ConnectionParams("", 1, 1 second, "", "", ""), service, 1 second) {

  override protected lazy val (controlIn, connectionOut) =
    MergeHub
      .source[ControlMsg](8)
      .map { _ ⇒
        logger.info("Getting connection")
        NewConnection(null)
      }
      .toMat(BroadcastHub.sink(8))(Keep.both)
      .run()

  def updateConnection =
    Source.single(GetConnection).runWith(controlIn)
}
