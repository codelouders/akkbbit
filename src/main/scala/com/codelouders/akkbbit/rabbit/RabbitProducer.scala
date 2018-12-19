package com.codelouders.akkbbit.rabbit

import akka.stream.ActorMaterializer
import com.codelouders.akkbbit.producer.Producer

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

class RabbitProducer(
    rabbitMQService: RabbitMQService,
    rabbitConnectionParams: RabbitConnectionParams,
    channel: RabbitQueue,
    maxRetries: Int = Int.MaxValue,
    reconnectInterval: FiniteDuration = 1 second,
    maxBufferSize: Int = 2048)(implicit am: ActorMaterializer)
    extends Producer[RabbitConnectionParams, RabbitMQConnection](
      rabbitMQService,
      rabbitConnectionParams,
      maxRetries,
      reconnectInterval,
      maxBufferSize)
