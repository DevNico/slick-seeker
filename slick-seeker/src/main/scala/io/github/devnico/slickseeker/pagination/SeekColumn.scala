package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.CursorValueCodec
import io.github.devnico.slickseeker.filter.{
  ColumnSeekFilter,
  ColumnSeekFilterTypes,
  NullableSeekFilter,
  RowComparableSeekFilter
}
import slick.ast.{Node, OptionType, Ordering, TypedType}
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
        // Reversing the direction mirrors explicit nulls, keeping it keeps them; without a nulls clause, pages resolve
        // the database's default for the new direction (see withDefaultNulls)
        val finalNulls =
          if (current.ord.nulls == Ordering.NullsDefault || current.ord.direction == newDir) current.ord.nulls
          else if (current.ord.nulls == Ordering.NullsFirst) Ordering.NullsLast
          else Ordering.NullsFirst
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
          case (_, Ordering.NullsDefault) if !self.nullable => newCol
          case (Ordering.Asc, Ordering.NullsFirst)          => newCol.nullsLast
          case (Ordering.Asc, Ordering.NullsLast)           => newCol.nullsFirst
          case (Ordering.Asc, Ordering.NullsDefault)        => newCol.nullsFirst
          case (Ordering.Desc, Ordering.NullsFirst)         => newCol.nullsLast
          case (Ordering.Desc, Ordering.NullsLast)          => newCol.nullsFirst
          case (Ordering.Desc, Ordering.NullsDefault)       => newCol.nullsLast
        }
      }
      val filter    = self.filter
      val codec     = self.codec
      val typedType = self.typedType
    }
  }

  /** The filter of this column with the cursor value bound as `settings` say. `table` is an element of the seeker's
    * query `base`.
    */
  def appendToFilter(
      table: E,
      base: Node,
      cursor: CVE,
      prevCond: FilterCond,
      settings: SeekSettings
  ): Option[FilterCond] =
    codec
      .decode(cursor)
      .map { value =>
        val columnOrdered = this.col(table)
        val tpe           = typedType()
        filter(
          columnOrdered,
          comparable(table, base, settings.bind(value, tpe), tpe, settings),
          value == None,
          prevCond
        )
      }

  /** `value`, a cursor value of `tpe` bound for this column of `table`, as it compares with the column's values (see
    * [[SeekSettings.compareAs]]).
    */
  def comparable(table: E, base: Node, value: Rep[?], tpe: TypedType[?], settings: SeekSettings): Rep[Column] =
    settings.compareAs(
      col(table).column.toNode,
      base,
      value.asInstanceOf[Rep[Column]],
      tpe.asInstanceOf[TypedType[Column]]
    )

  /** This column with a missing nulls ordering resolved to the database's default where its filter needs one: the
    * built-in filter of `Option` columns (see [[NullableSeekFilter]]) and custom filters of `Option` columns, which
    * can't tell where the database puts nulls otherwise. Applied before [[reversed]], which mirrors it.
    */
  def withDefaultNulls(settings: SeekSettings): SeekColumn[E, U, CVE] = filter match {
    case _: RowComparableSeekFilter[?] => this
    case other =>
      val self          = this
      val builtInOption = other.isInstanceOf[NullableSeekFilter[?]]
      new SeekColumn[E, U, CVE] {
        type Column = self.Column
        val col: E => ColumnOrdered[Column] = { table =>
          val ordered = self.col(table)
          if (ordered.ord.nulls != Ordering.NullsDefault || !(builtInOption || self.isOption)) ordered
          else ordered.copy(ord = ordered.ord.copy(nulls = settings.defaultNulls(ordered.ord.direction)))
        }
        val filter    = self.filter
        val codec     = self.codec
        val typedType = self.typedType
      }
  }

  /** Whether this column can be null: an `Option` column, unless its filter is the built-in non-null one. */
  def nullable: Boolean = !nonNull && isOption

  /** Whether `ordered` sorts an `Option` column or expression (after its sort key's type). */
  def isOption: Boolean =
    typedType().isInstanceOf[OptionType]

  /** Whether this column can be part of a row value comparison (see [[RowComparableSeekFilter]]). */
  def rowComparable: Boolean = filter.isInstanceOf[RowComparableSeekFilter[?]]

  /** A column with the built-in non-null filter: a nulls clause in its ordering means nothing, but costs index use on
    * databases that emulate it (MySQL, SQL Server, SQLite: `isnull(c), c`), so it gets none.
    */
  def nonNull: Boolean = rowComparable

  /** The decoded cursor value as a parameter of a row value comparison, if this column can be part of one. */
  def rowValue(table: E, base: Node, cursor: CVE, settings: SeekSettings): Option[Node] = filter match {
    case _: RowComparableSeekFilter[?] =>
      val tpe = typedType()
      codec.decode(cursor).map(v => comparable(table, base, settings.bind(v, tpe), tpe, settings).toNode)
    case _ => None
  }

  /** Like [[appendToFilter]], with the cursor value bound as a query parameter of this column's type. */
  def appendToFilterParam(
      table: E,
      base: Node,
      value: Rep[?],
      valueIsNull: Boolean,
      prevCond: FilterCond,
      settings: SeekSettings
  ): FilterCond =
    filter(this.col(table), comparable(table, base, value, typedType(), settings), valueIsNull, prevCond)
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
