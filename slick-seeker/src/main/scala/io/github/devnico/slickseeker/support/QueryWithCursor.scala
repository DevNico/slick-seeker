package io.github.devnico.slickseeker.support

import io.github.devnico.slickseeker.filter.SeekerSortKey
import slick.ast.{BaseTypedType, TypedType}

// Import only what we need from Slick, avoiding wildcards
import slick.lifted.{ColumnOrdered, FlatShapeLevel, LiteralColumn, Rep, Shape}

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

}

object QueryWithCursor {
  def seed[E, U, CVE](implicit
      shape: Shape[FlatShapeLevel, E, U, E],
      btt: BaseTypedType[Int]
  ): QueryWithCursor[E, U, Rep[Int], Int, CVE] =
    QueryWithCursor(_ => LiteralColumn(0), _ => Nil)
}
