package com.codelouders.akkbbit.common
import akka.stream.{BufferOverflowException, OverflowStrategy}
import com.codelouders.akkbbit.producer.RetriableMessage
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable.Seq

private[akkbbit] object BufferOverflowExecutor extends LazyLogging {

  def executeStrategy[T](
      buffer: Seq[RetriableMessage[T]],
      msg: T,
      overflowStrategy: OverflowStrategy): Seq[RetriableMessage[T]] = {

    logger.warn(s"Applying buffer overflow strategy: $overflowStrategy")

    overflowStrategy match {
      case st: OverflowStrategy if st == OverflowStrategy.dropHead ⇒
        buffer.tail :+ RetriableMessage(attemptsCounter = 0, message = msg)

      case st: OverflowStrategy if st == OverflowStrategy.dropNew ⇒
        buffer

      case st: OverflowStrategy if st == OverflowStrategy.fail ⇒
        throw BufferOverflowException("MQ flow buffer max size reached! Failing flow.")

      case st: OverflowStrategy if st == OverflowStrategy.dropBuffer ⇒
        Seq.empty :+ RetriableMessage(attemptsCounter = 0, message = msg)

      case st: OverflowStrategy if st == OverflowStrategy.dropTail ⇒
        buffer.dropRight(1) :+ RetriableMessage(attemptsCounter = 0, message = msg)
    }
  }
}
