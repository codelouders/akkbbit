package com.codelouders.akkbbit.common
import com.rabbitmq.client.Connection

private[akkbbit] trait ConnectionUpdate
private[akkbbit] object ConnectionUpdate {
  private[akkbbit] case class NewConnection(connection: Connection) extends ConnectionUpdate
  private[akkbbit] case class ConnectionResend(connection: Connection) extends ConnectionUpdate
  private[akkbbit] case object NotConnected extends ConnectionUpdate
}

private[akkbbit] trait ControlMsg
private[akkbbit] object ControlMsg {
  private[akkbbit] case object GetConnection extends ControlMsg
}
