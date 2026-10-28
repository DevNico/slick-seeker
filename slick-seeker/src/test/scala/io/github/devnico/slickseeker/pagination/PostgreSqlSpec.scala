package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.databases.TestDatabase
import io.github.devnico.slickseeker.support.{
  ChainPgProfile,
  MyPgProfile,
  NullsLowPgProfile,
  SortDirection,
  TypedParamsPgProfile
}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.postgresql.PostgreSQLContainer
import MyPgProfile.api._
import slick.jdbc.JdbcProfile
import slick.lifted.{ColumnsShapeLevel, FlatShapeLevel, Shape}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

sealed trait Level
object Level {
  case object High extends Level
  case object Low  extends Level
  case object Mid  extends Level
}

// Seekers against a real PostgreSQL (Testcontainers; only if `postgresql` is selected, see TestDatabase): native enums
// and uuids mapped to String, REAL columns and default nulls, for ad-hoc and compiled pages with and without row value
// comparisons.
class PostgreSqlSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val ec: ExecutionContext = ExecutionContext.global

  implicit val intCodec: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = value.toIntOption
  }
  implicit val floatCodec: CursorValueCodec[Float, String] = new CursorValueCodec[Float, String] {
    def encode(value: Float): String         = value.toString
    def decode(value: String): Option[Float] = value.toFloatOption
  }
  implicit val strCodec: CursorValueCodec[String, String] = new CursorValueCodec[String, String] {
    def encode(value: String): String         = value
    def decode(value: String): Option[String] = Some(value)
  }
  implicit val optStrCodec: CursorValueCodec[Option[String], String] = new CursorValueCodec[Option[String], String] {
    def encode(value: Option[String]): String = value.fold("N")("S" + _)
    def decode(value: String): Option[Option[String]] =
      if (value == "N") Some(None) else if (value.startsWith("S")) Some(Some(value.drop(1))) else None
  }

  val levels: Map[String, Level] = Map("high" -> Level.High, "low" -> Level.Low, "mid" -> Level.Mid)
  implicit val levelType: BaseColumnType[Level] =
    MappedColumnType.base[Level, String](l => levels.collectFirst { case (k, `l`) => k }.get, levels)
  implicit val levelCodec: CursorValueCodec[Level, String] = new CursorValueCodec[Level, String] {
    def encode(value: Level): String         = levelType.valueToSQLLiteral(value)
    def decode(value: String): Option[Level] = levels.collectFirst { case (k, l) if s"'$k'" == value => l }
  }
  implicit val optLevelCodec: CursorValueCodec[Option[Level], String] = new CursorValueCodec[Option[Level], String] {
    def encode(value: Option[Level]): String = value.fold("N")(levelCodec.encode)
    def decode(value: String): Option[Option[Level]] =
      if (value == "N") Some(None) else levelCodec.decode(value).map(Some(_))
  }

  case class Item(
      id: Int,
      name: String,
      level: Level,
      olevel: Option[Level],
      extId: String,
      score: Float,
      tag: Option[String]
  )

  class Items(t: Tag) extends Table[Item](t, "pg_items") {
    def id     = column[Int]("id", O.PrimaryKey)
    def name   = column[String]("name")
    def level  = column[Level]("level")   // native enum level_type
    def olevel = column[Option[Level]]("olevel")
    def extId  = column[String]("ext_id") // uuid
    def score  = column[Float]("score")   // real
    def tag    = column[Option[String]]("tag")
    def *      = (id, name, level, olevel, extId, score, tag).mapTo[Item]
  }
  val items = TableQuery[Items]

  val selected: Boolean                   = TestDatabase.PostgreSql.selected
  lazy val container: PostgreSQLContainer = new PostgreSQLContainer("postgres:16.15-alpine")
  lazy val db: Database =
    Database.forURL(
      container.getJdbcUrl,
      container.getUsername,
      container.getPassword,
      driver = "org.postgresql.Driver"
    )

  // The simple query protocol sends `EXPLAIN (GENERIC_PLAN)` with its `$n` placeholders as is
  lazy val simpleDb: Database = {
    val url = container.getJdbcUrl
    Database.forURL(
      url + (if (url.contains("?")) "&" else "?") + "preferQueryMode=simple",
      container.getUsername,
      container.getPassword,
      driver = "org.postgresql.Driver"
    )
  }

  def await[T](f: Future[T]): T = Await.result(f, 30.seconds)

  override def beforeAll(): Unit = if (selected) {
    container.start()
    try setUp()
    catch {
      case e: Throwable =>
        container.stop()
        throw e
    }
  }

  def setUp(): Unit =
    await(
      db.run(
        DBIO.seq(
          sqlu"create type level_type as enum ('high', 'low', 'mid')",
          sqlu"""create table pg_items (
                   id int primary key, name text not null, level level_type not null, olevel level_type,
                   ext_id uuid not null, score real not null, tag text)""",
          // Ties on every sort column, nulls in the Option columns, REAL values that aren't exact (0.1, 0.2, …)
          sqlu"""insert into pg_items
                 select i, 'n' || (i % 4), (array['high', 'low', 'mid'])[1 + i % 3]::level_type,
                        case when i % 5 = 0 then null else (array['high', 'low', 'mid'])[1 + (i + 1) % 3]::level_type end,
                        md5((i % 9)::text)::uuid, (i % 7) / 10.0, case when i % 3 = 0 then null else 't' || (i % 4) end
                 from generate_series(1, 31) i""",
          // For query plans: enough rows that an index serving the order beats a sort
          sqlu"create table pg_big (id int primary key, name text not null, tag text)",
          sqlu"""insert into pg_big
                 select i, 'n' || (i % 1000), case when i % 7 = 0 then null else 't' || (i % 500) end
                 from generate_series(1, 50000) i""",
          sqlu"create index pg_big_name_id on pg_big (name, id)",
          sqlu"create index pg_big_tag_id on pg_big (tag, id)",
          sqlu"analyze pg_big"
        )
      )
    )

  override def afterAll(): Unit = if (selected) {
    db.close()
    simpleDb.close()
    container.stop()
  }

  type Config = Query[Items, Item, Seq] => SlickSeeker[Items, Item, String, ?, ?]

  val configs: Seq[(String, Config)] = Seq(
    "row: name, id"                -> (_.toSeeker.seek(_.name.asc).seek(_.id.asc)),
    "row: name, id desc"           -> (_.toSeeker.seek(_.name.desc).seek(_.id.desc)),
    "row: enum, id"                -> (_.toSeeker.seek(_.level.asc).seek(_.id.asc)),
    "row: uuid as String, id"      -> (_.toSeeker.seek(_.extId.asc).seek(_.id.asc)),
    "row: REAL, id"                -> (_.toSeeker.seek(_.score.asc).seek(_.id.asc)),
    "row: seekDirection desc"      -> (_.toSeeker.seek(_.level.asc).seek(_.id.asc).seekDirection(SortDirection.Desc)),
    "chain: REAL desc, name, id"   -> (_.toSeeker.seek(_.score.desc).seek(_.name.asc).seek(_.id.asc)),
    "chain: Option enum nullsLast" -> (_.toSeeker.seek(_.olevel.desc.nullsLast).seek(_.id.asc)),
    "chain: Option enum default"   -> (_.toSeeker.seek(_.olevel.desc).seek(_.id.asc)),
    "chain: Option text default"   -> (_.toSeeker.seek(_.tag.asc).seek(_.id.desc)),
    "chain: Option nullsFirst"     -> (_.toSeeker.seek(_.tag.desc.nullsFirst).seek(_.level.asc).seek(_.id.asc))
  )

  val idShape: Shape[FlatShapeLevel, Rep[Int], Int, Rep[Int]] = implicitly
  val unitShape: Shape[ColumnsShapeLevel, Unit, Unit, Unit]   = implicitly
  val stringEnv: CursorEnvironment[String]                    = implicitly

  type Page = PaginatedResultWithoutCount[Int]

  def adHoc(config: Config, profile: JdbcProfile)(limit: Int, cursor: Option[String]): Page =
    await(db.run(config(items).pageWithoutCountAs(limit, cursor, 1000)(_.id)(idShape, profile, stringEnv, ec)))

  def compiled(config: Config, profile: JdbcProfile): (Int, Option[String]) => Page = {
    val page = CompiledSeekPage[Unit]((_: Unit) => config(items))(_.id, maxLimit = 1000)(unitShape, idShape, profile)
    (limit, cursor) => await(db.run(page.pageWithoutCount((), limit, cursor)))
  }

  /** The pages from the first page following next cursors. */
  def forward(run: (Int, Option[String]) => Page, limit: Int): Vector[Page] = {
    @tailrec def go(cursor: Option[String], pages: Vector[Page]): Vector[Page] = {
      val page = run(limit, cursor)
      if (page.nextCursor.isEmpty || pages.size > 100) pages :+ page else go(page.nextCursor, pages :+ page)
    }
    go(None, Vector.empty)
  }

  class Big(t: Tag) extends Table[(Int, String, Option[String])](t, "pg_big") {
    def id   = column[Int]("id", O.PrimaryKey)
    def name = column[String]("name")
    def tag  = column[Option[String]]("tag")
    def *    = (id, name, tag)
  }
  val big = TableQuery[Big]

  /** The generic plan (PostgreSQL 16) of the seeker's page after its first page. */
  def secondPagePlan(
      seeker: SlickSeeker[Big, (Int, String, Option[String]), String, ?, ?],
      profile: JdbcProfile = MyPgProfile
  ): String = {
    val first = await(db.run(seeker.pageWithoutCountAs(20, None)(_.id)(idShape, MyPgProfile, stringEnv, ec)))
    val query = seeker.pageQuery(stringEnv.decodeWithDirectionOrThrow(first.nextCursor), 20, SeekSettings.of(profile))
    val sql   = query.result.statements.head
    val numbered =
      sql.split("\\?", -1).zipWithIndex.map { case (part, i) => if (i == 0) part else s"$$$i$part" }.mkString
    await(simpleDb.run(sql"EXPLAIN (GENERIC_PLAN) #$numbered".as[String])).mkString("\n")
  }

  if (selected) "Seekers on PostgreSQL" should {
    configs.foreach { case (name, config) =>
      s"return every row once in order, the same pages ad-hoc and compiled, with and without row comparisons: $name" in {
        val all = adHoc(config, MyPgProfile)(1000, None).items
        all should have size 31

        val runs = Seq(
          "ad-hoc"         -> (adHoc(config, MyPgProfile) _),
          "ad-hoc chain"   -> (adHoc(config, ChainPgProfile) _),
          "compiled"       -> compiled(config, MyPgProfile),
          "compiled chain" -> compiled(config, ChainPgProfile)
        )
        for (limit <- Seq(2, 3)) {
          val expected = forward(runs.head._2, limit)
          runs.foreach { case (run, page) =>
            withClue(s"$run limit=$limit: ") {
              val pages = forward(page, limit)
              pages.flatMap(_.items) shouldBe all
              pages shouldBe expected

              // Back from the last page through every previous cursor
              val back = Iterator
                .iterate(Option(pages.last))(_.flatMap(_.prevCursor).map(c => page(limit, Some(c))))
                .takeWhile(_.isDefined)
                .flatten
                .toVector
              back.reverse.flatMap(_.items) shouldBe all
            }
          }
        }
      }
    }

    "serve pages from indexes: default nulls and row value comparisons keep the index order" in {
      val defaultNulls = secondPagePlan(big.toSeeker.seek(_.tag.desc).seek(_.id.desc))
      withClue(defaultNulls) {
        defaultNulls should include("Index Scan Backward using pg_big_tag_id")
        defaultNulls should not include "Sort"
      }
      val row = secondPagePlan(big.toSeeker.seek(_.name.asc).seek(_.id.asc))
      withClue(row) {
        row should include("Index Cond: (ROW(name, id) > ROW($1, $2))")
        row should not include "Sort"
      }
      // Control: nulls last for a descending column (not PostgreSQL's default) needs a sort
      secondPagePlan(big.toSeeker.seek(_.tag.desc).seek(_.id.desc), NullsLowPgProfile) should include("Sort Key")
    }

    "fail on enums for compiled pages that bind strings as varchar (what untyped string parameters fix)" in {
      val config: Config = _.toSeeker.seek(_.level.asc).seek(_.id.asc)
      val page           = compiled(config, TypedParamsPgProfile)
      val error          = the[Exception] thrownBy page(2, page(2, None).nextCursor)
      error.getMessage should include("operator does not exist: level_type > character varying")
    }
  }
}
