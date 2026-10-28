package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.support.{MyH2Profile, SortDirection}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._
import slick.jdbc.{HsqldbProfile, JdbcProfile, MySQLProfile, PostgresProfile, SQLServerProfile, SQLiteProfile}

// The SQL of cursor pages as other profiles render it (no database needed).
class SqlRenderingSpec extends AnyWordSpec with Matchers {

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

  class Items(t: Tag) extends Table[(Int, String, Option[String])](t, "items") {
    def id   = column[Int]("id", O.PrimaryKey)
    def name = column[String]("name")
    def tag  = column[Option[String]]("tag")
    def *    = (id, name, tag)
  }
  val items = TableQuery[Items]
  val env   = implicitly[CursorEnvironment[String]]

  /** The SQL of a page after a cursor with `values`, as `profile` renders it. */
  def sql(
      seeker: SlickSeeker[Items, (Int, String, Option[String]), String, ?, ?],
      profile: JdbcProfile,
      values: Seq[String],
      direction: CursorDirection = CursorDirection.Forward
  ): String = {
    val decoded = env.decodeWithDirectionOrThrow(Some(env.encode(values, direction)))
    val query   = seeker.pageQuery(decoded, 2, SeekSettings.of(profile))
    profile.api.streamableQueryActionExtensionMethods(query).result.statements.head
  }

  val byNameId = items.toSeeker.seek(_.name.asc).seek(_.id.asc)

  "Cursor pages" should {
    "write cursor values as literals on SQL Server, and bind them elsewhere" in {
      val sqlServer = sql(byNameId, SQLServerProfile, Seq("n1", "5"))
      sqlServer should include("'n1'")
      sqlServer should not include "?"
      sql(byNameId, PostgresProfile, Seq("n1", "5")) should include("""("name", "id") > (?, ?)""")
    }

    "give non-null columns no nulls clause, so no database emulates one on backward pages" in {
      val backward = Seq(MySQLProfile, SQLServerProfile, SQLiteProfile, PostgresProfile, HsqldbProfile)
        .map(profile => profile.toString -> sql(byNameId, profile, Seq("n1", "5"), CursorDirection.Backward))
      all(
        backward.map(_._2.toLowerCase)
      ) should (not include "is null" and not include "isnull" and not include "nulls")
    }

    "give non-null columns no nulls clause with seekDirection either" in {
      Seq(SortDirection.Asc, SortDirection.Desc).foreach { direction =>
        val seeker = byNameId.seekDirection(direction)
        Seq(CursorDirection.Forward, CursorDirection.Backward).foreach { cursorDirection =>
          all(Seq(MySQLProfile, SQLServerProfile, SQLiteProfile, PostgresProfile).map { profile =>
            sql(seeker, profile, Seq("n1", "5"), cursorDirection).toLowerCase
          }) should (not include "is null" and not include "isnull" and not include "nulls")
        }
      }
    }

    "write the database's default nulls placement out for Option columns" in {
      def order(profile: JdbcProfile, seeker: SlickSeeker[Items, (Int, String, Option[String]), String, ?, ?]) = {
        val s = sql(seeker, profile, Seq("St1", "5"))
        s.substring(s.toLowerCase.indexOf("order by"))
      }
      val asc  = items.toSeeker.seek(_.tag.asc).seek(_.id.asc)
      val desc = items.toSeeker.seek(_.tag.desc).seek(_.id.asc)
      order(PostgresProfile, asc) should include(""""tag" nulls last""")
      order(PostgresProfile, desc) should include(""""tag" desc nulls first""")
      order(HsqldbProfile, asc) should include(""""tag" nulls first""")
      order(HsqldbProfile, desc) should include(""""tag" desc nulls first""")
      // MySQL sorts nulls first ascending and last descending: its default needs no emulation
      order(MySQLProfile, asc) should not include "isnull"
      order(MySQLProfile, desc) should not include "isnull"
    }

    "fetch at most Int.MaxValue rows" in {
      SlickSeeker.normalizeLimit(Int.MaxValue, Int.MaxValue) shouldBe Int.MaxValue - 1
      SlickSeeker.normalizeLimit(0, 10) shouldBe 1
      SlickSeeker.normalizeLimit(50, 10) shouldBe 10
    }
  }
}
