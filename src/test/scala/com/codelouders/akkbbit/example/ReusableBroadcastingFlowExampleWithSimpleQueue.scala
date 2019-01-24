package com.codelouders.akkbbit.example

import java.nio.ByteOrder
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Sink, Source}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ConnectionParams,
  ConnectionProvider,
  RabbitChannelConfig,
  RabbitQueueConfig
}
import com.codelouders.akkbbit.producer.SentStatus.{FailedToSent, MessageSent}
import com.codelouders.akkbbit.producer.{AkkbbitProducer, PassThroughStatusMessage}
import com.codelouders.akkbbit.rabbit.RabbitService

import scala.concurrent.duration._

object ReusableBroadcastingFlowExampleWithSimpleQueue extends App {

  implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
  implicit val am: ActorMaterializer = ActorMaterializer()
  val service = new RabbitService()
  val connectionProvider =
    new ConnectionProvider(
      ConnectionParams("host", 1234, 500 millis, "vHost", "user", "pass"),
      service,
      1 seconds)

  val rabbitChannelConfig = RabbitChannelConfig(RabbitQueueConfig("queueName"), None, None)

  val producerBuilder = new AkkbbitProducer(service, connectionProvider)

  implicit val order = ByteOrder.BIG_ENDIAN
  val serialiser: OriginalMsg ⇒ ByteString = { in ⇒
    ByteString.createBuilder.putInt(in.messageId).result()
  }

  val (in, out) = createReusableInAndOut()

  // connect to output of flow and filter on source id A
  out
    .filter(_.message.sourceId == "A")
    .map {
      // callback for source id A
      case original @ PassThroughStatusMessage(MessageSent, message) ⇒
        // message → access to original message when sending to rabbit succeeded
        original
      case original @ PassThroughStatusMessage(FailedToSent(cause), message) ⇒
        // message → access to original message when sending to rabbit succeeded
        // cause → reason of failure.
        //         TooManyAttempts    → failed to send (no rabbit connection)
        //         SerialisationError → failed to serialise message
        original
    }
    .runWith(Sink.ignore)

  // connect to output of flow and filter on source id B
  // Before connecting source we need to connect listeners first otherwise we might loose few first message
  out
    .filter(_.message.sourceId == "B")
    .map {
      // callback for source id B
      case original @ PassThroughStatusMessage(MessageSent, message) ⇒
        // message → access to original message when sending to rabbit succeeded
        original
      case original @ PassThroughStatusMessage(FailedToSent(cause), message) ⇒
        // message → access to original message when sending to rabbit succeeded
        // cause → reason of failure.
        //         TooManyAttempts    → failed to send (no rabbit connection)
        //         SerialisationError → failed to serialise message
        original
    }
    .runWith(Sink.ignore)

  // Both "producers will publish to rabbit using same channel"
  // Rabbit connection and channel will be reused
  // in both case in case of disconnection to rabbit flow will try to reconnect indefinitely
  // in a meantime returning TooManyAttempts error. It's possible to set bigger buffer size and higher retry number
  createProducerSource("A")
    .runWith(in)

  createProducerSource("B")
    .runWith(in)

  def createReusableInAndOut()
    : (Sink[OriginalMsg, NotUsed], Source[PassThroughStatusMessage[OriginalMsg], NotUsed]) =
    MergeHub
      .source(8)
      .via(producerBuilder.createFlow(serialiser, rabbitChannelConfig))
      .toMat(BroadcastHub.sink(8))(Keep.both)
      .run()

  def createProducerSource(sourceId: String) =
    Source
      .fromIterator(() ⇒ Iterator from 0)
      .throttle(1, 500 millis, 1, ThrottleMode.Shaping)
      .map(i ⇒ OriginalMsg(sourceId, i))

  case class OriginalMsg(sourceId: String, messageId: Int)
}
