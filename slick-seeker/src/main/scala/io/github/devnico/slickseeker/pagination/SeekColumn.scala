package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.CursorValueCodec
import io.github.devnico.slickseeker.filter.{
  BuiltInSeekFilter,
  ColumnSeekFilter,
  ColumnSeekFilterTypes,
  NullableSeekFilter,
  RowComparableSeekFilter
}
import slick.ast.{Node, Ordering, TypedType}
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

  /** Built-in filters get the cursor value bound as `settings` say; custom ones get the value. */
  def appendToFilter(
      table: E,
      cursor: CVE,
      prevCond: FilterCond,
      settings: SeekSettings
  ): Option[FilterCond] =
    codec
      .decode(cursor)
      .map { value =>
        val columnOrdered = this.col(table)
        filter match {
          case builtIn: BuiltInSeekFilter[Column @unchecked] =>
            builtIn.applyParam(columnOrdered, settings.bind(value, builtIn.valueType), value == None, prevCond)
          case custom => custom(columnOrdered, value, prevCond)
        }
      }

  /** This column with a missing nulls ordering resolved to the database's default where its filter needs one (see
    * [[NullableSeekFilter]]); applied before [[reversed]], which mirrors it.
    */
  def withDefaultNulls(settings: SeekSettings): SeekColumn[E, U, CVE] = filter match {
    case _: NullableSeekFilter[?] =>
      val self = this
      new SeekColumn[E, U, CVE] {
        type Column = self.Column
        val col: E => ColumnOrdered[Column] = { table =>
          val ordered = self.col(table)
          if (ordered.ord.nulls != Ordering.NullsDefault) ordered
          else ordered.copy(ord = ordered.ord.copy(nulls = settings.defaultNulls(ordered.ord.direction)))
        }
        val filter    = self.filter
        val codec     = self.codec
        val typedType = self.typedType
      }
    case _ => this
  }

  /** Whether this column can be part of a row value comparison (see [[RowComparableSeekFilter]]). */
  def rowComparable: Boolean = filter.isInstanceOf[RowComparableSeekFilter[?]]

  /** The decoded cursor value as a parameter of a row value comparison, if this column can be part of one. */
  def rowValue(cursor: CVE, settings: SeekSettings): Option[Node] = filter match {
    case f: RowComparableSeekFilter[Column @unchecked] =>
      codec.decode(cursor).map(settings.bind(_, f.valueType).toNode)
    case _ => None
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
