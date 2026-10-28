package io.github.devnico.slickseeker.support

import io.github.devnico.slickseeker.filter.SeekerSortKey
import io.github.devnico.slickseeker.pagination.SeekSettings
import slick.ast.{BaseTypedType, Bind, Pure, TypedType}

// Import only what we need from Slick, avoiding wildcards
import slick.lifted.{ColumnOrdered, FlatShapeLevel, LiteralColumn, Query, Rep, Shape, WrappingQuery}

final case class QueryWithCursor[E, U, C, CU, CVE](
    toCursor: E => C,
    encode: CU => List[CVE]
)(implicit
    val queryShape: Shape[FlatShapeLevel, E, U, E],
    val cShape: Shape[FlatShapeLevel, C, CU, C]
) {

  def withSeekColumn[T, K](col: E => ColumnOrdered[T])(implicit
      sk: SeekerSortKey[T, K, CVE]
  ): QueryWithCursor[E, U, (Rep[K], C), (K, CU), CVE] = {
    implicit val shape: Shape[FlatShapeLevel, Rep[K], K, Rep[K]] = sk.shape
    QueryWithCursor(
      e => (sk.mapCol(col(e)).column, toCursor(e)),
      { case (h, t) => encode(t) :+ sk.codec.encode(h) }
    )
  }

  def project(q: slick.lifted.Query[E, U, Seq]): slick.lifted.Query[(E, C), (U, CU), Seq] = {
    implicit val pair: Shape[FlatShapeLevel, (E, C), (U, CU), (E, C)] = Shape.tuple2Shape(queryShape, cShape)
    q.map(e => (e, toCursor(e)))
  }

  /** Like [[project]], but selects `select(e)` instead of the whole element, e.g. only the ids of a joined query. */
  def projectAs[P, PU](q: slick.lifted.Query[E, U, Seq])(select: E => P)(implicit
      pShape: Shape[FlatShapeLevel, P, PU, P]
  ): slick.lifted.Query[(P, C), (PU, CU), Seq] = {
    implicit val pair: Shape[FlatShapeLevel, (P, C), (PU, CU), (P, C)] = Shape.tuple2Shape(pShape, cShape)
    q.map(e => (select(e), toCursor(e)))
  }

  /** [[project]] with the cursor columns as `settings` select them (see [[SeekSettings.cursorColumn]]), and on SQL
    * Server without repeated columns (see [[SeekSettings.cursorColumnsAsExpressions]]).
    */
  private[slickseeker] def project(q: Query[E, U, Seq], settings: SeekSettings): Query[(E, C), (U, CU), Seq] =
    if (!settings.rewritesSelection) project(q)
    else QueryWithCursor.distinct(selectingCursor(q, settings).project(q), settings)

  /** [[projectAs]] with the cursor columns as `settings` select them. */
  private[slickseeker] def projectAs[P, PU](q: Query[E, U, Seq], settings: SeekSettings)(select: E => P)(implicit
      pShape: Shape[FlatShapeLevel, P, PU, P]
  ): Query[(P, C), (PU, CU), Seq] =
    if (!settings.rewritesSelection) projectAs(q)(select)
    else QueryWithCursor.distinct(selectingCursor(q, settings).projectAs(q)(select), settings)

  // This with the cursor columns of elements of `q` as `settings` select them.
  private def selectingCursor(q: Query[E, U, Seq], settings: SeekSettings): QueryWithCursor[E, U, C, CU, CVE] = {
    val scope = q.toNode
    copy[E, U, C, CU, CVE](toCursor = e => QueryWithCursor.mapColumns(toCursor(e))(settings.cursorColumn(_, scope)))
  }
}

object QueryWithCursor {

  // The cursor columns `(last, (…, (first, seed)))` mapped with `f`.
  private def mapColumns[C](cursor: C)(f: Rep[?] => Rep[?]): C = (cursor match {
    case (head: Rep[?], tail) => (f(head), mapColumns(tail)(f))
    case seed                 => seed
  }).asInstanceOf[C]

  private def distinct[T, TU](mapped: Query[T, TU, Seq], settings: SeekSettings): Query[T, TU, Seq] =
    if (settings.cursorColumnsAsExpressions) withoutRepeatedColumns(mapped) else mapped

  // `mapped` (a query's map) with columns it selects more than once as expressions (SeekSettings.withoutRepeatedColumns).
  private def withoutRepeatedColumns[T, TU](mapped: Query[T, TU, Seq]): Query[T, TU, Seq] = mapped.toNode match {
    case Bind(generator, from, Pure(value, identity)) =>
      val distinct = SeekSettings.withoutRepeatedColumns(value, from)
      if (distinct eq value) mapped
      else new WrappingQuery[T, TU, Seq](Bind(generator, from, Pure(distinct, identity)), mapped.shaped)
    case _ => mapped
  }

  def seed[E, U, CVE](implicit
      shape: Shape[FlatShapeLevel, E, U, E],
      btt: BaseTypedType[Int]
  ): QueryWithCursor[E, U, Rep[Int], Int, CVE] =
    QueryWithCursor(_ => LiteralColumn(0), _ => Nil)
}
