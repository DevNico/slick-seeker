package io.github.devnico.slickseeker.pagination

/** Result of a paginated query without total count.
  *
  * Use this when you don't need the total count, avoiding the extra COUNT(*) query.
  *
  * @param items
  *   Items in this page
  * @param prevCursor
  *   Cursor for the previous page (backward pagination)
  * @param nextCursor
  *   Cursor for the next page (forward pagination)
  * @tparam T
  *   Type of items
  */
final case class PaginatedResultWithoutCount[T](
    items: Seq[T],
    prevCursor: Option[String],
    nextCursor: Option[String]
) {

  /** Map items to a different type while preserving pagination metadata */
  def mapItems[U](f: T => U): PaginatedResultWithoutCount[U] =
    copy(items = items.map(f))

  /** Add a total count to produce a full PaginatedResult */
  def withCount(total: Int): PaginatedResult[T] =
    PaginatedResult(total, items, prevCursor, nextCursor)

}
