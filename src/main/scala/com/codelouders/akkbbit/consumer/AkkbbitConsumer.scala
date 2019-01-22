package com.codelouders.akkbbit.consumer
import com.codelouders.akkbbit.common.RabbitChannelConfig

trait AkkbbitConsumerSource {

  def createSource(rabbitChannel: RabbitChannelConfig, autoAck: Boolean)
}
