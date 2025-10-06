package io.github.devnico.slickseeker.pagination

/** Result of a paginated query.
  *
  * @param total
  *   Total number of items in the unpaginated query
  * @param items
  *   Items in this page
  * @param prevCursor
  *   Cursor for the previous page (backward pagination)
  * @param nextCursor
  *   Cursor for the next page (forward pagination)
  * @tparam T
  *   Type of items
  */
final case class PaginatedResult[T](
    total: Int,
    items: Seq[T],
    prevCursor: Option[String],
    nextCursor: Option[String]
) {

  /** Map items to a different type while preserving pagination metadata */
  def mapItems[U](f: T => U): PaginatedResult[U] =
    copy(items = items.map(f))

}
