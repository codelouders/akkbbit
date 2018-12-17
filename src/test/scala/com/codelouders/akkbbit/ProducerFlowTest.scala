package com.codelouders.akkbbit

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.{ActorMaterializer, KillSwitches, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import com.codelouders.akkbbit.IncomingMessage.ReconnectionTick
import com.codelouders.akkbbit.SentStatus.MessageSent
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

class ProducerFlowTest extends FlatSpec with Matchers {

  val fakeConnectionParams = new MQConnectionParams {
    override def host: String = ???

    override def port: Int = ???

    override def connectionTimeout: FiniteDuration = ???
  }

  "Producer flow" should "send messages successfully when connected" in {
    implicit val as = ActorSystem("test")
    implicit val am = ActorMaterializer()

    val queueSource =
      Source
        .queue[ReconnectionTick.type](10, OverflowStrategy.fail)
        .mapMaterializedValue(_ ⇒ NotUsed)

    val producer = new Producer(new FakeMQService, fakeConnectionParams, 0, 1 second) {
      override protected def tickingSource: Source[ReconnectionTick.type, NotUsed] = {
        queueSource
      }
    }

    val flow = producer.createFlow((a: String) ⇒ ByteString(a))

    val (kill, probe) = Source(List("a", "b", "c"))
      .via(flow)
      .viaMat(KillSwitches.single)(Keep.right)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    val Seq(el1, el2, el3) = probe.request(3).expectNextN(3)

    el1.message shouldBe "a"
    el1.status shouldBe MessageSent
    el2.message shouldBe "b"
    el2.status shouldBe MessageSent
    el3.message shouldBe "c"
    el3.status shouldBe MessageSent

    kill.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "resend messages after reconnecting" in {}

  it should "not try to resend if disconnected and retries set to 0" in {}

  it should "try to resend  and fail after reaching max retries number" in {}

  it should "apply overflow drop new strategy properly" in {}

  it should "apply overflow drop head strategy properly" in {}

  it should "apply overflow drop buffer strategy properly" in {}

  it should "apply overflow fail strategy properly" in {}
}
