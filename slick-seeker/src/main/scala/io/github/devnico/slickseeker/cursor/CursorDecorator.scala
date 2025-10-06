package io.github.devnico.slickseeker.cursor

import java.nio.charset.StandardCharsets
import scala.util.Try

/** Decorator for final cursor encoding.
  *
  * Can be used for Base64 encoding, encryption, compression, etc. Decorators can be chained by wrapping:
  * Base64Decorator(GzipDecorator())
  */
trait CursorDecorator {
  def encode(value: String): String
  def decode(cursor: String): Either[Throwable, String]
}

/** Identity decorator - pass-through, useful for testing */
class IdentityDecorator extends CursorDecorator {
  override def encode(value: String): String                     = value
  override def decode(cursor: String): Either[Throwable, String] = Right(cursor)
}

object IdentityDecorator {
  def apply(): IdentityDecorator = new IdentityDecorator()
}

/** Base64 cursor decorator - encodes cursors as Base64 strings
  *
  * Example chaining:
  * {{{
  * val decorator = Base64Decorator(GzipDecorator())
  * }}}
  */
class Base64Decorator(inner: CursorDecorator = IdentityDecorator()) extends CursorDecorator {

  override def encode(value: String): String = {
    val innerEncoded = inner.encode(value)
    java.util.Base64.getEncoder.encodeToString(innerEncoded.getBytes(StandardCharsets.UTF_8))
  }

  override def decode(cursor: String): Either[Throwable, String] =
    Try(new String(java.util.Base64.getDecoder.decode(cursor), StandardCharsets.UTF_8)).toEither
      .flatMap(inner.decode(_))

}

object Base64Decorator {
  def apply(): Base64Decorator                       = new Base64Decorator()
  def apply(inner: CursorDecorator): Base64Decorator = new Base64Decorator(inner)
}
