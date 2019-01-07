package com.codelouders.akkbbit.producer

import com.codelouders.akkbbit.common.ConnectionUpdate
import com.codelouders.akkbbit.error.SerialisationException

import scala.collection.immutable.Seq

case class PassThroughStatusMessage[+T](status: SentStatus, message: T)

sealed trait SentStatus
object SentStatus {
  case object MessageSent extends SentStatus
  final case class FailedToSent(cause: SentError) extends SentStatus
}

sealed trait SentError
object SentError {
  final case class TooManyAttempts(numberOfAttempts: Int, threshold: Int) extends SentError
  final case class SerialisationError(cause: SerialisationException) extends SentError
}

private[akkbbit] trait IncomingMessage[+T]
private[akkbbit] object IncomingMessage {
  final case class ConnectionInfo(rabbitConnection: ConnectionUpdate)
      extends IncomingMessage[Nothing]
  final case class MessageToSend[T](msg: T) extends IncomingMessage[T]
  case object RetryTick extends IncomingMessage[Nothing]
}

private[akkbbit] case class RetriableMessage[T](attemptsCounter: Int, message: T)

private[akkbbit] case class ProducerResult[T](
    output: Seq[PassThroughStatusMessage[T]],
    updatedBuffer: Seq[RetriableMessage[T]])
