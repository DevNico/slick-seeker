package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.{CursorDirection, CursorEnvironment}
import io.github.devnico.slickseeker.support.QueryWithCursor
import slick.ast.{OptionType, ScalaBaseType, TypedType}
import slick.lifted.{ColumnsShapeLevel, Shape}

/** The cursor of a seeker (its columns and their encoding) without its query; for [[CompiledSeekPage]]. */
final private[slickseeker] case class SeekCursorSupport[E, U, CVE, C, CU](
    columns: Vector[SeekColumn[E, U, CVE]],
    qwc: QueryWithCursor[E, U, C, CU, CVE]
) {
  private type AnyShape = Shape[ColumnsShapeLevel, Any, Any, Any]

  /** A page of `normalizedLimit` items from up to `normalizedLimit + 1` rows with their cursor values. */
  def toPage[I](
      results: Seq[(I, CU)],
      isBackward: Boolean,
      hasCurrentCursor: Boolean,
      normalizedLimit: Int
  )(implicit cursorEnvironment: CursorEnvironment[CVE]): PaginatedResultWithoutCount[I] = {
    val hasMoreResults = results.size > normalizedLimit
    val pageItems      = if (hasMoreResults) results.dropRight(1) else results

    val items = if (isBackward) pageItems.reverse else pageItems

    val hasNext = if (isBackward) items.nonEmpty && hasCurrentCursor else hasMoreResults
    val hasPrev = if (isBackward) hasMoreResults else hasCurrentCursor

    def cursor(values: CU, direction: CursorDirection) =
      cursorEnvironment.encode(qwc.encode(values).toVector, direction)

    PaginatedResultWithoutCount(
      items = items.map(_._1),
      nextCursor = if (hasNext && items.nonEmpty) Some(cursor(items.last._2, CursorDirection.Forward)) else None,
      prevCursor = if (hasPrev && items.nonEmpty) Some(cursor(items.head._2, CursorDirection.Backward)) else None
    )
  }

  /** The cursor values as parameters, nested like the cursor columns `(last, (…, (first, seed)))`. Needs the sort keys'
    * `typedType`.
    */
  def paramShape: AnyShape =
    columns.foldLeft(Shape.primitiveShape[Int, ColumnsShapeLevel](ScalaBaseType.intType).asInstanceOf[AnyShape]) {
      (tail, column) =>
        val head = Shape.primitiveShape[Any, ColumnsShapeLevel](column.typedType().asInstanceOf[TypedType[Any]])
        Shape.tuple2Shape(head, tail).asInstanceOf[AnyShape]
    }

  /** The cursor values decoded and nested like [[paramShape]], or None if they don't fit the columns. */
  def decodeParams(values: Seq[CVE]): Option[Any] =
    if (values.size != columns.size) None
    else
      columns.zip(values).foldLeft[Option[Any]](Some(0)) { case (tail, (column, value)) =>
        for {
          t <- tail
          v <- column.codec.decode(value)
        } yield (v, t)
      }

  /** Which cursor values of [[decodeParams]] are `None`, first column first. */
  @scala.annotation.tailrec
  def nullFlags(params: Any, acc: List[Boolean] = Nil): List[Boolean] = params match {
    case (value, tail) => nullFlags(tail, (value == None) :: acc)
    case _             => acc
  }

  /** Cursor columns of an `Option` type: each has forms for `None` cursor values. */
  def nullableColumns: Int = columns.count(_.typedType().isInstanceOf[OptionType])
}
