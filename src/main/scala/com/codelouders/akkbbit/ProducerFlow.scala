package com.codelouders.akkbbit

import akka.NotUsed
import akka.stream.{ActorMaterializer, BufferOverflowException, OverflowStrategy}
import akka.stream.scaladsl.{Flow, MergeHub, Sink, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.SentError.TooManyAttempts
import com.codelouders.akkbbit.SentStatus.{FailedToSent, MessageSent}
import com.typesafe.scalalogging.LazyLogging

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
    * @tparam T
    */
  def createSink[T](serializer: T ⇒ ByteString): Sink[T, NotUsed]
}

class Producer(
    mqService: MQService,
    connectionParams: MQConnectionParams,
    reconnectInterval: FiniteDuration,
    maxRetries: Int,
    maxBufferSize: Int = 2048)(implicit am: ActorMaterializer)
    extends ProducerFlow
    with ProducerSink
    with LazyLogging {

  override def createFlow[T](
      serializer: T ⇒ ByteString,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, PassThroughStatusMessage[T], NotUsed] =
    Flow[T]
      .map(Right(_))
      .merge(Source.tick(reconnectInterval, reconnectInterval, Left()))
      .statefulMapConcat[PassThroughStatusMessage[T]] { () ⇒
        var connection = mqService.connect(connectionParams)
        var buffer = Seq.empty[RetriableMessage[T]]

        {
          case Left(_) ⇒
            if (!mqService.isAlive(connection))
              connection = mqService.connect(connectionParams)

            val result = send(buffer, connection, serializer)
            buffer = result.newBuffer
            result.output

          case Right(msg) ⇒
            if (buffer.size + 1 > maxBufferSize)
              buffer = executeOverFlowStrategy(buffer, msg, overflowStrategy)
            else
              buffer = buffer :+ RetriableMessage(attemptsCounter = 0, message = msg)

            if (!mqService.isAlive(connection)) {
              val result = send(buffer, connection, serializer)
              buffer = result.newBuffer
              result.output
            }
            else
              Seq.empty

        }
      }

  override def createSink[T](serializer: T ⇒ ByteString): Sink[T, NotUsed] =
    MergeHub
      .source[T](256)
      .via(createFlow(serializer, OverflowStrategy.dropHead))
      .to(Sink.ignore)
      .run()

  private def send[T](
      buffer: Seq[RetriableMessage[T]],
      connection: MQConnection,
      serializer: T ⇒ ByteString): SendResult[T] = {

    val (sent, notSent) = buffer.partition { el ⇒
      mqService.send(connection, serializer(el.message))
    }

    val (drop, leave) =
      notSent
        .map(el ⇒ el.copy(attemptsCounter = el.attemptsCounter + 1))
        .partition(_.attemptsCounter > maxRetries)

    val successful = sent.map { el ⇒
      PassThroughStatusMessage(MessageSent, el.message)
    }

    val failures = drop.map { el ⇒
      PassThroughStatusMessage(
        FailedToSent(TooManyAttempts(el.attemptsCounter, maxRetries + 1)),
        el.message)
    }

    SendResult(successful ++ failures, leave)
  }

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
        throw BufferOverflowException("Ampq flow buffer max size reached! Failing flow.")

      case OverflowStrategy.dropBuffer ⇒
        Seq.empty

      case OverflowStrategy.dropTail ⇒
        buffer.dropRight(1) :+ RetriableMessage(attemptsCounter = 0, message = msg)
    }
  }
}
