package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.CursorValueCodec
import io.github.devnico.slickseeker.filter.{ColumnSeekFilter, ColumnSeekFilterTypes}
import slick.ast.Ordering
import slick.ast.TypedType
import slick.lifted.{ColumnOrdered, Rep}

private[slickseeker] trait SeekColumn[E, U, CVE] {
  import ColumnSeekFilterTypes._

  type Column
  def col: E => ColumnOrdered[Column]
  def filter: ColumnSeekFilter[Column]
  def codec: CursorValueCodec[Column, CVE]
  def typedType: () => TypedType[Column]

  def encodeCursor(value: Column): CVE = codec.encode(value)

  def withDirection(direction: io.github.devnico.slickseeker.support.SortDirection): SeekColumn[E, U, CVE] = {
    val self = this
    new SeekColumn[E, U, CVE] {
      type Column = self.Column
      val col: E => ColumnOrdered[Column] = { t =>
        val current = self.col(t)
        val newDir =
          if (direction == io.github.devnico.slickseeker.support.SortDirection.Asc) Ordering.Asc else Ordering.Desc
        val finalNulls =
          if (current.ord.nulls != Ordering.NullsDefault) {
            (current.ord.nulls, direction) match {
              case (Ordering.NullsFirst, io.github.devnico.slickseeker.support.SortDirection.Desc) => Ordering.NullsLast
              case (Ordering.NullsLast, io.github.devnico.slickseeker.support.SortDirection.Desc)  => Ordering.NullsFirst
              case (nulls, io.github.devnico.slickseeker.support.SortDirection.Asc)                => nulls
              case (nulls, _)                                                                      => nulls
            }
          } else {
            if (direction == io.github.devnico.slickseeker.support.SortDirection.Asc) Ordering.NullsLast
            else Ordering.NullsFirst
          }
        current.copy(ord = Ordering(newDir, finalNulls))
      }
      val filter    = self.filter
      val codec     = self.codec
      val typedType = self.typedType
    }
  }

  def reversed: SeekColumn[E, U, CVE] = {
    val self = this
    new SeekColumn[E, U, CVE] {
      type Column = self.Column
      val col: E => ColumnOrdered[Column] = { table =>
        val originalCol = self.col(table)
        val newCol      = originalCol.reverse

        (originalCol.ord.direction, originalCol.ord.nulls) match {
          case (Ordering.Asc, Ordering.NullsFirst)    => newCol.nullsLast
          case (Ordering.Asc, Ordering.NullsLast)     => newCol.nullsFirst
          case (Ordering.Asc, Ordering.NullsDefault)  => newCol.nullsFirst
          case (Ordering.Desc, Ordering.NullsFirst)   => newCol.nullsLast
          case (Ordering.Desc, Ordering.NullsLast)    => newCol.nullsFirst
          case (Ordering.Desc, Ordering.NullsDefault) => newCol.nullsLast
        }
      }
      val filter    = self.filter
      val codec     = self.codec
      val typedType = self.typedType
    }
  }

  def appendToFilter(
      table: E,
      cursor: CVE,
      prevCond: FilterCond
  ): Option[FilterCond] =
    codec
      .decode(cursor)
      .map { value =>
        val columnOrdered = this.col(table)
        filter(columnOrdered, value, prevCond)
      }

  /** Like [[appendToFilter]], with the cursor value bound as a query parameter of this column's type. */
  def appendToFilterParam(table: E, value: Rep[?], valueIsNull: Boolean, prevCond: FilterCond): FilterCond =
    filter.applyParam(this.col(table), value.asInstanceOf[Rep[Column]], valueIsNull, prevCond)
}

private[slickseeker] object SeekColumn {
  def apply[E, U, T, CVE](
      colFn: E => ColumnOrdered[T],
      filterFn: ColumnSeekFilter[T],
      codecFn: CursorValueCodec[T, CVE],
      typedTypeFn: () => TypedType[T]
  ): SeekColumn[E, U, CVE] = new SeekColumn[E, U, CVE] {
    type Column = T
    val col       = colFn
    val filter    = filterFn
    val codec     = codecFn
    val typedType = typedTypeFn
  }
}
