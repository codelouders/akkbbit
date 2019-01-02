package com.codelouders.akkbbit.common

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import com.codelouders.akkbbit.common.ControlMsg.GetCurrentConnection
import com.codelouders.akkbbit.common.RabbitConnection.NotConnected
import com.codelouders.akkbbit.rabbit.RabbitService
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable.Seq

class ConnectionProvider(connectionParams: ConnectionParams, rabbitService: RabbitService)(
    implicit am: ActorMaterializer)
    extends LazyLogging {

  private[akkbbit] lazy val (
    controlIn: Sink[ControlMsg, NotUsed],
    connectionOut: Source[RabbitConnection, NotUsed]) =
    MergeHub
      .source[ControlMsg](8)
      .viaMat(connectionStateFlow)(Keep.left)
      .toMat(BroadcastHub.sink(8))(Keep.both)
      .run()

  private def connectionStateFlow: Flow[ControlMsg, RabbitConnection, NotUsed] = {
    Flow[ControlMsg]
      .statefulMapConcat { () ⇒
        var connection = rabbitService.connect(connectionParams)

        {
          case GetCurrentConnection(reconnect) ⇒
            if (connection.exists(_.isOpen))
              Seq(RabbitConnection.Connected(connection.get))
            else if (reconnect) {
              connection = rabbitService.connect(connectionParams)

              Seq(
                connection
                  .map { conn ⇒
                    RabbitConnection.Connected(conn)
                  }
                  .getOrElse(RabbitConnection.NotConnected))
            }
            else
              Seq(NotConnected)
        }
      }
  }
}
