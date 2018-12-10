package com.codelouders.akkbbit

import akka.NotUsed
import akka.stream.{ActorMaterializer, OverflowStrategies, OverflowStrategy}
import akka.stream.scaladsl.{Flow, MergeHub, Sink}
import com.codelouders.akkbbit.SentStatus.MessageSent

trait ProducerFlow {

  /**
    * TODO!
    * @tparam T
    * @return
    */
  def createFlow[T](
      maxBufferSize: Int,
      maxRetries: Int,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead): Flow[T, SentStatus, NotUsed]

  /**
    * TODO!
    * @tparam T
    * @return
    */
  def createPassThroughFlow[T](): Flow[T, PassThroughStatusMessage[T], NotUsed]
}

trait ProducerSink {

  /**
    * TODO: better wording
    * This never dyes! Only one connection created.
    * Many producers can be connected because it uses merghub under the hood.
    * @tparam T
    */
  def createSink[T](bufferSize: Int)(implicit am: ActorMaterializer): Sink[T, NotUsed]
}

object Producer extends ProducerFlow with ProducerSink {

  override def createFlow[T](
      maxBufferSize: Int,
      maxRetries: Int,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropHead)
    : Flow[T, SentStatus, NotUsed] =
    Flow[T]
      .buffer(maxBufferSize, overflowStrategy) //
      //TODO: SEND!
      .map(_ ⇒ MessageSent)

  //TODO: create ampq conf classes and pass them here
  override def createPassThroughFlow[T](): Flow[T, PassThroughStatusMessage[T], NotUsed] =
    Flow[T]
    //TODO: SEND!
      .map(in ⇒ PassThroughStatusMessage(MessageSent, in))

  override def createSink[T](bufferSize: Int)(implicit am: ActorMaterializer): Sink[T, NotUsed] =
    MergeHub
      .source[T](bufferSize)
      //TODO: send (reuse flow)
      .to(Sink.ignore)
      .run()
}
