package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.support.{MyH2Profile, SortDirection}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._
import slick.jdbc.{HsqldbProfile, JdbcProfile, MySQLProfile, PostgresProfile, SQLServerProfile, SQLiteProfile}

import java.time.LocalDateTime

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

  implicit val dateTimeCodec: CursorValueCodec[LocalDateTime, String] = new CursorValueCodec[LocalDateTime, String] {
    def encode(value: LocalDateTime): String         = value.toString
    def decode(value: String): Option[LocalDateTime] = scala.util.Try(LocalDateTime.parse(value)).toOption
  }

  class Items(t: Tag) extends Table[(Int, String, Option[String], String, LocalDateTime)](t, "items") {
    def id    = column[Int]("id", O.PrimaryKey)
    def name  = column[String]("name")
    def tag   = column[Option[String]]("tag")
    def uname = column[String]("uname", O.SqlType("NVARCHAR(40)"))
    def at    = column[LocalDateTime]("at", O.SqlType("DATETIME"))
    def *     = (id, name, tag, uname, at)
  }
  val at    = "2020-01-01T10:00:00.003"
  val items = TableQuery[Items]
  val env   = implicitly[CursorEnvironment[String]]

  /** The SQL of a page after a cursor with `values`, as `profile` renders it. */
  def sql(
      seeker: SlickSeeker[Items, (Int, String, Option[String], String, LocalDateTime), String, ?, ?],
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
    "bind cursor values, cast on SQL Server to the type family their column declares" in {
      val sqlServer = sql(byNameId, SQLServerProfile, Seq("n1", "5"))
      sqlServer should include(""""name" > cast(? as VARCHAR(MAX))""")
      sqlServer should include(""""id" > ?""")
      sqlServer should not include "'n1'"
      val declared =
        sql(items.toSeeker.seek(_.uname.asc).seek(_.at.desc).seek(_.id.asc), SQLServerProfile, Seq("n", at, "5"))
      declared should include(""""uname" > cast(? as NVARCHAR(MAX))""")
      declared should include(""""at" < cast(? as DATETIME)""")
      // Expressions: the type of their column
      sql(items.toSeeker.seek(_.uname.toLowerCase.asc).seek(_.id.asc), SQLServerProfile, Seq("n", "5")) should include(
        "cast(? as NVARCHAR(MAX))"
      )
      sql(byNameId, PostgresProfile, Seq("n1", "5")) should include("""("name", "id") > (?, ?)""")
      all(
        Seq(PostgresProfile, MySQLProfile, SQLiteProfile).map(sql(byNameId, _, Seq("n1", "5")))
      ) should not include "cast("
    }

    "cast SQL Server's varchar, nvarchar, char, nchar, datetime and smalldatetime cursor values only" in {
      def cast(sqlType: String) = SlickSeekerSupport.defaultCursorValueCast(SQLServerProfile, sqlType)
      cast("VARCHAR(MAX)") shouldBe Some("VARCHAR(MAX)")
      cast("varchar(40)") shouldBe Some("VARCHAR(MAX)")
      cast("CHAR(8)") shouldBe Some("VARCHAR(MAX)")
      cast("NVARCHAR(40)") shouldBe Some("NVARCHAR(MAX)")
      cast("nchar(8)") shouldBe Some("NVARCHAR(MAX)")
      cast("datetime") shouldBe Some("DATETIME")
      cast("SMALLDATETIME") shouldBe Some("SMALLDATETIME")
      Seq("DATETIME2(6)", "DATETIMEOFFSET(6)", "INTEGER", "DECIMAL(21,2)", "UNIQUEIDENTIFIER", "TEXT").map(
        cast
      ) shouldBe
        Seq.fill(6)(None)
      SlickSeekerSupport.defaultCursorValueCast(PostgresProfile, "VARCHAR(40)") shouldBe None
    }

    "select cursor columns as expressions on SQL Server only, so a seek column can also be selected" in {
      def select(profile: JdbcProfile) =
        profile.api
          .streamableQueryActionExtensionMethods(byNameId.qwc.projectAs(items, SeekSettings.of(profile))(_.id))
          .result
          .statements
          .head
      select(SQLServerProfile) should startWith(
        """select "id", (case when (1 = 1) then "id" else "id" end), (case when (1 = 1) then "name" else "name" end)"""
      )
      select(PostgresProfile) should startWith("""select "id", "id", "name"""")
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

    "keep explicit nulls with seekDirection unless it reverses the direction" in {
      def order(seeker: SlickSeeker[Items, (Int, String, Option[String], String, LocalDateTime), String, ?, ?]) = {
        val s = sql(seeker, PostgresProfile, Seq("St1", "5"))
        s.substring(s.toLowerCase.indexOf("order by"))
      }
      val descFirst = items.toSeeker.seek(_.tag.desc.nullsFirst).seek(_.id.asc)
      order(descFirst.seekDirection(SortDirection.Desc)) should include(""""tag" desc nulls first""")
      order(descFirst.seekDirection(SortDirection.Asc)) should include(""""tag" nulls last""")
      val ascFirst = items.toSeeker.seek(_.tag.asc.nullsFirst).seek(_.id.asc)
      order(ascFirst.seekDirection(SortDirection.Asc)) should include(""""tag" nulls first""")
      order(ascFirst.seekDirection(SortDirection.Desc)) should include(""""tag" desc nulls last""")
    }

    "write the database's default nulls placement out for Option columns" in {
      def order(
          profile: JdbcProfile,
          seeker: SlickSeeker[Items, (Int, String, Option[String], String, LocalDateTime), String, ?, ?]
      ) = {
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
