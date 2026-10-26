package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.{CursorDirection, CursorEnvironment}
import slick.ast.{ScalaBaseType, TypedType}
import slick.dbio
import slick.jdbc.JdbcProfile
import slick.lifted.{
  Compilable,
  CompiledFunction,
  ColumnsShapeLevel,
  ConstColumn,
  Executable,
  FlatShapeLevel,
  Query,
  Rep,
  Shape
}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

/** Pages of a seeker as compiled queries: application parameters, cursor values and the limit are bound as query
  * parameters, so Slick compiles each form of the page query once instead of on every page.
  *
  * `build` creates the seeker from the application parameters (e.g. a search term or filter values as `Rep`s). Its
  * query must only depend on these parameters: everything else (constants, table and schema names, Scala-side branches)
  * is compiled in. Optional filters therefore need one `CompiledSeekPage` per combination or SQL-side handling.
  *
  * A page query has one form for the first page and, for forward and backward pages, one per combination of `None`
  * cursor values (at most [[maxForms]]). Each is created on first use and kept, so create a `CompiledSeekPage` once (a
  * `val` or a cache entry) and reuse it.
  *
  * For every cursor this seeker produced, items and cursors are the same as those of
  * [[SlickSeeker.pageWithoutCountAs]]. Cursors whose values don't decode or don't match the cursor columns give the
  * first page (the ad-hoc seeker partly filters on them instead).
  *
  * Requirements: the sort keys provide [[io.github.devnico.slickseeker.filter.SeekerSortKey.typedType]] and their
  * filters [[io.github.devnico.slickseeker.filter.ColumnSeekFilter.applyParam]] (the built-in ones do; checked on
  * creation), and the cursor value codecs decode values of their real type (bound parameters are not rendered via
  * `toString` like literals).
  */
