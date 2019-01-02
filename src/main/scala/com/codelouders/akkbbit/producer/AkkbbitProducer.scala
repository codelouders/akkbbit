package com.codelouders.akkbbit.producer

import akka.NotUsed
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, MergeHub, Sink, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.common.ControlMsg.GetConnection
import com.codelouders.akkbbit.common._
import com.codelouders.akkbbit.producer.IncomingMessage.{
  ConnectionInfo,
  MessageToSend,
  ReconnectionTick
}
import com.codelouders.akkbbit.producer.OutboundMessage.{Reconnect, Result}
import com.codelouders.akkbbit.producer.SentError.TooManyAttempts
import com.codelouders.akkbbit.producer.SentStatus.{FailedToSent, MessageSent}
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
      reconnectInterval: FiniteDuration = 1 second): Sink[T, NotUsed]
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
      reconnectInterval: FiniteDuration = 1 second,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, PassThroughStatusMessage[T], NotUsed] = {

    require(
      overflowStrategy != OverflowStrategy.backpressure,
      "Backpressure strategy is not supported")

    Flow[T].async
      .via(stateFlow[T](
        serializer = serializer,
        connectionParams = channelConfig,
        reconnectInterval = reconnectInterval,
        maxRetries = maxRetries,
        maxBufferSize = maxBufferSize,
        overflowStrategy = overflowStrategy
      ))
      .alsoTo {
        Flow[OutboundMessage[T]]
          .collect {
            case Reconnect ⇒ GetConnection
          }
          .to(connectionProvider.controlIn)
      }
      .collect {
        case Result(res) ⇒ res
      }
  }

  override def createSink[T](
      serializer: T ⇒ ByteString,
      channelConfig: RabbitChannelConfig,
      maxBufferSize: Int = 2048,
      reconnectInterval: FiniteDuration = 1 second): Sink[T, NotUsed] =
    MergeHub
      .source[T](256)
      .via(createFlow(
        serializer = serializer,
        channelConfig = channelConfig,
        maxRetries = Int.MaxValue,
        maxBufferSize = maxBufferSize,
        reconnectInterval = reconnectInterval,
        overflowStrategy = OverflowStrategy.dropHead
      ))
      .to(Sink.ignore)
      .run()

  private def stateFlow[T](
      serializer: T ⇒ ByteString,
      connectionParams: RabbitChannelConfig,
      reconnectInterval: FiniteDuration,
      maxRetries: Int,
      maxBufferSize: Int,
      overflowStrategy: OverflowStrategy): Flow[T, OutboundMessage[T], NotUsed] =
    Flow[T]
      .map(MessageToSend(_))
      .merge(tickingSource(reconnectInterval).async)
      .merge(connectionProvider.connectionOut.map(ConnectionInfo).async)
      .statefulMapConcat[OutboundMessage[T]] { () ⇒
        var connection: Option[ActiveConnection] = None
        var buffer = Seq.empty[RetriableMessage[T]]

        def trySend(conn: ActiveConnection) = {
          logger.debug(s"Messages to send: $buffer")
          val returnState = send(buffer, conn, maxRetries, serializer)
          logger.debug(s"Messages saved for another retry: ${returnState.newBuffer}")
          buffer = returnState.newBuffer
          returnState.output.map(Result(_))
        }

        def tooManyAttempts(list: Seq[RetriableMessage[T]]) = {
          val (tooManyAttempts, updatedBuffer) =
            spitByAttempts(list, maxRetries)
          buffer = updatedBuffer
          logger.debug(s"Messages saved for retry: $updatedBuffer")
          logger.debug(s"Failed to sent(too many attempts): $tooManyAttempts")
          tooManyAttempts.map(wrapIntoTooManyAttemptsMessage(maxRetries)).map(Result(_))
        }

        {
          case ReconnectionTick ⇒
            logger.debug("ReconnectionTick")

            connection match {
              case Some(conn) if connection.exists(rabbitService.isAlive) ⇒
                trySend(conn)
              case _ ⇒
                Seq(Reconnect)
            }

          case ConnectionInfo(newConn) ⇒
            connection = rabbitService.setUpChannel(newConn, connectionParams)
            connection match {
              case Some(conn) if rabbitService.isAlive(conn) ⇒
                trySend(conn)
              case _ ⇒
                tooManyAttempts(incNumberOfAttempts(buffer))
            }

          case MessageToSend(msg) ⇒
            if (buffer.size + 1 > maxBufferSize)
              buffer = BufferOverflowExecutor.executeStrategy(buffer, msg, overflowStrategy)
            else
              buffer = buffer :+ RetriableMessage(attemptsCounter = 0, message = msg)

            connection match {
              case Some(conn) if rabbitService.isAlive(conn) ⇒
                trySend(conn)
              case _ ⇒
                buffer = buffer.updated(buffer.size - 1, buffer.last.copy(attemptsCounter = 1))
                tooManyAttempts(buffer)
            }
        }
      }
      .async

  protected def tickingSource(
      reconnectInterval: FiniteDuration): Source[ReconnectionTick.type, NotUsed] =
    Source
      .tick(0 millis, reconnectInterval, ReconnectionTick)
      .mapMaterializedValue(_ ⇒ NotUsed)

  private def incNumberOfAttempts[T](buffer: Seq[RetriableMessage[T]]): Seq[RetriableMessage[T]] = {
    logger.debug(s"buffer before update attempts: $buffer")
    buffer
      .map(el ⇒ el.copy(attemptsCounter = el.attemptsCounter + 1))
  }

  private def spitByAttempts[T](
      buffer: Seq[RetriableMessage[T]],
      maxRetries: Int): (Seq[RetriableMessage[T]], Seq[RetriableMessage[T]]) = {
    buffer
      .partition(_.attemptsCounter > maxRetries)
  }

  private def send[T](
      buffer: Seq[RetriableMessage[T]],
      connection: ActiveConnection,
      maxRetries: Int,
      serializer: T ⇒ ByteString): SendResult[T] = {

    val (sent, notSent) = buffer.partition { el ⇒
      rabbitService.send(connection, serializer(el.message))
    }

    val (tooManyAttempts, updatedBuffer) = spitByAttempts(incNumberOfAttempts(notSent), maxRetries)

    val successful = sent.map { el ⇒
      PassThroughStatusMessage(MessageSent, el.message)
    }

    val failures = tooManyAttempts.map(wrapIntoTooManyAttemptsMessage(maxRetries))
    SendResult(successful ++ failures, updatedBuffer)
  }

  private def wrapIntoTooManyAttemptsMessage[T](maxRetries: Int)(
      retriableMessage: RetriableMessage[T]) =
    PassThroughStatusMessage(
      FailedToSent(TooManyAttempts(retriableMessage.attemptsCounter, maxRetries + 1)),
      retriableMessage.message)
}
