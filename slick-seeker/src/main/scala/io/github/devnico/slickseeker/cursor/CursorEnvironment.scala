package io.github.devnico.slickseeker.cursor

/** Environment for cursor encoding/decoding.
  *
  * Combines a codec for values and a decorator for the final encoding (e.g., Base64).
  *
  * @tparam E
  *   Cursor value encoding type (e.g., JsValue for JSON)
  */
final case class CursorEnvironment[E](codec: CursorCodec[E], decorator: CursorDecorator) {
  import CursorEnvironment.DecodeError
  import CursorDirection._

  /** Encode cursor values with forward direction */
  def encode(values: Seq[E]): String = encode(values, Forward)

  /** Encode cursor values with specified direction */
  def encode(values: Seq[E], direction: CursorDirection): String = {
    val rawCursor = codec.encode(values)
    val prefixed = direction match {
      case Forward  => s">$rawCursor"
      case Backward => s"<$rawCursor"
    }
    decorator.encode(prefixed)
  }

  /** Decode cursor without direction information */
  def decode(cursor: Option[String]): Either[DecodeError, Option[Seq[E]]] =
    decodeWithDirection(cursor).map(_.map(_._2))

  /** Decode cursor with direction information */
  def decodeWithDirection(cursor: Option[String]): Either[DecodeError, Option[(CursorDirection, Seq[E])]] =
    cursor match {
      case None                         => Right(None)
      case Some(value) if value.isBlank => Right(None)
      case Some(value) =>
        for {
          decoded <- decorator.decode(value).left.map(err => DecodeError("Failed to decode cursor envelope", Some(err)))
          (direction, rawCursor) = decoded.headOption match {
                                     case Some('>') => (Forward, decoded.tail)
                                     case Some('<') => (Backward, decoded.tail)
                                     case _         => (Forward, decoded) // backward compatibility
                                   }
          values <- codec
                      .decode(rawCursor)
                      .left
                      .map(DecodeError(_))
        } yield Some((direction, values))
    }

  /** Decode cursor or throw exception */
  def decodeOrThrow(cursor: Option[String]): Option[Seq[E]] =
    decode(cursor) match {
      case Left(error)   => throw new IllegalArgumentException(error.message, error.cause.orNull)
      case Right(result) => result
    }

  /** Decode cursor with direction or throw exception */
  def decodeWithDirectionOrThrow(cursor: Option[String]): Option[(CursorDirection, Seq[E])] =
    decodeWithDirection(cursor) match {
      case Left(error)   => throw new IllegalArgumentException(error.message, error.cause.orNull)
      case Right(result) => result
    }

}

object CursorEnvironment {
  final case class DecodeError(message: String, cause: Option[Throwable] = None)
}
