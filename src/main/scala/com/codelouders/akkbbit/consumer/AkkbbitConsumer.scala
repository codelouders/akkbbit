package com.codelouders.akkbbit.consumer
import com.codelouders.akkbbit.common.ConnectionParams

trait AkkbbitConsumerSource {

  def createSource(connectionParams: ConnectionParams)
}
