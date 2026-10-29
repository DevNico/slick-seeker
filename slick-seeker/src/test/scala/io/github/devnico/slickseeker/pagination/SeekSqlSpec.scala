package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.support.{ChainH2Profile, MyH2Profile, NullsHighH2Profile}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._
import slick.jdbc.JdbcProfile
import slick.lifted.FlatShapeLevel

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

// The SQL of cursor pages, from H2's query statistics: row value comparisons where seek columns qualify, and cursor
// values bound as parameters.
class SeekSqlSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val ec: ExecutionContext = ExecutionContext.global

  implicit val intCodec: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = value.toIntOption
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

  class Items(t: Tag) extends Table[(Int, String, Option[String])](t, "seek_sql_items") {
    def id   = column[Int]("id", O.PrimaryKey)
    def name = column[String]("name")
    def tag  = column[Option[String]]("tag")
    def *    = (id, name, tag)
  }
  val items = TableQuery[Items]
  val db =
    Database.forURL("jdbc:h2:mem:seeksql;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver", keepAliveConnection = true)

  override def beforeAll(): Unit = await(
    db.run(
      DBIO.seq(
        items.schema.create,
        items ++= (1 to 9).map(i => (i, s"n${i % 3}", if (i % 2 == 0) None else Some(s"t$i"))),
        sqlu"SET QUERY_STATISTICS TRUE"
      )
    )
  )
  override def afterAll(): Unit = db.close()
  def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  val idShape: Shape[FlatShapeLevel, Rep[Int], Int, Rep[Int]] = implicitly
  val stringEnv: CursorEnvironment[String]                    = implicitly

  def lastSql(run: => Unit): String = {
    await(db.run(sqlu"SET QUERY_STATISTICS FALSE" andThen sqlu"SET QUERY_STATISTICS TRUE"))
    run
    val statements = await(db.run(sql"SELECT SQL_STATEMENT FROM INFORMATION_SCHEMA.QUERY_STATISTICS".as[String]))
      .filter(_.contains("seek_sql_items"))
    statements should have size 1
    statements.head
  }

  type ItemSeeker = SlickSeeker[Items, (Int, String, Option[String]), String, ?, ?]

  /** The SQL of the second page (forward) and of the page before it (backward). */
  def cursorSql(seeker: ItemSeeker, profile: JdbcProfile = MyH2Profile): (String, String) = {
    def page(cursor: Option[String]) =
      await(db.run(seeker.pageWithoutCountAs(2, cursor)(_.id)(idShape, profile, stringEnv, ec)))
    val next   = page(None).nextCursor
    val second = page(next)
    (lastSql(page(next)), lastSql(page(second.prevCursor)))
  }

  "A seeker" should {
    "compare non-null columns of one direction as a row, with bound cursor values" in {
      val (forward, backward) = cursorSql(items.toSeeker.seek(_.name.asc).seek(_.id.asc))
      forward should include("""where ("name", "id") > (?, ?) order by "name", "id"""")
      backward should include("""where ("name", "id") < (?, ?) order by "name" desc""")
      cursorSql(items.toSeeker.seek(_.name.desc).seek(_.id.desc))._1 should include("""("name", "id") < (?, ?)""")
    }

    "chain the conditions of mixed directions, nullable columns or with row comparisons off" in {
      val chained = Seq(
        cursorSql(items.toSeeker.seek(_.name.asc).seek(_.id.desc))._1,
        cursorSql(items.toSeeker.seek(_.tag.asc.nullsFirst).seek(_.id.asc))._1,
        cursorSql(items.toSeeker.seek(_.name.asc).seek(_.id.asc), ChainH2Profile)._1
      )
      all(chained) should include(""" = ?) and """)
      all(chained) should not include ") > ("
    }

    "sort nulls of Option columns explicitly where the database puts them, and non-null columns without a clause" in {
      // H2 sorts nulls low: first ascending, last descending
      cursorSql(items.toSeeker.seek(_.tag.asc).seek(_.id.asc))._1 should include(
        """order by "tag" nulls first, "id" """
      )
      cursorSql(items.toSeeker.seek(_.tag.desc).seek(_.id.desc))._1 should include(
        """order by "tag" desc nulls last, "id" desc """
      )
      cursorSql(items.toSeeker.seek(_.tag.asc).seek(_.id.asc), NullsHighH2Profile)._1 should include(
        """order by "tag" nulls last, "id" """
      )
      // Explicit nulls orderings are kept
      cursorSql(items.toSeeker.seek(_.tag.asc.nullsLast).seek(_.id.asc))._1 should include(
        """order by "tag" nulls last"""
      )
      cursorSql(items.toSeeker.seek(_.name.desc).seek(_.id.desc))._1 should include(
        """order by "name" desc, "id" desc """
      )
    }

    "write cursor values a codec decoded as another type than the column's as literals" in {
      // Decodes Long for an Int column (unchecked): bound as a parameter, it would fail
      val mistyped: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
        def encode(value: Int): String         = value.toString
        def decode(value: String): Option[Int] = value.toLongOption.asInstanceOf[Option[Int]]
      }
      val byId   = MyH2Profile.api.seekerSortKeyIdentity[Int, String](implicitly, implicitly, mistyped)
      val seeker = items.toSeeker.seek(_.name.asc).seek(_.id.asc)(byId)
      val page =
        (c: Option[String]) => await(db.run(seeker.pageWithoutCountAs(4, c)(_.id)(idShape, MyH2Profile, stringEnv, ec)))
      val first = page(None)
      val typed = items.toSeeker.seek(_.name.asc).seek(_.id.asc)
      page(first.nextCursor) shouldBe await(
        db.run(typed.pageWithoutCountAs(4, first.nextCursor)(_.id)(idShape, MyH2Profile, stringEnv, ec))
      )
      lastSql(page(first.nextCursor)) should include("""("name", "id") > (?, 1)""")

      val compiled = CompiledSeekPage[Unit]((_: Unit) => items.toSeeker.seek(_.name.asc).seek(_.id.asc)(byId))(_.id)
      await(db.run(compiled.pageWithoutCount((), 4, first.nextCursor))) shouldBe page(first.nextCursor)

      // Strict ones fail on such a page instead
      val strict =
        CompiledSeekPage.strict[Unit]((_: Unit) => items.toSeeker.seek(_.name.asc).seek(_.id.asc)(byId))(_.id)
      await(db.run(strict.pageWithoutCount((), 4, None))) shouldBe first
      val error = the[IllegalArgumentException] thrownBy strict.pageWithoutCount((), 4, first.nextCursor)
      error.getMessage shouldBe "CompiledSeekPage.strict: the cursor value codec of a int column decoded " +
        "java.lang.Long, which can't be bound"
      // Such pages run as ad-hoc pages, with the application parameters still bound
      val fromId = CompiledSeekPage[Int]((min: ConstColumn[Int]) =>
        items.filter(_.id >= min).toSeeker.seek(_.name.asc).seek(_.id.asc)(byId)
      )(_.id)
      lastSql(await(db.run(fromId.pageWithoutCount(2, 4, first.nextCursor)))) should include(""""id" >= ?""")
    }

    "use row comparisons in compiled pages" in {
      val page = CompiledSeekPage[Unit]((_: Unit) => items.toSeeker.seek(_.name.asc).seek(_.id.asc))(_.id)
      val next = await(db.run(page.pageWithoutCount((), 2, None))).nextCursor
      lastSql(await(db.run(page.pageWithoutCount((), 2, next)))) should include("""("name", "id") > (?, ?)""")
    }
  }
}
