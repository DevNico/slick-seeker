package io.github.devnico.slickseeker.databases

import io.github.devnico.slickseeker.pagination.PaginatedResultWithoutCount
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import slick.dbio.DBIO
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Pages every case of the database's schemas forward and backward with several limits, ad-hoc and compiled, and checks
  * that each run returns every row exactly once in the database's own order, with the same pages and cursors as the
  * first run. Only runs if the database is selected (see [[TestDatabase]]).
  */
abstract class DatabaseSpec(
    database: TestDatabase,
    schemas: TestDatabase => Seq[SeekSchema] = d => Seq(new MatrixSchema(d))
) extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll {
  implicit val ec: ExecutionContext = ExecutionContext.global

  type Page = PaginatedResultWithoutCount[Int]
  type Run  = (Int, Option[String]) => DBIO[Page]

  val limits: Seq[Int] = Seq(3, 7)

  lazy val db: Database = database.open()

  // Connections with other JDBC URL properties (see open), closed with the suite's
  private var extraDbs: List[Database] = Nil

  /** A connection with extra JDBC URL `properties`. */
  def open(properties: String): Database = synchronized {
    val opened = database.open(properties)
    extraDbs ::= opened
    opened
  }

  lazy val schemaList: Seq[SeekSchema] = schemas(database)

  def await[T](f: Future[T]): T = Await.result(f, 5.minutes)

  override def beforeAll(): Unit = if (database.selected) {
    try schemaList.foreach(schema => await(db.run(schema.setUp)))
    catch {
      case e: Throwable =>
        afterAll()
        throw e
    }
  }

  override def afterAll(): Unit = if (database.selected) {
    try {
      (db :: extraDbs).foreach(_.close())
    } finally database.stop()
  }

  /** The pages of `run` from the first page following next cursors, then back from the last page following previous
    * cursors (in the order they were fetched), on one connection.
    */
  def walk(run: Run, limit: Int): DBIO[(Vector[Page], Vector[Page])] = {
    val maxPages = 1000
    def forward(cursor: Option[String], pages: Vector[Page]): DBIO[Vector[Page]] =
      run(limit, cursor).flatMap { page =>
        val all = pages :+ page
        if (page.nextCursor.isEmpty || all.size > maxPages) DBIO.successful(all) else forward(page.nextCursor, all)
      }
    def backward(page: Page, pages: Vector[Page]): DBIO[Vector[Page]] =
      page.prevCursor match {
        case Some(cursor) if pages.size <= maxPages => run(limit, Some(cursor)).flatMap(p => backward(p, pages :+ p))
        case _                                      => DBIO.successful(pages)
      }
    forward(None, Vector.empty).flatMap(pages => backward(pages.last, Vector(pages.last)).map(back => (pages, back)))
  }

  /** Checks `seekCase` against `on` (the suite's database unless given). */
  def checkCase(seekCase: SeekCase, on: => Database = db): Unit = {
    val target   = on
    val expected = await(target.run(seekCase.expected))
    expected.sorted shouldBe seekCase.ids.sorted

    for (limit <- limits) {
      var reference: Option[Vector[Page]] = None
      seekCase.runs.foreach { case (runName, run) =>
        withClue(s"$runName, limit $limit: ") {
          val (forward, backward) = await(target.run(walk(run, limit).withPinnedSession))
          forward.flatMap(_.items) shouldBe expected
          backward.reverse.flatMap(_.items) shouldBe expected
          reference match {
            case None        => reference = Some(forward)
            case Some(pages) => forward shouldBe pages
          }
        }
      }
    }
  }

  /** The first run of `seekCase` on `on` (with any limit, forward or backward) whose items are `wrong` given the
    * expected ones, as its description and items. Errors (e.g. of the setup) fail instead of counting as wrong items.
    */
  def firstWrongRun(seekCase: SeekCase, on: => Database = db)(
      wrong: (Seq[Int], Seq[Int]) => Boolean
  ): Option[(String, Seq[Int])] = {
    val target   = on
    val expected = await(target.run(seekCase.expected))
    expected.sorted shouldBe seekCase.ids.sorted
    val walks = for {
      limit          <- limits.iterator
      (runName, run) <- seekCase.runs.iterator
    } yield {
      val (forward, backward) = await(target.run(walk(run, limit).withPinnedSession))
      Seq(
        s"$runName, limit $limit, forward"  -> forward.flatMap(_.items),
        s"$runName, limit $limit, backward" -> backward.reverse.flatMap(_.items)
      )
    }
    walks.flatten.find { case (_, items) => wrong(expected, items) }
  }

  /** Checks that `seekCase` skips or repeats rows on `on`: some run's pages miss rows of the case or return some more
    * than once, and return no others.
    */
  def checkSkipsOrRepeats(seekCase: SeekCase, on: => Database = db): Unit =
    firstWrongRun(seekCase, on)((expected, items) => items.sorted != expected.sorted) match {
      case None => fail(s"${seekCase.name}: every run returned every row once")
      case Some((runName, items)) =>
        withClue(s"$runName: ") {
          items.toSet.subsetOf(seekCase.ids.toSet) shouldBe true
        }
    }

  if (database.selected) {
    s"Seekers on ${database.name}" should {
      schemaList.foreach { schema =>
        schema.cases.foreach { seekCase =>
          database.limitations.get(seekCase.name) match {
            case None => s"page every row once in the database's order: ${seekCase.name}" in checkCase(seekCase)
            case Some(reason) =>
              s"skip or repeat rows as documented ($reason): ${seekCase.name}" in checkSkipsOrRepeats(seekCase)
          }
        }
      }
    }
  }
}

class H2DatabaseSpec         extends DatabaseSpec(TestDatabase.H2)
class HsqldbDatabaseSpec     extends DatabaseSpec(TestDatabase.Hsqldb)
class SQLiteDatabaseSpec     extends DatabaseSpec(TestDatabase.SQLite)
class DerbyDatabaseSpec      extends DatabaseSpec(TestDatabase.Derby)
class PostgreSqlDatabaseSpec extends DatabaseSpec(TestDatabase.PostgreSql)
class MySqlDatabaseSpec      extends DatabaseSpec(TestDatabase.MySql)
class MariaDbDatabaseSpec extends DatabaseSpec(TestDatabase.MariaDb) {
  if (TestDatabase.MariaDb.selected) {
    "MariaDB" should {
      "page REAL columns with server-side prepared statements (binary parameters)" in {
        val weight = schemaList.flatMap(_.cases).find(_.name == "weight (REAL), id").get
        checkCase(weight, open("?useServerPrepStmts=true"))
      }
    }
  }
}
class OracleDatabaseSpec extends DatabaseSpec(TestDatabase.Oracle)
class CockroachDbDatabaseSpec extends DatabaseSpec(TestDatabase.CockroachDb) {
  if (TestDatabase.CockroachDb.selected) {
    "CockroachDB" should {
      "need the documented nulls override: PostgreSQL's defaults put null rows elsewhere than it sorts them" in {
        // The suite's tables, paged with PostgresProfile's defaults (nulls last ascending)
        val postgres = new MatrixSchema(TestDatabase.CockroachDb, MatrixProfile.PostgreSql)
        val tagCase  = postgres.cases.find(_.name == "tag (default nulls), id").get
        firstWrongRun(tagCase)((expected, items) => items != expected) shouldBe defined
      }
    }
  }
}
