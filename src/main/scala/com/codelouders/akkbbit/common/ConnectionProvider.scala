package com.codelouders.akkbbit.common

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, MergeHub, Sink, Source}
import com.codelouders.akkbbit.common.ControlMsg.GetConnection
import com.codelouders.akkbbit.rabbit.RabbitService
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

/**
  * New instance of this class = new connection to rabbit.
  * Instance can and should be reuse as long as we want to connect to the same rabbit.
  * Flow/sink will create it's own separate channel (see rabbit java client docs for more info)
  *
  * @param connectionParams
  * @param rabbitService
  * @param am
  */
class ConnectionProvider(
    connectionParams: ConnectionParams,
    rabbitService: RabbitService,
    reconnectInterval: FiniteDuration)(implicit am: ActorMaterializer)
    extends LazyLogging {

  protected lazy val (
    controlIn: Sink[ControlMsg, NotUsed],
    connectionOut: Source[ConnectionUpdate, NotUsed]) =
    MergeHub
      .source[ControlMsg](8)
      .merge(Source.tick(0 seconds, reconnectInterval, GetConnection), true)
      .viaMat(connectionStateFlow)(Keep.left)
      .toMat(BroadcastHub.sink(8))(Keep.both)
      .run()

  def connectionUpdateSource: Source[ConnectionUpdate, NotUsed] =
    connectionOut

  def forceRefresh: Unit = {
    Source.single(GetConnection).runWith(controlIn)
  }

  private def connectionStateFlow: Flow[ControlMsg, ConnectionUpdate, NotUsed] = {
    Flow[ControlMsg]
      .statefulMapConcat { () ⇒
        var connection = rabbitService.connect(connectionParams)

        {
          case GetConnection ⇒
            if (connection.exists(_.isOpen))
              Seq(ConnectionUpdate.Connected(connection.get))
            else {
              connection = rabbitService.connect(connectionParams)

              Seq(
                connection
                  .map { conn ⇒
                    ConnectionUpdate.Connected(conn)
                  }
                  .getOrElse(ConnectionUpdate.NotConnected))
            }
        }
      }
  }
}
