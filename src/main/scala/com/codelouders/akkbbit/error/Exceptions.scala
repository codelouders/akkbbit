package com.codelouders.akkbbit.error

class AkkbbitException(cause: Exception) extends Exception(cause)

case class SerialisationException(cause: Exception) extends AkkbbitException(cause)
