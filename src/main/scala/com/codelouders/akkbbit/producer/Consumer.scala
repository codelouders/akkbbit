package com.codelouders.akkbbit.producer

import com.codelouders.akkbbit.common.MQConnectionParams

trait ConsumerSource {

  def createSource(connectionParams: MQConnectionParams)
}
