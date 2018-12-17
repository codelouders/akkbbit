package com.codelouders.akkbbit

import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration

trait MQService[T <: MQConnectionParams, R <: MQConnection] {
  def connect(connectionParams: T): R
  def isAlive(connection: R): Boolean
  def send(connection: R, data: ByteString): Boolean
}

trait MQConnection

/**
  * Make sure that connectionTimeout is lower than reconnection interval
  *
  * @param host
  * @param port
  * @param connectionTimeout
  */
trait MQConnectionParams {
  def host: String
  def port: Int
  def connectionTimeout: FiniteDuration
}
