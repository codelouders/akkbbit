package com.codelouders.akkbbit

import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.codelouders.akkbbit.SentStatus.MessageSent

trait ProducerFlow {

}


object ProducerFlow extends ProducerFlow {
  //TODO: create ampq conf classes and pass them here
  def createPassThroughFlow[T](): Flow[T, PassThroughStatusMessage[T], NotUsed] =
    Flow[T]
      //TODO: SEND!
      .map(in ⇒ PassThroughStatusMessage(MessageSent, in))
}

