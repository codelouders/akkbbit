package com.codelouders.akkbbit.error

class AkkbbitException(msg: String, cause: Option[Exception]) extends Exception(msg, cause.orNull) {
  def this(msg: String) = this(msg, None)
}
