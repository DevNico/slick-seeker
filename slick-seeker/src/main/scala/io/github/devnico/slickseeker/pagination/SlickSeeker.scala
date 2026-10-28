package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.{CursorDirection, CursorEnvironment}
import io.github.devnico.slickseeker.filter.{ColumnSeekFilterTypes, SeekerSortKey}
import io.github.devnico.slickseeker.support.{QueryWithCursor, SortDirection}
import slick.ast.{Apply, BaseTypedType, Library, Node, Ordering, ProductNode, ScalaBaseType}
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
    val settings = SeekSettings.of(profile)
    pageRows[U](limit, cursor, maxLimit, settings)(limited =>
      streamableQueryActionExtensionMethods(qwc.project(limited, settings)).result
    )
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
    val settings = SeekSettings.of(profile)
    pageRows[PU](limit, cursor, maxLimit, settings)(limited =>
      streamableQueryActionExtensionMethods(qwc.projectAs(limited, settings)(select)).result
    )
  }

  private def pageRows[I](
      limit: Int,
      cursor: Option[String],
      maxLimit: Int,
      settings: SeekSettings
  )(
      run: lifted.Query[E, U, Seq] => dbio.DBIOAction[Seq[(I, CU)], dbio.NoStream, dbio.Effect.Read]
  )(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResultWithoutCount[I], dbio.NoStream, dbio.Effect.Read] = {
    val decodedCursor   = cursorEnvironment.decodeWithDirectionOrThrow(cursor)
    val normalizedLimit = SlickSeeker.normalizeLimit(limit, maxLimit)

    run(pageQuery(decodedCursor, normalizedLimit, settings)).map(results =>
      cursorSupport.toPage(
        results,
        decodedCursor.exists(_._1 == CursorDirection.Backward),
        decodedCursor.exists(_._2.nonEmpty),
        normalizedLimit
      )
    )
  }

  /** The query of a page: up to `normalizedLimit + 1` elements after the decoded cursor. */
  private[slickseeker] def pageQuery(
      decodedCursor: Option[(CursorDirection, Seq[CVE])],
      normalizedLimit: Int,
      settings: SeekSettings
  ): lifted.Query[E, U, Seq] = {
    val queryColumns = columnsFor(decodedCursor.exists(_._1 == CursorDirection.Backward), settings)
    val filtered     = baseQuery.filter(buildFilter(_, queryColumns, decodedCursor.map(_._2), settings))
    filtered.sortBy(buildOrdered(_, queryColumns)).take(normalizedLimit + 1)
  }

  /** The seek columns of a page in its direction, with default nulls resolved for `settings`' database. */
  private def columnsFor(isBackward: Boolean, settings: SeekSettings): Vector[SeekColumn[E, U, CVE]] = {
    val resolved = columns.map(_.withDefaultNulls(settings))
    if (isBackward) resolved.map(_.reversed) else resolved
  }

  /** The cursor of this seeker without its query, for [[CompiledSeekPage]]. */
  private[slickseeker] def cursorSupport: SeekCursorSupport[E, U, CVE, C, CU] = SeekCursorSupport(columns, qwc)

  /** The page query with the cursor values (`cursor`: the parameters of [[SeekCursorSupport.paramShape]]) and the limit
    * bound as parameters, for [[CompiledSeekPage]]. Its form depends only on `isBackward` and `nullFlags` (which cursor
    * values are `None`), so one compiled query per combination serves every cursor.
    */
  private[slickseeker] def paramPageQuery[P, PU](
      cursor: Option[Any],
      nullFlags: List[Boolean],
      isBackward: Boolean,
      limit: lifted.ConstColumn[Long],
      settings: SeekSettings
  )(select: E => P)(implicit
      pShape: lifted.Shape[lifted.FlatShapeLevel, P, PU, P]
  ): lifted.Query[(P, C), (PU, CU), Seq] = {
    val queryColumns = columnsFor(isBackward, settings)
    val filtered = cursor match {
      case None => baseQuery
      case Some(params) =>
        val reps = flattenParams(params)
        require(
          reps.size == queryColumns.size && nullFlags.size == queryColumns.size,
          s"${reps.size} cursor parameters and ${nullFlags.size} null flags for ${queryColumns.size} seek columns"
        )
        val base = baseQuery.toNode
        baseQuery.filter { table =>
          val values = reps.zip(queryColumns).map { case (rep, column) =>
            column.comparable(table, base, rep, column.typedType(), settings).toNode
          }
          rowFilter(table, queryColumns, values, settings).getOrElse {
            reps
              .zip(nullFlags)
              .zip(queryColumns)
              .reverse
              .foldLeft[FilterCond](alwaysFalse) { case (prevCond, ((rep, isNull), column)) =>
                column.appendToFilterParam(table, base, rep, isNull, prevCond, settings)
              }
          }
        }
    }
    qwc.projectAs(filtered.sortBy(buildOrdered(_, queryColumns)).take(limit), settings)(select)
  }

  private def buildFilter(
      table: E,
      columns: Seq[SeekColumn[E, U, CVE]],
      cursorValues: Option[Seq[CVE]],
      settings: SeekSettings
  ): FilterCond =
    cursorValues
      .filter(values => settings.rowComparisons && values.size == columns.size)
      .flatMap(values =>
        sequence(columns.zip(values).map { case (column, value) =>
          column.rowValue(table, baseQuery.toNode, value, settings)
        })
      )
      .flatMap(rowFilter(table, columns, _, settings))
      .getOrElse(buildChainFilter(table, columns, cursorValues, settings))

  /** `(a, b) > (x, y)` (or `<` for descending columns) if row value comparisons are on and the columns qualify: at
    * least two, all [[SeekColumn.rowComparable]] and sorting in the same direction. Equal to the chained conditions
    * then.
    */
  private def rowFilter(
      table: E,
      columns: Seq[SeekColumn[E, U, CVE]],
      values: Seq[Node],
      settings: SeekSettings
  ): Option[FilterCond] =
    if (!settings.rowComparisons || columns.size < 2 || !columns.forall(_.rowComparable)) None
    else {
      val ordered = columns.map(_.col(table))
      ordered.map(_.ord.direction).distinct match {
        case Seq(direction) => Some(rowComparison(ordered.map(_.column.toNode), values, direction))
        case _              => None
      }
    }

  private def buildChainFilter(
      table: E,
      columns: Seq[SeekColumn[E, U, CVE]],
      cursorValues: Option[Seq[CVE]],
      settings: SeekSettings
  ): FilterCond = {
    val zipped = cursorValues.getOrElse(Seq.empty).zip(columns).reverse
    if (zipped.isEmpty) {
      alwaysTrue
    } else {
      val filter = zipped.foldLeft[Option[FilterCond]](Some(alwaysFalse)) { case (prevCond, (cursorValue, column)) =>
        prevCond.flatMap(column.appendToFilter(table, baseQuery.toNode, cursorValue, _, settings))
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
  @scala.annotation.tailrec
  private def flattenParams(params: Any, acc: List[lifted.Rep[?]] = Nil): List[lifted.Rep[?]] = params match {
    case (head: lifted.Rep[?], tail) => flattenParams(tail, head :: acc)
    case _                           => acc
  }

  /** `limit` within 1 and `maxLimit`, and below Int.MaxValue so that the fetched `limit + 1` fits an Int. */
  private[slickseeker] def normalizeLimit(limit: Int, maxLimit: Int): Int =
    limit.max(1).min(maxLimit).min(Int.MaxValue - 1)

  private def sequence[T](options: Seq[Option[T]]): Option[Seq[T]] =
    if (options.forall(_.isDefined)) Some(options.flatten) else None

  private def rowComparison(columns: Seq[Node], values: Seq[Node], direction: Ordering.Direction): FilterCond = {
    implicit val condType: slick.ast.TypedType[Option[Boolean]] = ScalaBaseType.booleanType.optionType
    val operator                                                = if (direction == Ordering.Asc) Library.> else Library.<
    val row                                                     = (nodes: Seq[Node]) => ProductNode(slick.util.ConstArray.from(nodes))
    lifted.Rep.forNode[Option[Boolean]](Apply(operator, slick.util.ConstArray(row(columns), row(values)))(condType))
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
