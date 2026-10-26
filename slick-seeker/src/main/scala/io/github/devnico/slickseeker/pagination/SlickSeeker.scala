package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.{CursorDirection, CursorEnvironment}
import io.github.devnico.slickseeker.filter.{SeekerSortKey, ColumnSeekFilterTypes}
import io.github.devnico.slickseeker.support.{QueryWithCursor, SortDirection}
import slick.ast.{BaseTypedType, ScalaBaseType}
import slick.dbio
import slick.jdbc.JdbcProfile
import slick.lifted

import scala.concurrent.ExecutionContext

/** SlickSeeker provides cursor-based pagination for Slick queries.
  *
  * @param baseQuery
  *   The base query to paginate
  * @param columns
  *   The columns used for seeking/sorting
  * @param qwc
  *   Query with cursor helper for projection
  */
final case class SlickSeeker[E, U, CVE, C, CU](
    baseQuery: lifted.Query[E, U, Seq],
    columns: Vector[SeekColumn[E, U, CVE]],
    qwc: QueryWithCursor[E, U, C, CU, CVE]
)(implicit shape: lifted.Shape[lifted.FlatShapeLevel, E, U, E]) extends Seeker[U, CVE] {
  import ColumnSeekFilterTypes._
  import SlickSeeker._

  def seek[T, K](col: E => lifted.ColumnOrdered[T])(implicit
      sk: SeekerSortKey[T, K, CVE]
  ): SlickSeeker[E, U, CVE, (lifted.Rep[K], C), (K, CU)] =
    copy(
      columns = columns :+ SeekColumn[E, U, K, CVE](
        t => sk.mapCol(col(t)),
        sk.filter,
        sk.codec,
        () => sk.typedType
      ),
      qwc = qwc.withSeekColumn(col)
    )

  def seekDirection(direction: SortDirection): SlickSeeker[E, U, CVE, C, CU] = copy(
    columns = columns.map(_.withDirection(direction))
  )

  def page[Profile <: JdbcProfile](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int = 100
  )(implicit
      profile: Profile,
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): profile.api.DBIOAction[PaginatedResult[U], profile.api.NoStream, profile.api.Effect.Read] = {
    // Explicitly import the api to make implicits available
    val theApi = profile.api
    import theApi._

    // Scala 2 workaround: map the length to a single-value query
    val totalAction: DBIOAction[Int, NoStream, Effect.Read] = {
      val countQuery = baseQuery.map(_ => 1).length // This returns Rep[Int]
      // Wrap it in a Query by using a single-value query
      val wrappedQuery = Query(countQuery)
      val ext          = streamableQueryActionExtensionMethods(wrappedQuery)
      ext.result.map(_.head)
    }

    val resultsAction: DBIOAction[PaginatedResultWithoutCount[U], NoStream, Effect.Read] =
      pageWithoutCount[Profile](limit, cursor, maxLimit)(profile, cursorEnvironment, ec)

    for {
      total  <- totalAction
      result <- resultsAction
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
    pageRows[U](limit, cursor, maxLimit)(limited => streamableQueryActionExtensionMethods(qwc.project(limited)).result)
  }

  /** Like [[page]], but the items are `select(e)` instead of whole elements (see [[pageWithoutCountAs]]). */
  def pageAs[P, PU, Profile <: JdbcProfile](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int = 100
  )(select: E => P)(implicit
      pShape: lifted.Shape[lifted.FlatShapeLevel, P, PU, P],
      profile: Profile,
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): profile.api.DBIOAction[PaginatedResult[PU], profile.api.NoStream, profile.api.Effect.Read] = {
    val theApi = profile.api
    import theApi._
    for {
      total <- streamableQueryActionExtensionMethods(Query(baseQuery.map(_ => 1).length)).result.map(_.head)
      result <-
        pageWithoutCountAs[P, PU, Profile](limit, cursor, maxLimit)(select)(pShape, profile, cursorEnvironment, ec)
    } yield result.withCount(total)
  }

  /** Like [[pageWithoutCount]], but the items are `select(e)` instead of whole elements. Filtering, sorting and the
    * cursor still use the whole element, so a query can join tables for searching and sorting while a page selects only
    * what it needs, e.g. ids whose rows are loaded separately.
    */
  def pageWithoutCountAs[P, PU, Profile <: JdbcProfile](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int = 100
  )(select: E => P)(implicit
      pShape: lifted.Shape[lifted.FlatShapeLevel, P, PU, P],
      profile: Profile,
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): profile.api.DBIOAction[PaginatedResultWithoutCount[PU], profile.api.NoStream, profile.api.Effect.Read] = {
    val theApi = profile.api
    import theApi._
    pageRows[PU](limit, cursor, maxLimit)(limited =>
      streamableQueryActionExtensionMethods(qwc.projectAs(limited)(select)).result
    )
  }

  private def pageRows[I](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int
  )(
      run: lifted.Query[E, U, Seq] => dbio.DBIOAction[Seq[(I, CU)], dbio.NoStream, dbio.Effect.Read]
  )(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResultWithoutCount[I], dbio.NoStream, dbio.Effect.Read] = {
    val decodedCursor = cursorEnvironment.decodeWithDirectionOrThrow(cursor)
    val isBackward    = decodedCursor.exists(_._1 == CursorDirection.Backward)
    val rawCursor     = decodedCursor.map(_._2)

    val queryColumns = if (isBackward) columns.map(_.reversed) else columns

    val normalizedLimit = limit.max(1).min(maxLimit)

    val filtered = baseQuery.filter(buildFilter(_, queryColumns, rawCursor))
    val sorted   = filtered.sortBy(buildOrdered(_, queryColumns))

    run(sorted.take(normalizedLimit + 1)).map(results =>
      toPage(results, isBackward, rawCursor.exists(_.nonEmpty), normalizedLimit)
    )
  }

  /** A page of `normalizedLimit` items from up to `normalizedLimit + 1` rows with their cursor values. */
  private[slickseeker] def toPage[I](
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

    val nextCursor =
      if (hasNext && items.nonEmpty)
        Some(cursorEnvironment.encode(encodeCursor(items.last._2), CursorDirection.Forward))
      else None

    val prevCursor =
      if (hasPrev && items.nonEmpty)
        Some(cursorEnvironment.encode(encodeCursor(items.head._2), CursorDirection.Backward))
      else None

    PaginatedResultWithoutCount(items = items.map(_._1), nextCursor = nextCursor, prevCursor = prevCursor)
  }

  /** The page query with the cursor values (`cursor`: the parameters of [[QueryWithCursor.paramShape]]) and the limit
    * bound as parameters, for [[CompiledSeekPage]]. Its form depends only on `isBackward` and `nullFlags` (which cursor
    * values are `None`), so one compiled query per combination serves every cursor.
    */
  private[slickseeker] def paramPageQuery[P, PU](
      cursor: Option[Any],
      nullFlags: List[Boolean],
      isBackward: Boolean,
      limit: lifted.ConstColumn[Long]
  )(select: E => P)(implicit
      pShape: lifted.Shape[lifted.FlatShapeLevel, P, PU, P]
  ): lifted.Query[(P, C), (PU, CU), Seq] = {
    val queryColumns = if (isBackward) columns.map(_.reversed) else columns
    val filtered = cursor match {
      case None => baseQuery
      case Some(params) =>
        val reps = flattenParams(params)
        baseQuery.filter { table =>
          reps
            .zip(nullFlags)
            .zip(queryColumns)
            .reverse
            .foldLeft[FilterCond](alwaysFalse) { case (prevCond, ((rep, isNull), column)) =>
              column.appendToFilterParam(table, rep, isNull, prevCond)
            }
        }
    }
    qwc.projectAs(filtered.sortBy(buildOrdered(_, queryColumns)).take(limit))(select)
  }

  /** The cursor values as parameters, nested like the cursor columns `(last, (…, (first, seed)))`; for
    * [[CompiledSeekPage]]. Needs the sort keys' `typedType`.
    */
  private[slickseeker] def cursorParamShape: lifted.Shape[lifted.ColumnsShapeLevel, Any, Any, Any] =
    columns.foldLeft[lifted.Shape[lifted.ColumnsShapeLevel, Any, Any, Any]](
      lifted.Shape.primitiveShape[Int, lifted.ColumnsShapeLevel](ScalaBaseType.intType).asInstanceOf[
        lifted.Shape[lifted.ColumnsShapeLevel, Any, Any, Any]
      ]
    ) { (tail, column) =>
      lifted.Shape
        .tuple2Shape(
          lifted.Shape.primitiveShape[Any, lifted.ColumnsShapeLevel](
            column.typedType().asInstanceOf[slick.ast.TypedType[Any]]
          ),
          tail
        )
        .asInstanceOf[lifted.Shape[lifted.ColumnsShapeLevel, Any, Any, Any]]
    }

  /** The cursor values decoded and nested like [[cursorParamShape]], or None if they don't fit the columns. */
  private[slickseeker] def decodeCursorParams(values: Seq[CVE]): Option[Any] =
    if (values.size != columns.size) None
    else
      columns.zip(values).foldLeft[Option[Any]](Some(0)) { case (tail, (column, value)) =>
        for {
          t <- tail
          v <- column.codec.decode(value)
        } yield (v, t)
      }

  /** Which cursor values of [[decodeCursorParams]] are `None`, first column first. */
  private[slickseeker] def cursorNullFlags(params: Any): List[Boolean] = params match {
    case (value, tail) => cursorNullFlags(tail) :+ (value == None)
    case _             => Nil
  }

  /** Cursor columns of an `Option` type: each has a form for `None` cursor values in [[CompiledSeekPage]]. */
  private[slickseeker] def nullableCursorColumns: Int =
    columns.count(_.typedType().isInstanceOf[slick.ast.OptionType])

  private def encodeCursor(projectedCursor: CU): Vector[CVE] =
    qwc.encode(projectedCursor).toVector

  private def buildFilter(
      table: E,
      columns: Seq[SeekColumn[E, U, CVE]],
      cursorValues: Option[Seq[CVE]]
  ): FilterCond = {
    val zipped = cursorValues.getOrElse(Seq.empty).zip(columns).reverse
    if (zipped.isEmpty) {
      alwaysTrue
    } else {
      val filter = zipped.foldLeft[Option[FilterCond]](Some(alwaysFalse)) { case (prevCond, (cursorValue, column)) =>
        prevCond.flatMap(column.appendToFilter(table, cursorValue, _))
      }
      filter.getOrElse(alwaysTrue)
    }
  }

  private def buildOrdered(table: E, columns: Seq[SeekColumn[E, U, CVE]]): slick.lifted.Ordered =
    new slick.lifted.Ordered(columns.toIndexedSeq.map(_.col(table)).flatMap(_.columns))

}

object SlickSeeker {
  import ColumnSeekFilterTypes._

  // Cursor parameters nest as (last column, (…, (first column, seed))); the seed is not a cursor column.
  private def flattenParams(params: Any): List[lifted.Rep[?]] = params match {
    case (head: lifted.Rep[?], tail) => flattenParams(tail) :+ head
    case _                           => Nil
  }

  private def alwaysTrue: FilterCond = {
    implicit val typedType: slick.ast.TypedType[Boolean] = ScalaBaseType.booleanType
    lifted.LiteralColumn(Some(true))
  }

  private def alwaysFalse: FilterCond = {
    implicit val typedType: slick.ast.TypedType[Boolean] = ScalaBaseType.booleanType
    lifted.LiteralColumn(Some(false))
  }

  def apply[E, U, CVE](q: lifted.Query[E, U, Seq])(implicit
      shape: lifted.Shape[lifted.FlatShapeLevel, E, U, E],
      baseTypedType: BaseTypedType[Int]
  ): SlickSeeker[E, U, CVE, lifted.Rep[Int], Int] =
    SlickSeeker(q, Vector.empty, QueryWithCursor.seed[E, U, CVE])

}
