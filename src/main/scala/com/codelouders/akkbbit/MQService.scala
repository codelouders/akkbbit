package com.codelouders.akkbbit

import akka.util.ByteString

trait MQService {
  def connect(connectionParams: MQConnectionParams): MQConnection
  def isAlive(connection: MQConnection): Boolean
  def send(connection: MQConnection, data: ByteString): Boolean
}

trait MQConnection
trait MQConnectionParams
