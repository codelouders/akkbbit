package com.codelouders.akkbbit

import scala.collection.immutable.Seq

sealed trait SentStatus

object SentStatus {
  case object MessageSent extends SentStatus
  final case class FailedToSent(cause: SentError) extends SentStatus
}

sealed trait SentError

object SentError {
  final case class TooManyAttempts(numberOfAttempts: Int, threshold: Int) extends SentError
}

case class PassThroughStatusMessage[T](status: SentStatus, message: T)

private[akkbbit] trait IncomingMessage[+T]
private[akkbbit] object IncomingMessage {
  final case class MessageToSend[T](msg: T) extends IncomingMessage[T]
  case object ReconnectionTick extends IncomingMessage[Nothing]
}

private[akkbbit] case class RetriableMessage[T](attemptsCounter: Int, message: T)

private[akkbbit] case class SendResult[T](
    output: Seq[PassThroughStatusMessage[T]],
    newBuffer: Seq[RetriableMessage[T]])
