package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.{CursorDirection, CursorEnvironment}
import io.github.devnico.slickseeker.support.QueryWithCursor
import slick.ast.{BaseTypedType, Ordering, ScalaBaseType}
import slick.lifted
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext

/** PostgreSQL Tuple-optimized seeker with compile-time safety guarantees.
  *
  * This variant uses PostgreSQL row/tuple comparison syntax for efficient pagination. Enforces constraints at the type
  * level:
  *   - All columns must be non-nullable (no Option[T])
  *   - All columns must have the same sort direction
  *
  * @param baseQuery
  *   The base query to paginate
  * @param columns
  *   The columns used for seeking/sorting
  * @param qwc
  *   Query with cursor helper for projection
  * @param direction
  *   The uniform sort direction (all columns must use this)
  */
final case class SlickPgTupleSeeker[E, U, CVE, C, CU, D <: Ordering.Direction](
    baseQuery: lifted.Query[E, U, Seq],
    columns: Vector[PgTupleSeekColumn[E, U, CVE]],
    qwc: QueryWithCursor[E, U, C, CU, CVE],
    direction: D
)(implicit shape: lifted.Shape[lifted.FlatShapeLevel, E, U, E]) extends Seeker[U, CVE] {

  import SlickSeeker._

  /** Add a non-nullable column to the seek columns.
    *
    * Note: Direction is enforced at seeker creation time (ASC or DESC). All columns must be non-nullable (Option types
    * will fail at compile time).
    */
  def seek[T](col: E => lifted.Rep[T])(implicit
      btt: BaseTypedType[T],
      codec: io.github.devnico.slickseeker.cursor.CursorValueCodec[T, CVE],
      colShape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[T], T, lifted.Rep[T]]
  ): SlickPgTupleSeeker[E, U, CVE, (lifted.Rep[T], C), (T, CU), D] = {
    implicit val repShape = colShape
    implicit val pairShape: lifted.Shape[lifted.FlatShapeLevel, (lifted.Rep[T], C), (T, CU), (lifted.Rep[T], C)] =
      lifted.Shape.tuple2Shape[lifted.FlatShapeLevel, lifted.Rep[T], C, T, CU, lifted.Rep[T], C](repShape, qwc.cShape)

    copy(
      columns = columns :+ PgTupleSeekColumn[E, U, T, CVE](col, codec),
      qwc = QueryWithCursor[E, U, (lifted.Rep[T], C), (T, CU), CVE](
        (e: E) => (col(e), qwc.toCursor(e)),
        { case (h, t) =>
          // Type erasure is safe here - the types are guaranteed by the QueryWithCursor type parameters
          qwc.encode(t.asInstanceOf[CU]) :+ codec.encode(h.asInstanceOf[T])
        }
      )(shape, pairShape)
    )
  }

  def page[Profile <: JdbcProfile](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int = 100
  )(implicit
      profile: Profile,
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): profile.api.DBIOAction[PaginatedResult[U], profile.api.NoStream, profile.api.Effect.Read] = {
    val theApi = profile.api
    import theApi._

    val totalAction: DBIOAction[Int, NoStream, Effect.Read] = {
      val countQuery   = baseQuery.map(_ => 1).length
      val wrappedQuery = Query(countQuery)
      val ext          = streamableQueryActionExtensionMethods(wrappedQuery)
      ext.result.map(_.head)
    }

    for {
      total  <- totalAction
      result <- pageWithoutCount[Profile](limit, cursor, maxLimit)(profile, cursorEnvironment, ec)
    } yield result.withCount(total)
  }

  def pageWithoutCount[Profile <: JdbcProfile](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int = 100
  )(implicit
      profile: Profile,
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): profile.api.DBIOAction[PaginatedResultWithoutCount[U], profile.api.NoStream, profile.api.Effect.Read] = {
    val theApi = profile.api
    import theApi._

    val decodedCursor = cursorEnvironment.decodeWithDirectionOrThrow(cursor)
    val isBackward    = decodedCursor.exists(_._1 == CursorDirection.Backward)
    val rawCursor     = decodedCursor.map(_._2)

    val queryColumns    = columns // Direction is fixed at seeker level
    val normalizedLimit = limit.max(1).min(maxLimit)

    val resultsAction: DBIOAction[Seq[(U, CU)], NoStream, Effect.Read] = {
      val filtered  = baseQuery.filter(buildPgTupleFilter(_, queryColumns, rawCursor, isBackward))
      val sorted    = filtered.sortBy(buildOrdered(_, queryColumns, isBackward))
      val limited   = sorted.take(normalizedLimit + 1)
      val projected = qwc.project(limited)
      val ext       = streamableQueryActionExtensionMethods(projected)
      ext.result
    }

    resultsAction.map { results =>
      val hasMore = results.size > normalizedLimit
      val items   = if (hasMore) results.init else results

      val itemsWithoutCursor = items.map(_._1)
      val itemsWithCursor    = items.map(_._2)

      if (isBackward) {
        // In backward mode: results are DESC ordered, need to reverse
        val reversedItems   = itemsWithoutCursor.reverse
        val reversedCursors = itemsWithCursor.reverse

        val nextCursor = // Actually prev because reversed
          if (rawCursor.isDefined && reversedCursors.nonEmpty)
            Some(cursorEnvironment.encode(encodeCursor(reversedCursors.head), CursorDirection.Backward))
          else None

        val prevCursor = // Actually next because reversed
          if (hasMore && reversedCursors.nonEmpty)
            Some(cursorEnvironment.encode(encodeCursor(reversedCursors.last), CursorDirection.Forward))
          else None

        PaginatedResultWithoutCount(
          items = reversedItems,
          nextCursor = prevCursor,
          prevCursor = nextCursor
        )
      } else {
        val nextCursor =
          if (hasMore && itemsWithCursor.nonEmpty)
            Some(cursorEnvironment.encode(encodeCursor(itemsWithCursor.last), CursorDirection.Forward))
          else None

        val prevCursor =
          if (rawCursor.isDefined && itemsWithCursor.nonEmpty)
            Some(cursorEnvironment.encode(encodeCursor(itemsWithCursor.head), CursorDirection.Backward))
          else None

        PaginatedResultWithoutCount(
          items = itemsWithoutCursor,
          nextCursor = nextCursor,
          prevCursor = prevCursor
        )
      }
    }
  }

  private def encodeCursor(projectedCursor: CU): Seq[CVE] =
    qwc.encode(projectedCursor)

  private def buildPgTupleFilter(
      table: E,
      columns: Seq[PgTupleSeekColumn[E, U, CVE]],
      cursorValues: Option[Seq[CVE]],
      isBackward: Boolean
  ): lifted.Rep[Option[Boolean]] =
    cursorValues match {
      case Some(values) if values.nonEmpty && columns.nonEmpty =>
        val columnReps = columns.map(_.col(table))
        val decodedValuesOpt = values.zip(columns).map { case (cursorValue, column) =>
          column.codec.decode(cursorValue)
        }

        if (decodedValuesOpt.exists(_.isEmpty) || decodedValuesOpt.size != columns.size) {
          lifted.LiteralColumn(Some(true): Option[Boolean])(ScalaBaseType.booleanType.optionType)
        } else {
          val decodedValues = decodedValuesOpt.flatten
          val operator =
            if (direction == Ordering.Asc) {
              if (isBackward) "<" else ">" // ASC: > forward, < backward
            } else {
              if (isBackward) ">" else "<" // DESC: < forward, > backward
            }

          buildPgTupleExpression(columnReps.map(_.asInstanceOf[lifted.Rep[Any]]), decodedValues, operator)
        }
      case _ => lifted.LiteralColumn(Some(true): Option[Boolean])(ScalaBaseType.booleanType.optionType)
    }

  private def buildPgTupleExpression(
      columns: Seq[lifted.Rep[Any]],
      values: Seq[Any],
      operator: String
  ): lifted.Rep[Option[Boolean]] = {
    import slick.ast._

    implicit val booleanType: TypedType[Boolean] = ScalaBaseType.booleanType

    // Build tuple node from columns
    val tupleNode = ProductNode(slick.util.ConstArray.from(columns.map(_.toNode)))

    // Build values node from literal values
    val valuesNode = ProductNode(slick.util.ConstArray.from(values.map { v =>
      LiteralNode(v.toString)(ScalaBaseType.stringType)
    }))

    // Create SQL comparison using SimpleBinaryOperator
    val operatorSymbol = operator match {
      case ">"  => Library.>
      case "<"  => Library.<
      case ">=" => Library.>=
      case "<=" => Library.<=
      case "="  => Library.==
      case _    => throw new IllegalArgumentException(s"Unsupported operator: $operator")
    }

    val comparison = Apply(
      operatorSymbol,
      slick.util.ConstArray(tupleNode, valuesNode)
    )(OptionType(booleanType))

    lifted.Rep.forNode[Option[Boolean]](comparison)
  }

  private def buildOrdered(
      table: E,
      columns: Seq[PgTupleSeekColumn[E, U, CVE]],
      isBackward: Boolean
  ): slick.lifted.Ordered = {
    val ordering =
      if (direction == Ordering.Asc) {
        slick.ast.Ordering(if (isBackward) Ordering.Desc else Ordering.Asc)
      } else {
        slick.ast.Ordering(if (isBackward) Ordering.Asc else Ordering.Desc)
      }
    new slick.lifted.Ordered(
      columns.toIndexedSeq.map(c => new lifted.ColumnOrdered(c.col(table), ordering)).flatMap(_.columns)
    )
  }
}

/** Seek column for PostgreSQL tuple mode - stores Rep[T] directly.
  *
  * Direction is applied at the seeker level, not per-column.
  */
private[slickseeker] trait PgTupleSeekColumn[E, U, CVE] {
  type Column
  def col: E => lifted.Rep[Column]
  def codec: io.github.devnico.slickseeker.cursor.CursorValueCodec[Column, CVE]
}

private[slickseeker] object PgTupleSeekColumn {
  def apply[E, U, T, CVE](
      colFn: E => lifted.Rep[T],
      codecFn: io.github.devnico.slickseeker.cursor.CursorValueCodec[T, CVE]
  ): PgTupleSeekColumn[E, U, CVE] = new PgTupleSeekColumn[E, U, CVE] {
    type Column = T
    val col   = colFn
    val codec = codecFn
  }
}
