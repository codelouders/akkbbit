# akkbbit
Provides Akka streams sources, flows and sinks to connect to RabbitMQ. 

## Features: 
 - Automatically reconnects.
 - Producer flow provides send status feedback.
 - connection reusage (few akka stages can share connection and just simply use separate channels)
 
## Examples

### Simple queue sink
In this rabbitmq is a simple sink. It has internal buffer of 8192 elements in size. Internally it will retry resend 
until succeed or buffer is overflowed. In later case it will start dropping the oldest messages to make room for new ones.
After new connection is establish all of the message from buffer will be published. In this case there is no "feedback"
about sending state. 

```scala

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

object SinkExampleWithSimpleQueue {  

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
```
### Simple queue flow example
In this case we want to create flow which internally is going to retry 5 times if connection to rabbit is lost.
It will return either PassThroughStatusMessage with a success and original message or error and original message.
PassThroughStatusMessage and the fact of being able to access original message can be really powerful 
especially together with commitable message. It can be used to acknowledge or not message read from consumer.   

```scala
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
    .via(producerBuilder.createFlow(serialiser, rabbitChannelConfig, maxRetries = 5, maxBufferSize = 4096))
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
```

### Reusing flow (channel) by more then one source of data
This example shows how to build long running sink which can be reuse by many sources of messages. 
Underneath the is only one connection with one channel created. 

```scala
package com.codelouders.akkbbit.example

import java.nio.ByteOrder
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, MergeHub, Sink, Source}
import akka.stream.{ActorMaterializer, ThrottleMode}
import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ConnectionParams,
  ConnectionProvider,
  RabbitChannelConfig,
  RabbitQueueConfig
}
import com.codelouders.akkbbit.producer.{AkkbbitProducer, PassThroughStatusMessage}
import com.codelouders.akkbbit.producer.SentStatus.{FailedToSent, MessageSent}
import com.codelouders.akkbbit.rabbit.RabbitService

import scala.concurrent.duration._
object ReusableFlowExampleWithSimpleQueue extends App {

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

  val reusableSink = createReusableSink()

  // Both "producers will publish to rabbit using same channel"
  // Rabbit connection and channel will be reused
  // in both case in case of disconnection to rabbit flow will try to reconnect indefinitely
  // in a meantime returning TooManyAttempts error. It's possible to set bigger buffer size and higher retry number
  createProducerSource()
    .runWith(reusableSink)

  createProducerSource()
    .runWith(reusableSink)

  def createReusableSink(): Sink[Int, NotUsed] =
    MergeHub
      .source(8)
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
      .toMat(Sink.ignore)(Keep.left)
      .run()

  def createProducerSource() =
    Source
      .fromIterator(() ⇒ Iterator from 0)
      .throttle(1, 500 millis, 1, ThrottleMode.Shaping)
}
```

### More examples
There are more complex examples available under test in com.codelouders.akkbbit.example package

## In progress
 - Consumer
