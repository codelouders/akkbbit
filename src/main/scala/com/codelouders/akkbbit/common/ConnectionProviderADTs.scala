package com.codelouders.akkbbit.common
import com.rabbitmq.client.Connection

private[akkbbit] trait RabbitConnection
private[akkbbit] object RabbitConnection {
  private[akkbbit] case class Connected(connection: Connection) extends RabbitConnection
  private[akkbbit] case object NotConnected extends RabbitConnection
}

private[akkbbit] trait ControlMsg
private[akkbbit] object ControlMsg {
  private[akkbbit] case object GetConnection extends ControlMsg
}
