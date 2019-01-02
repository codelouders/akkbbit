package com.codelouders.akkbbit

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.{BroadcastHub, Keep, MergeHub, Sink, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import com.codelouders.akkbbit.common.{RabbitChannelConfig, RabbitQueueConfig}
import com.codelouders.akkbbit.producer.IncomingMessage.ReconnectionTick
import com.codelouders.akkbbit.producer.{AkkbbitProducer, IncomingMessage}
import com.codelouders.akkbbit.producer.SentError.TooManyAttempts
import com.codelouders.akkbbit.producer.SentStatus.{FailedToSent, MessageSent}
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

class ProducerFlowTest extends FlatSpec with Matchers {

  val connectionParams = RabbitChannelConfig(RabbitQueueConfig(""), None, None)

  "Producer flow" should "send messages successfully when connected" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow(
      serializer = (a: String) ⇒ ByteString(a),
      channelConfig = connectionParams
    )

    val (inQueue, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .via(flow)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)

    probe.request(1).expectNoMessage(100 millis)

    inQueue.offer("a")
    inQueue.offer("b")
    inQueue.offer("c")

    val Seq(el1, el2, el3) = probe.request(3).expectNextN(3)

    el1.message shouldBe "a"
    el1.status shouldBe MessageSent
    el2.message shouldBe "b"
    el2.status shouldBe MessageSent
    el3.message shouldBe "c"
    el3.status shouldBe MessageSent

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "resend messages after reconnecting" in {

    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow((a: String) ⇒ ByteString(a), connectionParams, 1)

    val (inQueue, probe) =
      Source
        .queue[String](10, OverflowStrategy.fail)
        .viaMat(flow)(Keep.left)
        .via(killSwitch.flow)
        .toMat(TestSink.probe)(Keep.both)
        .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

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

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "not try to resend if disconnected and retries set to 0" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow((a: String) ⇒ ByteString(a), connectionParams)

    val (incoming, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

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

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "try to resend  and fail after reaching max retries number" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow((a: String) ⇒ ByteString(a), connectionParams, 2)

    val (incoming, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

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

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "apply overflow drop new strategy properly" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow(
      serializer = (a: String) ⇒ ByteString(a),
      channelConfig = connectionParams,
      maxRetries = 10,
      maxBufferSize = 5,
      reconnectInterval = 1 second,
      overflowStrategy = OverflowStrategy.dropNew
    )

    val (incoming, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

    service.disconnect()

    incoming.offer("a") // 1
    incoming.offer("b") // 2
    incoming.offer("c") // 3
    incoming.offer("d") // 4
    incoming.offer("e") // 5
    incoming.offer("f") // 6 should be dropped

    probe.request(3).expectNoMessage(50 millis)

    Source.single(ReconnectionTick).runWith(inTicks)

    val Seq(el1, el2, el3, el4, el5) = probe.request(6).expectNextN(5)

    el1.message shouldBe "a"
    el1.status shouldBe MessageSent
    el2.message shouldBe "b"
    el2.status shouldBe MessageSent
    el3.message shouldBe "c"
    el3.status shouldBe MessageSent
    el4.message shouldBe "d"
    el4.status shouldBe MessageSent
    el5.message shouldBe "e"
    el5.status shouldBe MessageSent

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "apply overflow drop head strategy properly" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow(
      serializer = (a: String) ⇒ ByteString(a),
      channelConfig = connectionParams,
      maxRetries = 10,
      maxBufferSize = 5,
      reconnectInterval = 1 second,
      overflowStrategy = OverflowStrategy.dropHead
    )

    val (incoming, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

    service.disconnect()

    incoming.offer("a") // 1 // should be dropped and f should get in
    incoming.offer("b") // 2 // should be dropped and g should get in
    incoming.offer("c") // 3
    incoming.offer("d") // 4
    incoming.offer("e") // 5
    incoming.offer("f") // 6
    incoming.offer("g") // 7

    probe.request(3).expectNoMessage(50 millis)

    Source.single(ReconnectionTick).runWith(inTicks)

    val Seq(el1, el2, el3, el4, el5) = probe.request(7).expectNextN(5)

    el1.message shouldBe "c"
    el1.status shouldBe MessageSent
    el2.message shouldBe "d"
    el2.status shouldBe MessageSent
    el3.message shouldBe "e"
    el3.status shouldBe MessageSent
    el4.message shouldBe "f"
    el4.status shouldBe MessageSent
    el5.message shouldBe "g"
    el5.status shouldBe MessageSent

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "apply overflow drop tail strategy properly" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow(
      serializer = (a: String) ⇒ ByteString(a),
      channelConfig = connectionParams,
      maxRetries = 10,
      maxBufferSize = 5,
      reconnectInterval = 1 second,
      overflowStrategy = OverflowStrategy.dropTail
    )

    val (incoming, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

    service.disconnect()

    incoming.offer("a") // 1
    incoming.offer("b") // 2
    incoming.offer("c") // 3
    incoming.offer("d") // 4
    incoming.offer("e") // 5 // should be dropped and f should get in
    incoming.offer("f") // 6 // should be dropped and g should get in
    incoming.offer("g") // 7

    probe.request(3).expectNoMessage(50 millis)

    Source.single(ReconnectionTick).runWith(inTicks)

    val Seq(el1, el2, el3, el4, el5) = probe.request(7).expectNextN(5)

    el1.message shouldBe "a"
    el1.status shouldBe MessageSent
    el2.message shouldBe "b"
    el2.status shouldBe MessageSent
    el3.message shouldBe "c"
    el3.status shouldBe MessageSent
    el4.message shouldBe "d"
    el4.status shouldBe MessageSent
    el5.message shouldBe "g"
    el5.status shouldBe MessageSent

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "apply overflow drop buffer strategy properly" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow(
      serializer = (a: String) ⇒ ByteString(a),
      channelConfig = connectionParams,
      maxRetries = 10,
      maxBufferSize = 5,
      reconnectInterval = 1 second,
      overflowStrategy = OverflowStrategy.dropBuffer
    )

    val (incoming, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

    service.disconnect()

    incoming.offer("a") // 1
    incoming.offer("b") // 2
    incoming.offer("c") // 3
    incoming.offer("d") // 4
    incoming.offer("e") // 5
    incoming.offer("f") // 6 // it should drop all above messages
    incoming.offer("g") // 7

    probe.request(3).expectNoMessage(50 millis)

    Source.single(ReconnectionTick).runWith(inTicks)

    val Seq(el1, el2) = probe.request(7).expectNextN(2)

    el1.message shouldBe "f"
    el1.status shouldBe MessageSent
    el2.message shouldBe "g"
    el2.status shouldBe MessageSent

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  it should "apply overflow fail strategy properly" in {
    implicit val as: ActorSystem = ActorSystem(s"test-${UUID.randomUUID()}")
    implicit val am: ActorMaterializer = ActorMaterializer()

    val killSwitch: SharedKillSwitch = KillSwitches.shared("test-flow-kill-switch")

    val (inTicks, outTicks) = crateTickStream(killSwitch)

    val service = new StubService

    val producer =
      createProducer(service = service, outTicks = outTicks)

    val flow = producer.createFlow(
      serializer = (a: String) ⇒ ByteString(a),
      channelConfig = connectionParams,
      maxRetries = 10,
      maxBufferSize = 2,
      reconnectInterval = 1 second,
      overflowStrategy = OverflowStrategy.fail
    )

    val (incoming, probe) = Source
      .queue[String](10, OverflowStrategy.fail)
      .viaMat(flow)(Keep.left)
      .via(killSwitch.flow)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    Source.single(ReconnectionTick).runWith(inTicks)
    probe.request(1).expectNoMessage(100 millis)

    service.disconnect()

    incoming.offer("a") // 1
    incoming.offer("b") // 2
    incoming.offer("c") // 3 it should trigger failure

    probe.request(3).expectError() shouldBe a[BufferOverflowException]

    killSwitch.shutdown()
    Await.result(as.terminate(), 3 seconds)
  }

  private def createProducer(
      service: StubService,
      outTicks: Source[IncomingMessage.ReconnectionTick.type, NotUsed])(
      implicit am: ActorMaterializer): AkkbbitProducer = {

    new AkkbbitProducer(service, new StubConnectionProvider(service)) {
      override protected def tickingSource(
          f: FiniteDuration): Source[ReconnectionTick.type, NotUsed] = {
        outTicks
      }
    }
  }

  private def crateTickStream(killSwitch: SharedKillSwitch)(implicit am: ActorMaterializer): (
      Sink[IncomingMessage.ReconnectionTick.type, NotUsed],
      Source[IncomingMessage.ReconnectionTick.type, NotUsed]) = {

    MergeHub
      .source[ReconnectionTick.type](8)
      .via(killSwitch.flow)
      .toMat(BroadcastHub.sink(8))(Keep.both)
      .run()
  }
}
