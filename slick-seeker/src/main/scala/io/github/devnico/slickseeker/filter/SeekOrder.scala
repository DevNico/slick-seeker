package io.github.devnico.slickseeker.filter

/** Custom ordering for enum/ADT types.
  *
  * Allows defining explicit ordering for sealed traits/enums instead of relying on database ordering.
  *
  * Example:
  * ```scala
  * enum Priority { case Low, Medium, High }
  * given SeekOrder[Priority] = SeekOrder(IndexedSeq(Priority.Low, Priority.Medium, Priority.High))
  * ```
  *
  * @param values
  *   Ordered sequence of all possible values
  * @tparam T
  *   Type of values to order
  */
final case class SeekOrder[T](values: IndexedSeq[T]) {
  // Create defensive copy by converting to List and back to Vector
  private val _values: IndexedSeq[T] = values.toList.toVector
  private val idx: Map[T, Int]       = _values.iterator.zipWithIndex.toMap

  /** Get the ordered values */
  def orderedValues: IndexedSeq[T] = _values

  /** Get index of value (0 if not found) */
  def index(t: T): Int = idx.getOrElse(t, 0)
}
