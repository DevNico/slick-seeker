package io.github.devnico.slickseeker.databases

import io.github.devnico.slickseeker.pagination.{CompiledSeekPage, PaginatedResultWithoutCount, SlickSeeker}
import slick.lifted.{ColumnsShapeLevel, FlatShapeLevel, Ordered}

import scala.concurrent.ExecutionContext

/** A seeker configuration of a [[SeekSchema]]: how to page its rows' ids, and their ids in the database's order. */
final case class SeekCase(
    name: String,
    ids: Seq[Int],
    expected: slick.dbio.DBIO[Seq[Int]],
    runs: Seq[(String, (Int, Option[String]) => slick.dbio.DBIO[PaginatedResultWithoutCount[Int]])]
)

/** Tables, rows and seeker configurations to page against a [[TestDatabase]] with `profile` (usually its own). */
abstract class SeekSchema(val database: TestDatabase, val profile: MatrixProfile) {
  import profile.api._

  implicit val ec: ExecutionContext = ExecutionContext.global

  /** Creates the tables and inserts the rows. */
  def setUp: DBIO[Unit]

  /** The ids of all rows (the rows cases page unless they filter). */
  def ids: Seq[Int]

  def cases: Seq[SeekCase]

  /** Column options declaring `column`'s SQL type for this database if it overrides Slick's default, else `default`. */
  def declared[T](column: String, default: slick.ast.ColumnOption[T]*): Seq[slick.ast.ColumnOption[T]] =
    database.sqlType(column).fold(default)(t => Seq(profile.columnOptions.SqlType(t)))

  private val idShape: Shape[FlatShapeLevel, Rep[Int], Int, Rep[Int]] = implicitly
  private val unitShape: Shape[ColumnsShapeLevel, Unit, Unit, Unit]   = implicitly

  /** A case paging `seeker` ad-hoc and compiled, as ids (`id`) and whole elements (`whole` gives their id). Expected:
    * the base query sorted by the seek columns as declared (nulls where the database puts them by default).
    */
  def seekCase[E, U](name: String, seeker: SlickSeeker[E, U, String, ?, ?], rows: Seq[Int] = ids)(
      id: E => Rep[Int],
      whole: U => Int
  ): SeekCase = {
    implicit val eShape: Shape[FlatShapeLevel, E, U, E] = seeker.qwc.queryShape
    val expected: Query[Rep[Int], Int, Seq] =
      seeker.baseQuery.sortBy(e => new Ordered(seeker.columns.flatMap(_.col(e).columns))).map(id)
    val compiledIds   = CompiledSeekPage[Unit]((_: Unit) => seeker)(id, maxLimit = 1000)(unitShape, idShape, profile)
    val compiledWhole = CompiledSeekPage[Unit]((_: Unit) => seeker)(e => e, maxLimit = 1000)(unitShape, eShape, profile)
    val count         = rows.size

    def checkedCount[T](total: Int, page: PaginatedResultWithoutCount[T]): PaginatedResultWithoutCount[T] = {
      require(total == count, s"total $total instead of $count")
      page
    }

    SeekCase(
      name,
      rows,
      expected.result,
      Seq(
        "ad-hoc pageWithoutCountAs(id)" -> ((limit, cursor) =>
          seeker.pageWithoutCountAs(limit, cursor, 1000)(id)(idShape, profile, profile.api.cursorEnv, ec)
        ),
        "ad-hoc page" -> ((limit, cursor) =>
          seeker
            .page(limit, cursor, 1000)(profile, profile.api.cursorEnv, ec)
            .map(p => checkedCount(p.total, p.withoutCount.mapItems(whole)))
        ),
        "ad-hoc pageAs(id)" -> ((limit, cursor) =>
          seeker
            .pageAs(limit, cursor, 1000)(id)(idShape, profile, profile.api.cursorEnv, ec)
            .map(p => checkedCount(p.total, p.withoutCount))
        ),
        "compiled pageWithoutCount(id)" -> ((limit, cursor) =>
          compiledIds.pageWithoutCount((), limit, cursor)(profile.api.cursorEnv, ec)
        ),
        "compiled page" -> ((limit, cursor) =>
          compiledWhole.page((), limit, cursor)(profile.api.cursorEnv, ec).map(p =>
            checkedCount(p.total, p.withoutCount.mapItems(whole))
          )
        )
      )
    )
  }
}
