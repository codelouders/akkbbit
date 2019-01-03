package com.codelouders.akkbbit.producer

import com.codelouders.akkbbit.common.ConnectionUpdate

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
}

private[akkbbit] trait IncomingMessage[+T]
private[akkbbit] object IncomingMessage {
  final case class ConnectionInfo(rabbitConnection: ConnectionUpdate)
      extends IncomingMessage[Nothing]
  final case class MessageToSend[T](msg: T) extends IncomingMessage[T]
  case object RetryTick extends IncomingMessage[Nothing]
}

//private[akkbbit] trait OutboundMessage[+T]
//private[akkbbit] object OutboundMessage {
//  final case class Result[T](resultMsg: PassThroughStatusMessage[T]) extends OutboundMessage[T]
//  case object Reconnect extends OutboundMessage[Nothing]
//}

private[akkbbit] case class RetriableMessage[T](attemptsCounter: Int, message: T)

private[akkbbit] case class SendResult[T](
    output: Seq[PassThroughStatusMessage[T]],
    newBuffer: Seq[RetriableMessage[T]])
