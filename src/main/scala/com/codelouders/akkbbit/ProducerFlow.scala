package com.codelouders.akkbbit

import akka.NotUsed
import akka.stream.{ActorMaterializer, BufferOverflowException, OverflowStrategy}
import akka.stream.scaladsl.{Flow, MergeHub, Sink, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.SentStatus.MessageSent

import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.Seq

trait ProducerFlow {

  /**
    * TODO!
    * never dies, not even when disconnected.
    * by default when buffer overflows the oldest messages in buffer are going to be dropped
    * We don't support OverflowStrategy.backpressure at the moment!
    * @tparam T
    * @return
    */
  def createFlow[T](
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead): Flow[T, SentStatus, NotUsed]

  /**
    * TODO!
    * @tparam T
    * @return
    */
  def createPassThroughFlow[T](
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
  def createSink[T](): Sink[T, NotUsed]
}

class Producer(
    mqService: MQService,
    reconnectInterval: FiniteDuration,
    maxRetries: Int,
    maxBufferSize: Int = 2048)(implicit am: ActorMaterializer)
    extends ProducerFlow
    with ProducerSink {

  override def createFlow[T](overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, SentStatus, NotUsed] =
    Flow[T]
    //TODO: SEND!
      .map(Right(_))
      .merge(Source.tick(reconnectInterval, reconnectInterval, Left()))
      .statefulMapConcat { () ⇒
//        var connection =
        var buffer = Seq.empty[T]

        {
          case Left(_) ⇒
            Seq.empty

          case Right(msg) ⇒
            if (buffer.size + 1 > maxBufferSize)
              buffer = executeOverFlowStrategy(buffer, msg, overflowStrategy)
            else
              buffer = buffer :+ msg

            if (connected)
              send(buffer)
            else
              Seq.empty
        }
      }
      .map(_ ⇒ MessageSent)

  //TODO: create ampq conf classes and pass them here
  override def createPassThroughFlow[T](
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, PassThroughStatusMessage[T], NotUsed] =
    Flow[T]
      .map(in ⇒ PassThroughStatusMessage(MessageSent, in))

  override def createSink[T](): Sink[T, NotUsed] =
    MergeHub
      .source[T](256)
      .via(createFlow(OverflowStrategy.dropHead))
      .to(Sink.ignore)
      .run()

  private def connected: Boolean = {
    true
  }

  private def send[T](buffer: Seq[T]): Seq[T] = {
    buffer
  }

  private def executeOverFlowStrategy[T](
      buffer: Seq[T],
      msg: T,
      overflowStrategy: OverflowStrategy): Seq[T] = {
    overflowStrategy match {
      case OverflowStrategy.dropHead ⇒
        buffer.tail :+ msg

      case OverflowStrategy.dropNew ⇒
        buffer

      case OverflowStrategy.fail ⇒
        throw BufferOverflowException("Ampq flow buffer max size reached! Failing flow.")

      case OverflowStrategy.dropBuffer ⇒
        Seq.empty

      case OverflowStrategy.dropTail ⇒
        buffer.dropRight(1) :+ msg
    }
  }
}
