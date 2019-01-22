package com.codelouders.akkbbit.example

import java.nio.ByteOrder
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, FlowShape}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, MergeHub, Sink, Source}
import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ConnectionParams,
  ConnectionProvider,
  RabbitChannelConfig,
  RabbitQueueConfig
}
import com.codelouders.akkbbit.example.Error.MyDomainError
import com.codelouders.akkbbit.producer.{AkkbbitProducer, PassThroughStatusMessage}
import com.codelouders.akkbbit.rabbit.RabbitService

import scala.concurrent.duration._
import scala.util.Random

// format: off
/*

Use case:
We receive data from producers. We need to transform them and based on result of that transformation we either send it to rabbit or now.
We also need to know if data was successfully pushed to rabbitmq.

PassThroughStatusMessage - result of sending message via Rabbit
PassThroughError         - Error of transformation


                                                                          Success  |¯¯¯¯¯¯¯¯¯¯|   |¯¯¯¯¯¯¯¯¯¯|
                                                                           ┌------→| Collect  |--→| Rabbit   |
                                                                           |       | Successes|   | Producer |
|¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯|                          |¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯¯|--┘       |__________|   | Flow     |-┐ PassThroughStatusMessage
|                     |       |¯¯¯¯¯¯¯¯¯¯¯¯¯¯|   |                      |                         |__________| |
| Producers(n number) | * → n | BroadcastHUB | → | TRANSFORMATION       |                                      |
|                     |       |______________|   | EITHER Error/Success |                                      |
|_____________________|                          |______________________|--┐                                   |
                                                                           |               PassThroughError    ↓ IN
                                                                           |       |¯¯¯¯¯¯¯¯¯|           IN |¯¯¯¯¯¯¯¯¯¯|
                                                                           └-----→ | Collect |-------------→| MergeHUB |
                                                                          Failure  | failures|              |__________|
                                                                                   |_________|                 | OUT
                                                                                                               |
                                                                               |¯¯¯¯¯¯¯|   |¯¯¯¯¯¯¯¯¯¯¯¯¯|     |
                                                                               | Sink  |←--| Result      |←----┘
                                                                               | Ignore|   | Processing  | Either PassThroughError/PassThroughStatusMessage
                                                                               |_______|   |_____________|


 */
// format: on

object ExampleWithTransformation extends App {

  implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
  implicit val am: ActorMaterializer = ActorMaterializer()

  type In = Either[PassThroughError[Int], Int]
  type Out = Either[PassThroughError[Int], PassThroughStatusMessage[Int]]

  val service = new RabbitService()
  val connectionProvider =
    new ConnectionProvider(
      ConnectionParams("host", 1234, 500 millis, "vHost", "user", "pass"),
      service,
      1 seconds)

  val rabbitChannelConfig = RabbitChannelConfig(RabbitQueueConfig("queueName"), None, None)
  val producerBuilder = new AkkbbitProducer(service, connectionProvider)

  // use this Sink anywhere in your app to send msg to rabbit.
  Source
    .single(123)
    .runWith(alwaysRunningSinkViaRabbitFlow)

  // its ok to reuse alwaysRunningSinkViaRabbitFlow - sink. It still running and it is reusing same connection and channel
  Source
    .single(321)
    .runWith(alwaysRunningSinkViaRabbitFlow)

  // that sink due to Mergehub nature should be always running and can be reused by many "producers"
  lazy val alwaysRunningSinkViaRabbitFlow: Sink[Int, NotUsed] =
    MergeHub
      .source[Int](8)
      // TRANSFORMATION BEFORE SEND
      .map { in ⇒
        // random success/failure of "transformation"
        if (Random.nextBoolean()) {
          Right(in)
        }
        else {
          Left(PassThroughError(MyDomainError("Failed to transform"), in))
        }
      }
      // flow which will only try to send in case of transformation success
      .via(flowWithErrorSupport)
      // result processing.
      .map { result ⇒
        result.fold(
          error ⇒ {
            // React to processing error
            error.originalMsg // you can access original msg
          },
          passThrough ⇒ {
            // React to sent result (it still might be failure - if we failed to send / serialise)
            passThrough.message // you can access original msg
          }
        )
      }
      // discarding message
      .toMat(Sink.ignore)(Keep.left)
      .run()

  def flowWithErrorSupport: Flow[In, Out, NotUsed] = {
    implicit val order = ByteOrder.BIG_ENDIAN
    val serialiser: Int ⇒ ByteString = ByteString.createBuilder.putInt(_).result()

    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val broadcast = builder.add(Broadcast[In](2))
      val merge = builder.add(Merge[Out](2))

      val errorsFilter = Flow[In]
        .collect[Out] {
          case Left(error) ⇒ Left(error)
        }

      val sendFlow: Flow[In, Out, NotUsed] =
        Flow[In]
          .collect {
            case Right(data) ⇒ data
          }
          .via(producerBuilder.createFlow(serialiser, rabbitChannelConfig))
          .map(Right(_))

      // format: off
      broadcast ~> sendFlow     ~> merge
      broadcast ~> errorsFilter ~> merge
      // format: on

      FlowShape(broadcast.in, merge.out)
    })

  }
}

case class PassThroughError[T](error: Error, originalMsg: T)
trait Error
object Error {
  case class MyDomainError(msg: String) extends Error
}
