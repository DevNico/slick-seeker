package io.github.devnico.slickseeker.cursor

/** Codec for individual cursor values.
  *
  * @tparam T
  *   Value type (e.g., Int, String, Option[String])
  * @tparam E
  *   Encoded type (e.g., JsValue)
  */
trait CursorValueCodec[T, E] {
  def encode(value: T): E
  def decode(value: E): Option[T]
}

/** Codec for cursor value sequences.
  *
  * @tparam E
  *   Encoded value type (e.g., JsValue)
  */
trait CursorCodec[E] {
  def encode(values: Seq[E]): String
  def decode(cursor: String): Either[String, Seq[E]]
}
