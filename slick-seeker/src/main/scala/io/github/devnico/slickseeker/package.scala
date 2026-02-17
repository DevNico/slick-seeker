package io.github.devnico

/** Slick Seeker - Type-safe cursor-based pagination for Slick.
  *
  * Main exports for public API. Import these along with your custom profile's API:
  *
  * {{{
  * import MyProfile.api._
  * import io.github.devnico.slickseeker._
  * }}}
  */
package object slickseeker {
  // Type aliases for main pagination types
  type SlickSeeker[E, U, CVE, C, CU] = pagination.SlickSeeker[E, U, CVE, C, CU]
  val SlickSeeker: pagination.SlickSeeker.type = pagination.SlickSeeker

  type PaginatedResult[T] = pagination.PaginatedResult[T]
  val PaginatedResult: pagination.PaginatedResult.type = pagination.PaginatedResult

  type PaginatedResultWithoutCount[T] = pagination.PaginatedResultWithoutCount[T]
  val PaginatedResultWithoutCount: pagination.PaginatedResultWithoutCount.type = pagination.PaginatedResultWithoutCount

  // Type aliases for cursor system
  type CursorEnvironment[E] = cursor.CursorEnvironment[E]
  val CursorEnvironment: cursor.CursorEnvironment.type = cursor.CursorEnvironment

  type CursorCodec[E]         = cursor.CursorCodec[E]
  type CursorValueCodec[T, E] = cursor.CursorValueCodec[T, E]
  type CursorDecorator        = cursor.CursorDecorator
  type CursorDirection        = cursor.CursorDirection
  val CursorDirection: cursor.CursorDirection.type = cursor.CursorDirection

  type Base64Decorator = cursor.Base64Decorator
  val Base64Decorator: cursor.Base64Decorator.type = cursor.Base64Decorator

  type IdentityDecorator = cursor.IdentityDecorator
  val IdentityDecorator: cursor.IdentityDecorator.type = cursor.IdentityDecorator

  // Type aliases for filter/ordering
  type SeekOrder[T] = filter.SeekOrder[T]
  val SeekOrder: filter.SeekOrder.type = filter.SeekOrder

  type SeekerSortKey[T, K, CVE] = filter.SeekerSortKey[T, K, CVE]
  val SeekerSortKey: filter.SeekerSortKey.type = filter.SeekerSortKey

  type ColumnSeekFilter[T] = filter.ColumnSeekFilter[T]
  val ColumnSeekFilter: filter.ColumnSeekFilter.type = filter.ColumnSeekFilter

  // Type aliases for support types
  type SortDirection = support.SortDirection
  val SortDirection: support.SortDirection.type = support.SortDirection

  // Re-export error type
  type DecodeError = CursorEnvironment.DecodeError
  val DecodeError: CursorEnvironment.DecodeError.type = CursorEnvironment.DecodeError
}
