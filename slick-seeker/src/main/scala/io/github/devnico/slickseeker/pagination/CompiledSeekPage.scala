package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor.{CursorDirection, CursorEnvironment}
import slick.ast.{OptionType, ScalaBaseType, TypedType}
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
import slick.sql.SqlAction

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
  * [[SlickSeeker.pageWithoutCountAs]], with row value comparisons where the seeker uses them. Cursors whose values
  * don't decode or don't match the cursor columns give the first page (the ad-hoc seeker then pages without a cursor
  * filter, or filters on the leading columns if there are fewer values than columns).
  *
  * Every page is a compiled query. A page whose cursor value a codec decoded as another type than its column's (through
  * an unchecked cast, or `null`) throws `IllegalArgumentException`, as that value can't be bound.
  *
  * The seeker needs at least one seek column.
  *
  * [[withStatementTransform]] rewrites the SQL statements of a page before they run, e.g. to replace a placeholder
  * schema compiled in with the schema of the current tenant.
  */
final class CompiledSeekPage[A, AU, E, U, CVE, P, PU] private[pagination] (
    build: A => SlickSeeker[E, U, CVE, Any, Any],
    select: E => P,
    defaultMaxLimit: Int
)(implicit
    appShape: Shape[ColumnsShapeLevel, AU, AU, A],
    selectShape: Shape[FlatShapeLevel, P, PU, P],
    profile: JdbcProfile
) {
  // The cursor's types (C, CU) grow with every `seek` and can't be named here, so they are erased to `Any`. The casts
  // below are safe because cursor shapes, parameters and decoded values all come from the same seeker structure.
  private type Rows      = Seq[(PU, Any)]
  private type PageQuery = Query[(P, Any), (PU, Any), Seq]

  private val longType: TypedType[Long] = ScalaBaseType.longType
  private val limitShape: Shape[ColumnsShapeLevel, Long, Long, ConstColumn[Long]] =
    Shape.primitiveShape[Long, ColumnsShapeLevel](longType)

  private val settings = SeekSettings.of(profile)

  private def placeholder[T](what: String): Any => T = _ => throw new IllegalStateException(s"$what (structure only)")

  // The cursor (columns, types, encoding) from the seeker's structure without real parameters: nothing is executed and
  // the structure's query is not kept.
  private val cursorSupport: SeekCursorSupport[E, U, CVE, Any, Any] = {
    val structure = build(appShape.buildParams(placeholder("parameter")))
    require(structure.columns.nonEmpty, "CompiledSeekPage needs a seeker with at least one seek column")
    // Builds every form once (forward and backward, without and with None values in the nullable cursor columns), so a
    // sort key or filter that fails to build its condition fails here rather than on a later page
    val cursor   = structure.cursorSupport.paramShape(settings.paramType).buildParams(placeholder("cursor value"))
    val nullable = structure.columns.map(_.typedType().isInstanceOf[OptionType]).toList
    for {
      isBackward <- Seq(false, true)
      nullFlags  <- Seq(nullable.map(_ => false), nullable).distinct
    } structure.paramPageQuery[P, PU](
      Some(cursor),
      nullFlags,
      isBackward,
      limitShape.buildParams(placeholder("limit")),
      settings
    )(
      select
    )
    structure.cursorSupport
  }

  private val cursorShape = cursorSupport.paramShape(settings.paramType)

  /** The most page query forms this instance can create: the first page, and forward and backward per combination of
    * `None` values in nullable cursor columns.
    */
  val maxForms: Int = 1 + 2 * (1 << cursorSupport.nullableColumns)

  private val firstPage = compile[(AU, Long), PageQuery, Rows](Shape.tuple2Shape(appShape, limitShape)) { params =>
    val (a, limit) = params.asInstanceOf[(A, ConstColumn[Long])]
    build(a).paramPageQuery[P, PU](None, Nil, isBackward = false, limit, settings)(select)
  }

  private val cursorPages =
    new ConcurrentHashMap[(Boolean, List[Boolean]), CompiledFunction[?, ?, (AU, Any, Long), PageQuery, Rows]]()

  private def cursorPage(isBackward: Boolean, nullFlags: List[Boolean]) =
    cursorPages.computeIfAbsent(
      (isBackward, nullFlags),
      _ =>
        compile[(AU, Any, Long), PageQuery, Rows](Shape.tuple3Shape(appShape, cursorShape, limitShape)) { params =>
          val (a, cursor, limit) = params.asInstanceOf[(A, Any, ConstColumn[Long])]
          build(a).paramPageQuery[P, PU](Some(cursor), nullFlags, isBackward, limit, settings)(select)
        }
    )

  private lazy val count = compile[AU, Rep[Int], Int](appShape) { params =>
    implicit val intType: TypedType[Int] = ScalaBaseType.intType
    // Same count as SlickSeeker.page: no element columns in the counted subquery.
    build(params.asInstanceOf[A]).baseQuery.map(_ => slick.lifted.LiteralColumn(1)).length
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
  def page(params: AU, limit: Int, cursor: Option[String], maxLimit: Int = defaultMaxLimit)(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResult[PU], dbio.NoStream, dbio.Effect.Read] =
    pageWith(params, limit, cursor, maxLimit, None)

  /** Like [[SlickSeeker.pageWithoutCountAs]]: a page without total count. */
  def pageWithoutCount(params: AU, limit: Int, cursor: Option[String], maxLimit: Int = defaultMaxLimit)(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResultWithoutCount[PU], dbio.NoStream, dbio.Effect.Read] =
    pageWithoutCountWith(params, limit, cursor, maxLimit, None)

  /** The pages of this instance with `transform` applied to every SQL statement they run (the count and the page
    * query), e.g. to replace a placeholder schema with the schema of the current tenant. The compiled forms are shared
    * with this instance, so this is cheap to call per request.
    *
    * `transform` must keep the bound parameters (`?`) and the selected columns as they are.
    * {{{
    * byMinAge.withStatementTransform(_.replace("tenant_placeholder", tenantSchema))
    *   .pageWithoutCount(params = 30, limit = 20, cursor = None)
    * }}}
    */
  def withStatementTransform(transform: String => String): StatementTransformed =
    new StatementTransformed(transform)

  /** Pages whose SQL statements are rewritten by `transform`, see [[withStatementTransform]]. */
  final class StatementTransformed private[CompiledSeekPage] (transform: String => String) {

    /** Like [[CompiledSeekPage.page]], with the statements transformed. */
    def page(params: AU, limit: Int, cursor: Option[String], maxLimit: Int = defaultMaxLimit)(implicit
        cursorEnvironment: CursorEnvironment[CVE],
        ec: ExecutionContext
    ): dbio.DBIOAction[PaginatedResult[PU], dbio.NoStream, dbio.Effect.Read] =
      pageWith(params, limit, cursor, maxLimit, Some(transform))

    /** Like [[CompiledSeekPage.pageWithoutCount]], with the statements transformed. */
    def pageWithoutCount(params: AU, limit: Int, cursor: Option[String], maxLimit: Int = defaultMaxLimit)(implicit
        cursorEnvironment: CursorEnvironment[CVE],
        ec: ExecutionContext
    ): dbio.DBIOAction[PaginatedResultWithoutCount[PU], dbio.NoStream, dbio.Effect.Read] =
      pageWithoutCountWith(params, limit, cursor, maxLimit, Some(transform))
  }

  private def transformed[R, S <: dbio.NoStream, E <: dbio.Effect](
      action: SqlAction[R, S, E],
      transform: Option[String => String]
  ): dbio.DBIOAction[R, S, E] =
    transform.fold[dbio.DBIOAction[R, S, E]](action)(f => action.overrideStatements(action.statements.map(f)))

  private def pageWith(
      params: AU,
      limit: Int,
      cursor: Option[String],
      maxLimit: Int,
      transform: Option[String => String]
  )(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResult[PU], dbio.NoStream, dbio.Effect.Read] = {
    import profile.api._
    // Built first, so it throws (bad cursors, mistyped cursor values) before the count runs, like pageWithoutCount
    val withoutCount = pageWithoutCountWith(params, limit, cursor, maxLimit, transform)
    for {
      total  <- transformed(count(params).result, transform)
      result <- withoutCount
    } yield result.withCount(total)
  }

  private def pageWithoutCountWith(
      params: AU,
      limit: Int,
      cursor: Option[String],
      maxLimit: Int,
      transform: Option[String => String]
  )(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResultWithoutCount[PU], dbio.NoStream, dbio.Effect.Read] = {
    import profile.api._

    val normalizedLimit = SlickSeeker.normalizeLimit(limit, maxLimit)
    val fetchLimit      = normalizedLimit.toLong + 1
    val decoded         = cursorEnvironment.decodeWithDirectionOrThrow(cursor)

    def compiledPage(cursorValues: Option[(Boolean, Any)]) = {
      val rows: DBIOAction[Rows, NoStream, Effect.Read] = cursorValues match {
        case None =>
          transformed(firstPage((params, fetchLimit)).result, transform)
        case Some((isBackward, values)) =>
          transformed(
            cursorPage(isBackward, cursorSupport.nullFlags(values))((params, values, fetchLimit)).result,
            transform
          )
      }
      rows.map { results =>
        cursorSupport.toPage(results, cursorValues.exists(_._1), cursorValues.isDefined, normalizedLimit)
      }
    }

    // Cursors whose values don't decode or don't fit the cursor columns give the first page, as in the docs
    decoded.flatMap { case (direction, values) =>
      cursorSupport.decodeParams(values).map(cursorValues => (direction == CursorDirection.Backward, cursorValues))
    } match {
      case None => compiledPage(None)
      case Some(cursorValues @ (_, values)) =>
        cursorSupport.mistyped(values).foreach { problem =>
          throw new IllegalArgumentException(s"CompiledSeekPage: $problem, which can't be bound")
        }
        compiledPage(Some(cursorValues))
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
