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
  Shape
}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

/**
 * Pages of a seeker as compiled queries: application parameters, cursor values and the limit are bound as query
 * parameters, so Slick compiles each form of the page query once instead of on every page.
 *
 * `build` creates the seeker from the application parameters (e.g. a search term or filter values as `Rep`s). It must
 * not depend on anything else that varies per call: everything outside the parameters is compiled into the query. A
 * page query has one form per direction (first page, forward, backward) and per combination of `None` cursor values;
 * each is compiled on first use and kept by this instance, so create it once (e.g. as a `val` or in a cache) and reuse
 * it.
 *
 * Requires the seek columns' sort keys to provide [[io.github.devnico.slickseeker.filter.SeekerSortKey.typedType]] and
 * their filters [[io.github.devnico.slickseeker.filter.ColumnSeekFilter.applyParam]], as the built-in ones do.
 */
final class CompiledSeekPage[A, AU, E, U, CVE, C, CU, P, PU](
    build: A => SlickSeeker[E, U, CVE, C, CU],
    select: E => P,
    maxLimit: Int
)(implicit
    appShape: Shape[ColumnsShapeLevel, AU, AU, A],
    selectShape: Shape[FlatShapeLevel, P, PU, P],
    profile: JdbcProfile
) {
  private type Rows = Seq[(PU, CU)]
  private type PageQuery = Query[(P, C), (PU, CU), Seq]

  private val longType: TypedType[Long] = ScalaBaseType.longType
  private val limitShape: Shape[ColumnsShapeLevel, Long, Long, ConstColumn[Long]] =
    Shape.primitiveShape[Long, ColumnsShapeLevel](longType)

  // The seeker's structure (cursor columns and their types) without real parameters; nothing is executed.
  private val structure: SlickSeeker[E, U, CVE, C, CU] =
    build(appShape.buildParams(_ => throw new IllegalStateException("structure only")))

  private val firstPage: CompiledFunction[?, ?, (AU, Long), PageQuery, Rows] = {
    val paramShape = Shape.tuple2Shape(appShape, limitShape)
    compile[(AU, Long)](paramShape) { params =>
      val (a, limit) = params.asInstanceOf[(A, ConstColumn[Long])]
      build(a).paramPageQuery[P, PU](None, Nil, isBackward = false, limit)(select)
    }
  }

  private val cursorPages =
    new ConcurrentHashMap[(Boolean, List[Boolean]), CompiledFunction[?, ?, (AU, CU, Long), PageQuery, Rows]]()

  private def cursorPage(isBackward: Boolean, nullFlags: List[Boolean]) =
    cursorPages.computeIfAbsent(
      (isBackward, nullFlags),
      _ => {
        val cursorShape = structure.qwc.paramShape().asInstanceOf[Shape[ColumnsShapeLevel, CU, CU, Any]]
        val paramShape  = Shape.tuple3Shape(appShape, cursorShape, limitShape)
        compile[(AU, CU, Long)](paramShape) { params =>
          val (a, cursor, limit) = params.asInstanceOf[(A, Any, ConstColumn[Long])]
          build(a).paramPageQuery[P, PU](Some(cursor), nullFlags, isBackward, limit)(select)
        }
      }
    )

  private def compile[PV](paramShape: Shape[ColumnsShapeLevel, PV, PV, ?])(
      query: Any => PageQuery
  ): CompiledFunction[Any => PageQuery, Any, PV, PageQuery, Rows] = {
    val packedShape = paramShape.packedShape.asInstanceOf[Shape[ColumnsShapeLevel, Any, PV, Any]]
    Compilable
      .function1IsCompilable[Any, PageQuery, PV, Rows](
        packedShape,
        paramShape.asInstanceOf[Shape[ColumnsShapeLevel, PV, PV, ?]],
        Executable.queryIsExecutable[(P, C), (PU, CU), Seq]
      )
      .compiled(query, profile)
  }

  /** The number of compiled page query forms so far (for diagnostics and tests). */
  def compiledForms: Int = 1 + cursorPages.size

  /** A page without total count, with the same items and cursors as [[SlickSeeker.pageWithoutCountAs]]. */
  def pageWithoutCount(params: AU, limit: Int, cursor: Option[String])(implicit
      cursorEnvironment: CursorEnvironment[CVE],
      ec: ExecutionContext
  ): dbio.DBIOAction[PaginatedResultWithoutCount[PU], dbio.NoStream, dbio.Effect.Read] = {
    import profile.api._

    val normalizedLimit = limit.max(1).min(maxLimit)
    val fetchLimit      = normalizedLimit.toLong + 1
    val decoded = cursorEnvironment
      .decodeWithDirectionOrThrow(cursor)
      .filter(_._2.nonEmpty)
      .flatMap { case (direction, values) =>
        structure.qwc.decode(values.toList).map(cursorValues => (direction == CursorDirection.Backward, cursorValues))
      }

    val rows: DBIOAction[Rows, NoStream, Effect.Read] = decoded match {
      case None =>
        firstPage((params, fetchLimit)).result
      case Some((isBackward, cursorValues)) =>
        cursorPage(isBackward, structure.qwc.nullFlags(cursorValues))((params, cursorValues, fetchLimit)).result
    }

    rows.map { results =>
      structure.toPage(results, decoded.exists(_._1), hasCurrentCursor = decoded.isDefined, normalizedLimit)
    }
  }
}

object CompiledSeekPage {

  /**
   * `CompiledSeekPage[AU](build)(select)`: `AU` are the application parameter values, `build` takes their `Rep`s.
   * {{{
   * val byMinAge = CompiledSeekPage[Int]((minAge: ConstColumn[Int]) =>
   *   persons.filter(_.age >= minAge).toSeeker.seek(_.lastName.desc.nullsLast).seek(_.id.asc)
   * )(_.id)
   * byMinAge.pageWithoutCount(params = 30, limit = 20, cursor = None)
   * }}}
   */
  def apply[AU]: Builder[AU] = new Builder[AU]

  final class Builder[AU] {
    def apply[A, E, U, CVE, C, CU](build: A => SlickSeeker[E, U, CVE, C, CU]): Selecting[AU, A, E, U, CVE, C, CU] =
      new Selecting(build)
  }

  final class Selecting[AU, A, E, U, CVE, C, CU](build: A => SlickSeeker[E, U, CVE, C, CU]) {
    def apply[P, PU](select: E => P, maxLimit: Int = 100)(implicit
        appShape: Shape[ColumnsShapeLevel, AU, AU, A],
        selectShape: Shape[FlatShapeLevel, P, PU, P],
        profile: JdbcProfile
    ): CompiledSeekPage[A, AU, E, U, CVE, C, CU, P, PU] =
      new CompiledSeekPage(build, select, maxLimit)
  }
}
