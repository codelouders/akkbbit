package com.codelouders.akkbbit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, KillSwitches, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import com.codelouders.akkbbit.producer.IncomingMessage.ReconnectionTick
import com.codelouders.akkbbit.producer.Producer
import com.codelouders.akkbbit.producer.SentError.TooManyAttempts
import com.codelouders.akkbbit.producer.SentStatus.{FailedToSent, MessageSent}
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

class ProducerFlowTest extends FlatSpec with Matchers {

  val fakeConnectionParams: MQConnectionParams = new MQConnectionParams {
    override def host: String = ???

    override def port: Int = ???

    override def connectionTimeout: FiniteDuration = ???
  }

  "Producer flow" should "send messages successfully when connected" in {
    implicit val as = ActorSystem("test")
    implicit val am = ActorMaterializer()

    val (_, outTicks) =
      MergeHub
        .source[ReconnectionTick.type](8)
        .toMat(BroadcastHub.sink(8))(Keep.both)
        .run()

    val producer = new Producer(new FakeMQService, fakeConnectionParams, 0, 1 second) {
      override protected def tickingSource: Source[ReconnectionTick.type, NotUsed] = {
        outTicks
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

  it should "resend messages after reconnecting" in {

    implicit val as: ActorSystem = ActorSystem("test")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val (inTicks, outTicks) =
      MergeHub
        .source[ReconnectionTick.type](8)
        .toMat(BroadcastHub.sink(8))(Keep.both)
        .run()

    val service = new FakeMQService

    val producer = new Producer(service, fakeConnectionParams, 1, 1 second) {
      override protected def tickingSource: Source[ReconnectionTick.type, NotUsed] = {
        outTicks
      }
    }

    val flow = producer.createFlow((a: String) ⇒ ByteString(a))

    val ((inQueue, kill), probe) =
      Source
        .queue[String](10, OverflowStrategy.fail)
        .viaMat(flow)(Keep.left)
        .viaMat(KillSwitches.single)(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

    inQueue.offer("a")
    var out = probe.requestNext()
    out.message shouldBe "a"
    out.status shouldBe MessageSent

    service.disconnect()
    inQueue.offer("b")
    inQueue.offer("c")
    probe.request(2).expectNoMessage(50 millis)
    Source.single(ReconnectionTick).runWith(inTicks)
    val Seq(el1, el2) = probe.request(2).expectNextN(2)
    el1.message shouldBe "b"
    el1.status shouldBe MessageSent
    el2.message shouldBe "c"
    el2.status shouldBe MessageSent

    inQueue.offer("d")
    out = probe.requestNext()
    out.message shouldBe "d"
    out.status shouldBe MessageSent

    kill.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "not try to resend if disconnected and retries set to 0" in {
    implicit val as = ActorSystem("test")
    implicit val am = ActorMaterializer()

    val (_, outTicks) =
      MergeHub
        .source[ReconnectionTick.type](8)
        .toMat(BroadcastHub.sink(8))(Keep.both)
        .run()

    val service = new FakeMQService

    val producer = new Producer(service, fakeConnectionParams, 0, 1 second) {
      override protected def tickingSource: Source[ReconnectionTick.type, NotUsed] = {
        outTicks
      }
    }

    val flow = producer.createFlow((a: String) ⇒ ByteString(a))

    val ((incoming, kill), probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    service.disconnect()

    incoming.offer("a")
    incoming.offer("b")
    incoming.offer("c")

    val Seq(el1, el2, el3) = probe.request(3).expectNextN(3)

    el1.message shouldBe "a"
    el1.status shouldBe FailedToSent(TooManyAttempts(1, 1))
    el2.message shouldBe "b"
    el2.status shouldBe FailedToSent(TooManyAttempts(1, 1))
    el3.message shouldBe "c"
    el3.status shouldBe FailedToSent(TooManyAttempts(1, 1))

    kill.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "try to resend  and fail after reaching max retries number" in {
    implicit val as = ActorSystem("test")
    implicit val am = ActorMaterializer()

    val (inTicks, outTicks) =
      MergeHub
        .source[ReconnectionTick.type](8)
        .toMat(BroadcastHub.sink(8))(Keep.both)
        .run()

    val service = new FakeMQService

    val producer = new Producer(service, fakeConnectionParams, 2, 1 second) {
      override protected def tickingSource: Source[ReconnectionTick.type, NotUsed] = {
        outTicks
      }
    }

    val flow = producer.createFlow((a: String) ⇒ ByteString(a))

    val ((incoming, kill), probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    service.disconnect()
    service.reconnect = false

    incoming.offer("a")
    incoming.offer("b")
    incoming.offer("c")

    probe.request(3).expectNoMessage(50 millis)

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(3).expectNoMessage(50 millis)
    Source.single(ReconnectionTick).runWith(inTicks)
    val Seq(el1, el2, el3) = probe.request(3).expectNextN(3)

    el1.message shouldBe "a"
    el1.status shouldBe FailedToSent(TooManyAttempts(3, 3))
    el2.message shouldBe "b"
    el2.status shouldBe FailedToSent(TooManyAttempts(3, 3))
    el3.message shouldBe "c"
    el3.status shouldBe FailedToSent(TooManyAttempts(3, 3))

    kill.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "apply overflow drop new strategy properly" in {}

  it should "apply overflow drop head strategy properly" in {}

  it should "apply overflow drop buffer strategy properly" in {}

  it should "apply overflow fail strategy properly" in {}
}
