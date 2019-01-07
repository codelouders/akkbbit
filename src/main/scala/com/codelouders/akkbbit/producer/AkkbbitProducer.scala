package com.codelouders.akkbbit.producer

import akka.NotUsed
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, MergeHub, Sink, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.common._
import com.codelouders.akkbbit.producer.IncomingMessage.{ConnectionInfo, MessageToSend, RetryTick}
import com.codelouders.akkbbit.rabbit.RabbitService
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.Seq

trait ProducerFlow {

  /**
    * TODO!
    * never dies, not even when disconnected.
    * by default when buffer overflows the oldest messages in buffer are going to be dropped
    * We don't support OverflowStrategy.backpressure at the moment!
    *
    * @tparam T
    * @return
    */
  def createFlow[T](
      serializer: T ⇒ ByteString,
      channelConfig: RabbitChannelConfig,
      maxRetries: Int = 0,
      maxBufferSize: Int = 2048,
      reconnectInterval: FiniteDuration = 1 second,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, PassThroughStatusMessage[T], NotUsed]
}

trait ProducerSink {

  /**
    * TODO: better wording
    * This never dyes! Only one channel is created.
    * Many producers can be connected because it uses merghub under the hood.
    * when buffer overflows the oldest messages in buffer are going to be dropped
    *
    * @tparam T
    */
  def createSink[T](
      serializer: T ⇒ ByteString,
      channelConfig: RabbitChannelConfig,
      maxBufferSize: Int = 2048,
      retryInterval: FiniteDuration = 1 second): Sink[T, NotUsed]
}

/**
  *
  *
  * @param rabbitService
  * @param connectionProvider
  * @param connectionParams
  * @param maxRetries
  * @param reconnectInterval
  * @param maxBufferSize
  * @param am
  */
class AkkbbitProducer(rabbitService: RabbitService, connectionProvider: ConnectionProvider)(
    implicit am: ActorMaterializer)
    extends ProducerFlow
    with ProducerSink
    with LazyLogging {

  override def createFlow[T](
      serializer: T ⇒ ByteString,
      channelConfig: RabbitChannelConfig,
      maxRetries: Int = 0,
      maxBufferSize: Int = 2048,
      retryInterval: FiniteDuration = 1 second,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, PassThroughStatusMessage[T], NotUsed] = {

    require(
      overflowStrategy != OverflowStrategy.backpressure,
      "Backpressure strategy is not supported")

    Flow[T].async
      .via(
        stateFlow[T](
          serializer = serializer,
          connectionParams = channelConfig,
          retryInterval = retryInterval,
          maxRetries = maxRetries,
          maxBufferSize = maxBufferSize,
          overflowStrategy = overflowStrategy
        ))
  }

  override def createSink[T](
      serializer: T ⇒ ByteString,
      channelConfig: RabbitChannelConfig,
      maxBufferSize: Int = 2048,
      retryInterval: FiniteDuration = 1 second): Sink[T, NotUsed] =
    MergeHub
      .source[T](256)
      .via(createFlow(
        serializer = serializer,
        channelConfig = channelConfig,
        maxRetries = Int.MaxValue,
        maxBufferSize = maxBufferSize,
        retryInterval = retryInterval,
        overflowStrategy = OverflowStrategy.dropHead
      ))
      .to(Sink.ignore)
      .run()

  private def stateFlow[T](
      serializer: T ⇒ ByteString,
      connectionParams: RabbitChannelConfig,
      retryInterval: FiniteDuration,
      maxRetries: Int,
      maxBufferSize: Int,
      overflowStrategy: OverflowStrategy): Flow[T, PassThroughStatusMessage[T], NotUsed] = {

    Flow[T]
      .map(MessageToSend(_))
      .merge(tickingSource(retryInterval).async)
      .merge(connectionProvider.connectionUpdateSource.map(ConnectionInfo).async)
      .statefulMapConcat[PassThroughStatusMessage[T]] { () ⇒
        var connection: Option[ActiveConnection] = None
        var buffer = Seq.empty[RetriableMessage[T]]
        val producerStateService =
          new ProducerStateService[T](
            rabbitService = rabbitService,
            serializer = serializer,
            maxRetries = maxRetries,
            maxBufferSize = maxBufferSize,
            overflowStrategy = overflowStrategy)

        {
          case RetryTick ⇒
            logger.debug("ReconnectionTick")
            val result = connection match {
              case Some(conn) if rabbitService.isAlive(conn) ⇒
                producerStateService.send(buffer, conn)
              case _ ⇒
                producerStateService.detectTooManyAttempts(
                  producerStateService.incNumberOfAttempts(buffer))
            }
            buffer = result.updatedBuffer
            result.output

          case ConnectionInfo(newConn) ⇒
            connection = rabbitService.setUpChannel(newConn, connectionParams)
            connection match {
              case Some(conn) if rabbitService.isAlive(conn) ⇒
                val result = producerStateService.send(buffer, conn)
                buffer = result.updatedBuffer
                result.output
              case _ ⇒
                Seq.empty
            }

          case MessageToSend(msg) ⇒
            buffer = producerStateService.addNewMessageToBuffer(buffer, msg)

            val result = connection match {
              case Some(conn) if rabbitService.isAlive(conn) ⇒
                producerStateService.send(buffer, conn)
              case _ ⇒
                buffer = buffer.updated(buffer.size - 1, buffer.last.copy(attemptsCounter = 1))
                producerStateService.detectTooManyAttempts(buffer)
            }
            buffer = result.updatedBuffer
            result.output
        }
      }
      .async
  }

  protected def tickingSource(reconnectInterval: FiniteDuration): Source[RetryTick.type, NotUsed] =
    Source
      .tick(0 millis, reconnectInterval, RetryTick)
      .mapMaterializedValue(_ ⇒ NotUsed)

}
