package io.github.devnico.slickseeker.cursor

/** Direction of cursor-based pagination */
sealed trait CursorDirection extends Product with Serializable

object CursorDirection {
  case object Forward  extends CursorDirection
  case object Backward extends CursorDirection
}
