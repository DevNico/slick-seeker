package io.github.devnico.slickseeker.filter

import slick.ast.TypedType
import slick.lifted.{ColumnOrdered, Rep}

object ColumnSeekFilterTypes {
  type FilterCond = Rep[Option[Boolean]]
}

trait ColumnSeekFilter[T] {
  import ColumnSeekFilterTypes._
  def apply(column: ColumnOrdered[T], value: T, prevCond: FilterCond): FilterCond

  /** Like [[apply]], with the cursor value bound as a query parameter; `valueIsNull` tells whether it is `None`.
    * Optional: without it, compiled pages ([[io.github.devnico.slickseeker.pagination.CompiledSeekPage]]) run their
    * cursor pages as ad-hoc pages.
    */
  def applyParam(column: ColumnOrdered[T], value: Rep[T], valueIsNull: Boolean, prevCond: FilterCond): FilterCond =
    throw new NoCompiledPages(s"${getClass.getName} does not support compiled pages (no applyParam)")
}

/** Thrown by the default `applyParam` and `typedType`: compiled pages then run their cursor pages as ad-hoc pages. */
final private[slickseeker] class NoCompiledPages(message: String) extends UnsupportedOperationException(message)

/** A built-in filter: seekers bind its cursor values as parameters of `valueType` and call [[applyParam]]. */
private[slickseeker] trait BuiltInSeekFilter[T] extends ColumnSeekFilter[T] {
  def valueType: TypedType[T]
}

/** The built-in filter on a non-null column: its conditions over several columns of one direction equal a row value
  * comparison `(a, b) > (x, y)`, which seekers use instead where the profile supports it.
  */
private[slickseeker] trait RowComparableSeekFilter[T] extends BuiltInSeekFilter[T]

/** The built-in filter on an `Option` column: it needs an explicit nulls ordering, so seekers resolve a missing one to
  * where the database puts nulls by default (`SlickSeekerSupport.seekerDefaultNulls`).
  */
private[slickseeker] trait NullableSeekFilter[T] extends BuiltInSeekFilter[T]

// Note: ColumnSeekFilter implicits are provided by SlickSeekerSupport trait
// where the profile API is available. This allows the library to work
// with any JDBC profile without hardcoding profile imports.
object ColumnSeekFilter
