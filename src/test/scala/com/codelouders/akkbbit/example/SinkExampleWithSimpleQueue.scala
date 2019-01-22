package com.codelouders.akkbbit.example

import java.nio.ByteOrder
import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ConnectionParams,
  ConnectionProvider,
  RabbitChannelConfig,
  RabbitQueueConfig
}
import com.codelouders.akkbbit.producer.AkkbbitProducer
import com.codelouders.akkbbit.rabbit.RabbitService

import scala.concurrent.duration._

object SinkExampleWithSimpleQueue extends App {

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
    // It will internally buffer up to 8192 message before dropping the oldest ones.
    // The only reason to buffer is luck of active connection.
    .to(producerBuilder.createSink(serialiser, rabbitChannelConfig, 8192))
    .run()
}
