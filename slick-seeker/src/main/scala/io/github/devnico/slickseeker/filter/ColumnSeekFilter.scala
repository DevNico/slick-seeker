package io.github.devnico.slickseeker.filter

import slick.ast.BaseTypedType
import slick.lifted.{ColumnOrdered, Rep}

object ColumnSeekFilterTypes {
  type FilterCond = Rep[Option[Boolean]]
}

trait ColumnSeekFilter[T] {
  import ColumnSeekFilterTypes._
  def apply(column: ColumnOrdered[T], value: T, prevCond: FilterCond): FilterCond

  /** Like [[apply]], with the cursor value bound as a query parameter; `valueIsNull` tells whether it is `None`. */
  def applyParam(column: ColumnOrdered[T], value: Rep[T], valueIsNull: Boolean, prevCond: FilterCond): FilterCond =
    throw new UnsupportedOperationException(s"${getClass.getName} does not support compiled pages (no applyParam)")
}

// Note: ColumnSeekFilter implicits are provided by SlickSeekerSupport trait
// where the profile API is available. This allows the library to work
// with any JDBC profile without hardcoding profile imports.
object ColumnSeekFilter
