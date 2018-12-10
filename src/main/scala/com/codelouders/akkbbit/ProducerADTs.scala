package com.codelouders.akkbbit

trait SentStatus

object SentStatus{
  case object MessageSent extends SentStatus
  final case class FailedToSent(cause: Exception) extends SentStatus
}


case class PassThroughStatusMessage[T](status: SentStatus, message: T)
