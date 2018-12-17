package com.codelouders.akkbbit

import akka.NotUsed
import akka.stream.{ActorMaterializer, BufferOverflowException, OverflowStrategy}
import akka.stream.scaladsl.{Flow, MergeHub, Sink, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.SentError.TooManyAttempts
import com.codelouders.akkbbit.SentStatus.{FailedToSent, MessageSent}
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
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, PassThroughStatusMessage[T], NotUsed]
}

trait ProducerSink {

  /**
    * TODO: better wording
    * This never dyes! Only one connection created.
    * Many producers can be connected because it uses merghub under the hood.
    * when buffer overflows the oldest messages in buffer are going to be dropped
    *
    * set max retries to 0 is you need immediate - result/feedback
    *
    * @tparam T
    */
  def createSink[T](serializer: T ⇒ ByteString): Sink[T, NotUsed]
}

class Producer(
    mqService: MQService,
    connectionParams: MQConnectionParams,
    maxRetries: Int = Int.MaxValue,
    reconnectInterval: FiniteDuration = 1 second,
    maxBufferSize: Int = 2048)(implicit am: ActorMaterializer)
    extends ProducerFlow
    with ProducerSink
    with LazyLogging {

  override def createFlow[T](
      serializer: T ⇒ ByteString,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, PassThroughStatusMessage[T], NotUsed] = {

    require(overflowStrategy != OverflowStrategy.backpressure, "Backpressure is not supported")

    Flow[T]
      .map(Right(_))
      .async
      .merge(Source.tick(reconnectInterval, reconnectInterval, Left()))
      .async
      .statefulMapConcat[PassThroughStatusMessage[T]] { () ⇒
        var connection = mqService.connect(connectionParams)
        var buffer = Seq.empty[RetriableMessage[T]]

        {
          case Left(_) ⇒
            if (!mqService.isAlive(connection))
              connection = mqService.connect(connectionParams)

            val returnState = send(buffer, connection, serializer)
            buffer = returnState.newBuffer
            returnState.output

          case Right(msg) ⇒
            if (buffer.size + 1 > maxBufferSize)
              buffer = executeOverFlowStrategy(buffer, msg, overflowStrategy)
            else
              buffer = buffer :+ RetriableMessage(attemptsCounter = 0, message = msg)

            if (!mqService.isAlive(connection)) {
              val returnState = send(buffer, connection, serializer)
              buffer = returnState.newBuffer
              returnState.output
            }
            else {
              val (tooManyAttempts, updatedBuffer) = updateNumberOfAttempts(buffer)
              buffer = updatedBuffer
              tooManyAttempts.map(wrapIntoTooManyAttemptsMessage)
            }
        }
      }
      .async
  }

  override def createSink[T](serializer: T ⇒ ByteString): Sink[T, NotUsed] =
    MergeHub
      .source[T](256)
      .via(createFlow(serializer, OverflowStrategy.dropHead))
      .async
      .to(Sink.ignore)
      .run()

  private def updateNumberOfAttempts[T](
      buffer: Seq[RetriableMessage[T]]): (Seq[RetriableMessage[T]], Seq[RetriableMessage[T]]) =
    buffer
      .map(el ⇒ el.copy(attemptsCounter = el.attemptsCounter + 1))
      .partition(_.attemptsCounter > maxRetries)

  private def send[T](
      buffer: Seq[RetriableMessage[T]],
      connection: MQConnection,
      serializer: T ⇒ ByteString): SendResult[T] = {

    val (sent, notSent) = buffer.partition { el ⇒
      mqService.send(connection, serializer(el.message))
    }

    val (tooManyAttempts, updatedBuffer) = updateNumberOfAttempts(notSent)

    val successful = sent.map { el ⇒
      PassThroughStatusMessage(MessageSent, el.message)
    }

    val failures = tooManyAttempts.map(wrapIntoTooManyAttemptsMessage)
    SendResult(successful ++ failures, updatedBuffer)
  }

  private def wrapIntoTooManyAttemptsMessage[T](retriableMessage: RetriableMessage[T]) =
    PassThroughStatusMessage(
      FailedToSent(TooManyAttempts(retriableMessage.attemptsCounter, maxRetries + 1)),
      retriableMessage.message)

  private def executeOverFlowStrategy[T](
      buffer: Seq[RetriableMessage[T]],
      msg: T,
      overflowStrategy: OverflowStrategy): Seq[RetriableMessage[T]] = {

    logger.warn(s"Applying overflow strategy: $overflowStrategy")

    overflowStrategy match {
      case OverflowStrategy.dropHead ⇒
        buffer.tail :+ RetriableMessage(attemptsCounter = 0, message = msg)

      case OverflowStrategy.dropNew ⇒
        buffer

      case OverflowStrategy.fail ⇒
        throw BufferOverflowException("MQ flow buffer max size reached! Failing flow.")

      case OverflowStrategy.dropBuffer ⇒
        Seq.empty

      case OverflowStrategy.dropTail ⇒
        buffer.dropRight(1) :+ RetriableMessage(attemptsCounter = 0, message = msg)
    }
  }
}
