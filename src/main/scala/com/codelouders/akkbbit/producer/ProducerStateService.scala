package com.codelouders.akkbbit.producer
import akka.stream.OverflowStrategy
import akka.util.ByteString
import com.codelouders.akkbbit.common.{ActiveConnection, BufferOverflowExecutor}
import com.codelouders.akkbbit.error.SerialisationException
import com.codelouders.akkbbit.producer.SentError.{SerialisationError, TooManyAttempts}
import com.codelouders.akkbbit.producer.SentStatus.{FailedToSent, MessageSent}
import com.codelouders.akkbbit.rabbit.RabbitService
import com.typesafe.scalalogging.StrictLogging

import scala.collection.immutable.Seq

private[akkbbit] class ProducerStateService[T](
    rabbitService: RabbitService,
    serializer: T ⇒ ByteString,
    maxRetries: Int,
    maxBufferSize: Int,
    overflowStrategy: OverflowStrategy)
    extends StrictLogging {

  type Buffer = Seq[RetriableMessage[T]]

  def detectTooManyAttempts(buffer: Buffer): ProducerResult[T] = {

    val (tooManyAttempts, updatedBuffer) =
      spitByAttempts(buffer, maxRetries)

    logger.debug(s"Messages saved for retry: $updatedBuffer")
    logger.debug(s"Failed to sent(too many attempts): $tooManyAttempts")
    ProducerResult(tooManyAttempts.map(wrapIntoTooManyAttemptsMessage(maxRetries)), updatedBuffer)
  }

  def addNewMessageToBuffer(buffer: Buffer, newMessage: T): Buffer = {
    if (buffer.size + 1 > maxBufferSize)
      BufferOverflowExecutor.executeStrategy(buffer, newMessage, overflowStrategy)
    else
      buffer :+ RetriableMessage(attemptsCounter = 0, message = newMessage)
  }

  def send(buffer: Buffer, connection: ActiveConnection): ProducerResult[T] = {

    logger.debug(s"Messages to send: $buffer")

    case class Acc(
        output: Seq[PassThroughStatusMessage[T]] = Seq.empty,
        failedToSent: Seq[RetriableMessage[T]] = Seq.empty)

    val resultOfSending =
      buffer.foldLeft(Acc()) { (acc, el) ⇒
        try {
          if (rabbitService.send(connection, serializer(el.message)))
            acc.copy(output = acc.output :+ wrapSuccess(el))
          else
            acc.copy(failedToSent = acc.failedToSent :+ el)
        } catch {
          case e: Exception ⇒
            // exception can only be thrown by serialisation at this stage
            // we do not  want to retry on serialisation exception ever.
            // we cannot expect serialisation method to succeed in future
            // serialisation  should be a pure function
            acc.copy(output = acc.output :+ wrapSerialisationFailure(el, e))
        }
      }

    val (tooManyAttempts, updatedBuffer) =
      spitByAttempts(incNumberOfAttempts(resultOfSending.failedToSent), maxRetries)

    val failures = tooManyAttempts.map(wrapIntoTooManyAttemptsMessage(maxRetries))

    logger.debug(s"Messages saved for another retry: $updatedBuffer")

    ProducerResult(resultOfSending.output ++ failures, updatedBuffer)
  }

  def incNumberOfAttempts(buffer: Buffer): Buffer = {
    logger.debug(s"buffer before update attempts: $buffer")
    buffer
      .map(el ⇒ el.copy(attemptsCounter = el.attemptsCounter + 1))
  }

  private def spitByAttempts(
      buffer: Buffer,
      maxRetries: Int): (Seq[RetriableMessage[T]], Seq[RetriableMessage[T]]) = {
    buffer
      .partition(_.attemptsCounter > maxRetries)
  }

  private def wrapSerialisationFailure(retriableMessage: RetriableMessage[T], cause: Exception) =
    PassThroughStatusMessage(
      FailedToSent(SerialisationError(SerialisationException(cause))),
      retriableMessage.message)

  private def wrapSuccess(retriableMessage: RetriableMessage[T]) =
    PassThroughStatusMessage(MessageSent, retriableMessage.message)

  private def wrapIntoTooManyAttemptsMessage(maxRetries: Int)(
      retriableMessage: RetriableMessage[T]) =
    PassThroughStatusMessage(
      FailedToSent(TooManyAttempts(retriableMessage.attemptsCounter, maxRetries + 1)),
      retriableMessage.message)
}
