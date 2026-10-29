package io.github.devnico.slickseeker.filter

import slick.lifted.{ColumnOrdered, Rep}

object ColumnSeekFilterTypes {
  type FilterCond = Rep[Option[Boolean]]
}

/** The cursor condition of one seek column: which rows come after the cursor in the column's order. */
trait ColumnSeekFilter[T] {
  import ColumnSeekFilterTypes._

  /** The condition for rows after the cursor: `value` is this column's cursor value as a query parameter (`valueIsNull`
    * tells whether it is `None`), `prevCond` the condition on the following seek columns, for rows equal to `value` in
    * this one. `column.ord` is the order of the page (reversed on backward pages); on `Option` columns its nulls
    * ordering is always explicit.
    */
  def apply(column: ColumnOrdered[T], value: Rep[T], valueIsNull: Boolean, prevCond: FilterCond): FilterCond
}

/** A built-in filter. */
private[slickseeker] trait BuiltInSeekFilter[T] extends ColumnSeekFilter[T]

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
