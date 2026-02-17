package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.{CursorDirection, CursorEnvironment}
import io.github.devnico.slickseeker.filter.{SeekerSortKey, ColumnSeekFilterTypes}
import io.github.devnico.slickseeker.support.{QueryWithCursor, SortDirection}
import slick.ast.{BaseTypedType, ScalaBaseType}
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
        sk.codec
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
    // Explicitly import the api to make implicits available
    val theApi = profile.api
    import theApi._

    val decodedCursor = cursorEnvironment.decodeWithDirectionOrThrow(cursor)
    val isBackward    = decodedCursor.exists(_._1 == CursorDirection.Backward)
    val rawCursor     = decodedCursor.map(_._2)

    val queryColumns = if (isBackward) columns.map(_.reversed) else columns

    val normalizedLimit = limit.max(1).min(maxLimit)

    val resultsAction: DBIOAction[Seq[(U, CU)], NoStream, Effect.Read] = {
      val filtered  = baseQuery.filter(buildFilter(_, queryColumns, rawCursor))
      val sorted    = filtered.sortBy(buildOrdered(_, queryColumns))
      val limited   = sorted.take(normalizedLimit + 1)
      val projected = qwc.project(limited)
      val ext       = streamableQueryActionExtensionMethods(projected)
      ext.result
    }

    resultsAction.map { results =>
      val hasMoreResults = results.size > normalizedLimit
      val pageItems      = if (hasMoreResults) results.dropRight(1) else results

      val items = if (isBackward) pageItems.reverse else pageItems

      val hasCurrentCursor = rawCursor.isDefined && rawCursor.get.nonEmpty

      val hasNext = if (isBackward) {
        items.nonEmpty && hasCurrentCursor
      } else {
        hasMoreResults
      }

      val hasPrev = if (isBackward) {
        hasMoreResults
      } else {
        hasCurrentCursor
      }

      val nextCursor = if (hasNext && items.nonEmpty) {
        val item         = items.last
        val cursorValues = encodeCursor(item._2)
        Some(cursorEnvironment.encode(cursorValues, CursorDirection.Forward))
      } else None

      val prevCursor = if (hasPrev && items.nonEmpty) {
        val item         = items.head
        val cursorValues = encodeCursor(item._2)
        Some(cursorEnvironment.encode(cursorValues, CursorDirection.Backward))
      } else None

      PaginatedResultWithoutCount(
        items = items.map(_._1),
        nextCursor = nextCursor,
        prevCursor = prevCursor
      )
    }
  }

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
