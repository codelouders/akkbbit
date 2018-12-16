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

private case class RetriableMessage[T](attemptsCounter: Int, message: T)

private case class SendResult[T](
    output: Seq[PassThroughStatusMessage[T]],
    newBuffer: Seq[RetriableMessage[T]])