final class CompiledSeekPage[A, AU, E, U, CVE, P, PU] private[pagination] (
    build: A => SlickSeeker[E, U, CVE, Any, Any],
    select: E => P,
    maxLimit: Int
)(implicit
    appShape: Shape[ColumnsShapeLevel, AU, AU, A],
    selectShape: Shape[FlatShapeLevel, P, PU, P],
    profile: JdbcProfile
) {
  private type Rows      = Seq[(PU, Any)]
  private type PageQuery = Query[(P, Any), (PU, Any), Seq]

  private val longType: TypedType[Long] = ScalaBaseType.longType
  private val limitShape: Shape[ColumnsShapeLevel, Long, Long, ConstColumn[Long]] =
    Shape.primitiveShape[Long, ColumnsShapeLevel](longType)

  private def placeholder[T](what: String): Any => T = _ => throw new IllegalStateException(s"$what (structure only)")

  // The cursor (columns, types, encoding) from the seeker's structure without real parameters: nothing is executed and
  // the structure's query is not kept.
  private val cursorSupport: SeekCursorSupport[E, U, CVE, Any, Any] = {
    val structure = build(appShape.buildParams(placeholder("parameter")))
    // Fails here rather than on the first cursor page if a sort key or filter cannot bind parameters.
    structure.paramPageQuery[P, PU](
      Some(structure.cursorSupport.paramShape.buildParams(placeholder("cursor value"))),
      List.fill(structure.columns.size)(false),
      isBackward = false,
      limitShape.buildParams(placeholder("limit"))
    )(select)
    structure.cursorSupport
  }

  private val cursorShape = cursorSupport.paramShape

  /** The most page query forms this instance can create: the first page, and forward and backward per combination of
    * `None` values in nullable cursor columns.
    */
  val maxForms: Int = 1 + 2 * (1 << cursorSupport.nullableColumns)

  private val firstPage = compile[(AU, Long), PageQuery, Rows](Shape.tuple2Shape(appShape, limitShape)) { params =>
    val (a, limit) = params.asInstanceOf[(A, ConstColumn[Long])]
    build(a).paramPageQuery[P, PU](None, Nil, isBackward = false, limit)(select)
  }

  private val cursorPages =
    new ConcurrentHashMap[(Boolean, List[Boolean]), CompiledFunction[?, ?, (AU, Any, Long), PageQuery, Rows]]()

  private def cursorPage(isBackward: Boolean, nullFlags: List[Boolean]) =
    cursorPages.computeIfAbsent(
      (isBackward, nullFlags),
      _ =>
        compile[(AU, Any, Long), PageQuery, Rows](Shape.tuple3Shape(appShape, cursorShape, limitShape)) { params =>
          val (a, cursor, limit) = params.asInstanceOf[(A, Any, ConstColumn[Long])]
          build(a).paramPageQuery[P, PU](Some(cursor), nullFlags, isBackward, limit)(select)
        }
    )

  private lazy val count = compile[AU, Rep[Int], Int](appShape) { params =>
    build(params.asInstanceOf[A]).baseQuery.length
  }(Executable.scalarIsExecutable(Shape.repColumnShape[Int, FlatShapeLevel](ScalaBaseType.intType)))

  private def compile[PV, R <: Rep[?], RU](paramShape: Shape[ColumnsShapeLevel, PV, PV, ?])(query: Any => R)(implicit
      executable: Executable[R, RU]
  ): CompiledFunction[Any => R, Any, PV, R, RU] =
    Compilable
      .function1IsCompilable[Any, R, PV, RU](
        paramShape.packedShape.asInstanceOf[Shape[ColumnsShapeLevel, Any, PV, Any]],
        paramShape,
        executable
      )
      .compiled(query, profile)

  /** The page query forms created so far (each compiles on its first execution). */
  def createdForms: Int = 1 + cursorPages.size

  /** Like [[SlickSeeker.pageAs]]: a page with the total count of the seeker's query. */
  def page(params: AU, limit: Int, cursor: Option[String], maxLimit: Int = maxLimit)(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResult[PU], dbio.NoStream, dbio.Effect.Read] = {
    import profile.api._
    for {
      total  <- count(params).result
      result <- pageWithoutCount(params, limit, cursor, maxLimit)
    } yield result.withCount(total)
  }

  /** Like [[SlickSeeker.pageWithoutCountAs]]: a page without total count. */
  def pageWithoutCount(params: AU, limit: Int, cursor: Option[String], maxLimit: Int = maxLimit)(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResultWithoutCount[PU], dbio.NoStream, dbio.Effect.Read] = {
    import profile.api._

    val normalizedLimit = limit.max(1).min(maxLimit)
    val fetchLimit      = normalizedLimit.toLong + 1
    val decoded = cursorEnvironment
      .decodeWithDirectionOrThrow(cursor)
      .flatMap { case (direction, values) =>
        cursorSupport.decodeParams(values).map(cursorValues => (direction == CursorDirection.Backward, cursorValues))
      }

    val rows: DBIOAction[Rows, NoStream, Effect.Read] = decoded match {
      case None =>
        firstPage((params, fetchLimit)).result
      case Some((isBackward, cursorValues)) =>
        cursorPage(isBackward, cursorSupport.nullFlags(cursorValues))((params, cursorValues, fetchLimit)).result
    }

    rows.map { results =>
      cursorSupport.toPage(results, decoded.exists(_._1), hasCurrentCursor = decoded.isDefined, normalizedLimit)
    }
  }
}

object CompiledSeekPage {

  /** `CompiledSeekPage[AU](build)(select)`: `AU` are the application parameter values, `build` takes their `Rep`s.
    * {{{
    * val byMinAge = CompiledSeekPage[Int]((minAge: ConstColumn[Int]) =>
    *   persons.filter(_.age >= minAge).toSeeker.seek(_.lastName.desc.nullsLast).seek(_.id.asc)
    * )(_.id)
    * byMinAge.pageWithoutCount(params = 30, limit = 20, cursor = None)
    * }}}
    */
  def apply[AU]: Builder[AU] = new Builder[AU]

  final class Builder[AU] {
    def apply[A, E, U, CVE](build: A => SlickSeeker[E, U, CVE, ?, ?]): Selecting[AU, A, E, U, CVE] =
      new Selecting(build.asInstanceOf[A => SlickSeeker[E, U, CVE, Any, Any]])
  }

  final class Selecting[AU, A, E, U, CVE](build: A => SlickSeeker[E, U, CVE, Any, Any]) {
    def apply[P, PU](select: E => P, maxLimit: Int = 100)(implicit
        appShape: Shape[ColumnsShapeLevel, AU, AU, A],
        selectShape: Shape[FlatShapeLevel, P, PU, P],
        profile: JdbcProfile
    ): CompiledSeekPage[A, AU, E, U, CVE, P, PU] =
      new CompiledSeekPage(build, select, maxLimit)
  }
}
