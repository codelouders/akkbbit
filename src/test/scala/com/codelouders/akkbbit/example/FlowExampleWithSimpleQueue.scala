package com.codelouders.akkbbit.example

import java.nio.ByteOrder
import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ConnectionParams,
  ConnectionProvider,
  RabbitChannelConfig,
  RabbitQueueConfig
}
import com.codelouders.akkbbit.producer.SentError.TooManyAttempts
import com.codelouders.akkbbit.producer.SentStatus.{FailedToSent, MessageSent}
import com.codelouders.akkbbit.producer.{AkkbbitProducer, PassThroughStatusMessage}
import com.codelouders.akkbbit.rabbit.RabbitService

import scala.concurrent.duration._

object FlowExampleWithSimpleQueue extends App {

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
  val serialiser: Int ⇒ ByteString = ByteString.createBuilder.putInt(_).result()

  Source
    .fromIterator(() ⇒ Iterator from 0)
    .throttle(1, 500 millis, 1, ThrottleMode.Shaping)
    .via(producerBuilder.createFlow(serialiser, rabbitChannelConfig))
    .map {
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
}
