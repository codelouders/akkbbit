package com.codelouders.akkbbit.example

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, FlowShape}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, MergeHub, Sink}
import akka.util.ByteString
import com.codelouders.akkbbit.common.{
  ConnectionParams,
  ConnectionProvider,
  RabbitChannelConfig,
  RabbitQueueConfig
}
import com.codelouders.akkbbit.producer.{AkkbbitProducer, PassThroughStatusMessage}
import com.codelouders.akkbbit.rabbit.RabbitService

import scala.concurrent.duration._
import scala.util.Random

object ExampleWithEitherAsTransformationResult {

  implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
  implicit val am: ActorMaterializer = ActorMaterializer()
  val service = new RabbitService()
  val connectionProvider =
    new ConnectionProvider(ConnectionParams("", 123, 500 millis, "", "", ""), service, 1 seconds)

  val rabbitChannelConfig = RabbitChannelConfig(RabbitQueueConfig(""), None, None)

  val producerBuilder = new AkkbbitProducer(service, connectionProvider)
  val serialiser: (String) ⇒ ByteString = ByteString(_)

  val flowWithErrorSupport = Flow.fromGraph(GraphDSL.create() { implicit builder =>
    import GraphDSL.Implicits._

    type In = Either[MyDomainError, String]
    type Out = Either[MyDomainError, PassThroughStatusMessage[String]]

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

    broadcast ~> sendFlow ~> merge
    broadcast ~> errorsFilter ~> merge

    FlowShape(broadcast.in, merge.out)
  })

  val inData = MergeHub
    .source[Int](8)
    .map { in ⇒
      // transformacje
      if (Random.nextBoolean()) {
        Right(s"tansformed_$in")
      }
      else {
        Left(MyDomainError("Failed to transform"))
      }
    }
    .via(flowWithErrorSupport)
    .map { result ⇒
      result.fold(
        error ⇒ {
          // React to processing error
        },
        passthrough ⇒ {
          // React to sent result (it still might be failure - if we failed to send / serialise)
        }
      )
    }
    .runWith(Sink.ignore)

  // use this Sink anywhere in your app to send msg to rabbit.
  inData
}

case class MyDomainError(msg: String)
