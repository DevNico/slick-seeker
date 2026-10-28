package io.github.devnico.slickseeker.databases

import io.github.devnico.slickseeker.pagination.SlickSeeker
import org.scalatest.exceptions.TestFailedException

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZoneOffset}
import java.util.UUID

final case class SqlServerRow(
    id: Int,
    vc: String,
    nvc: String,
    ch: String,
    nch: String,
    dt: LocalDateTime,
    sdt: LocalDateTime,
    dt2: LocalDateTime,
    d: LocalDate,
    t: LocalTime,
    dto: OffsetDateTime,
    num: BigDecimal,
    mny: BigDecimal,
    rl: Float,
    fl: Double,
    tiny: Short,
    bt: Boolean,
    uid: UUID,
    ovc: Option[String],
    onvc: Option[String],
    odt: Option[LocalDateTime]
)

object SqlServerTypesSchema {
  val varchars: Vector[String] =
    Vector("a-c", "ab", "a-b", "ac", "b-a", "-a", "aa", "co-op", "coop", "cop", "a'b", "a b", "ba", "b-b", "-b", "bb")
  val nvarchars: Vector[String] =
    Vector("Иван", "Борис", "Анна", "Дмитрий", "Ольга", "Пётр", "東京", "大阪", "京都", "名古屋", "a-b", "ab", "😀", "Ω")
  val base: LocalDateTime = LocalDateTime.of(2020, 1, 1, 10, 0, 0)
  val uuids: Vector[UUID] = Vector.tabulate(7)(i => UUID.nameUUIDFromBytes(Array(i.toByte)))

  val rows: Seq[SqlServerRow] = (1 to 40).map { i =>
    SqlServerRow(
      id = i,
      vc = varchars(i % varchars.size),
      nvc = nvarchars(i % nvarchars.size),
      ch = varchars(i % 7),
      nch = nvarchars(i % 9),
      // datetime keeps 1/300 s: .003 is stored as .00333…, which a datetime2 parameter of .003 doesn't equal
      dt = base.plusNanos(Seq(0, 3, 7, 10, 13, 17)(i % 6) * 1000000L).plusSeconds((i % 4).toLong),
      sdt = base.plusMinutes((i % 5).toLong),
      dt2 = base.plusNanos((i % 9) * 100L),
      d = base.toLocalDate.plusDays((i % 5).toLong),
      // Slick reads time and datetimeoffset as text, failing on 100 ns digits: (6), its default
      t = LocalTime.of(10, 0).plusNanos((i % 8) * 1000L),
      // The same instants in other offsets compare equal
      dto = base.atOffset(ZoneOffset.UTC).plusMinutes((i % 3).toLong).withOffsetSameInstant(ZoneOffset.ofHours(i % 4)),
      num = BigDecimal(i % 7 - 3) / 7,
      mny = BigDecimal(i % 5) * BigDecimal("1.0001"),
      rl = (i   % 7) / 10f,
      fl = (i   % 7) / 10d,
      tiny = (i % 6 * 50).toShort,
      bt = i    % 3 == 0,
      uid = uuids(i % uuids.size),
      ovc = if (i % 4 == 0) None else Some(varchars(i % varchars.size)),
      onvc = if (i % 5 == 0) None else Some(nvarchars(i % 11)),
      odt = if (i % 3 == 0) None else Some(base.plusNanos(Seq(0, 3, 7)(i % 3) * 1000000L))
    )
  }
}

/** SQL Server's column types, each declared with `O.SqlType`, paged with `profile`. */
final class SqlServerTypesSchema(database: TestDatabase, profile0: MatrixProfile)
    extends SeekSchema(database, profile0) {
  import profile.api._
  import SqlServerTypesSchema._

  class Rows(tag: Tag) extends Table[SqlServerRow](tag, "seek_sqlserver_types") {
    def id   = column[Int]("id", O.PrimaryKey)
    def vc   = column[String]("vc", O.SqlType("VARCHAR(30)"))
    def nvc  = column[String]("nvc", O.SqlType("NVARCHAR(30)"))
    def ch   = column[String]("ch", O.SqlType("CHAR(8)"))
    def nch  = column[String]("nch", O.SqlType("NCHAR(8)"))
    def dt   = column[LocalDateTime]("dt", O.SqlType("DATETIME"))
    def sdt  = column[LocalDateTime]("sdt", O.SqlType("SMALLDATETIME"))
    def dt2  = column[LocalDateTime]("dt2", O.SqlType("DATETIME2(7)"))
    def d    = column[LocalDate]("d", O.SqlType("DATE"))
    def t    = column[LocalTime]("t", O.SqlType("TIME(6)"))
    def dto  = column[OffsetDateTime]("dto", O.SqlType("DATETIMEOFFSET(6)"))
    def num  = column[BigDecimal]("num", O.SqlType("DECIMAL(18,6)"))
    def mny  = column[BigDecimal]("mny", O.SqlType("MONEY"))
    def rl   = column[Float]("rl", O.SqlType("REAL"))
    def fl   = column[Double]("fl", O.SqlType("FLOAT"))
    def tiny = column[Short]("tiny", O.SqlType("TINYINT"))
    def bt   = column[Boolean]("bt", O.SqlType("BIT"))
    def uid  = column[UUID]("uid", O.SqlType("UNIQUEIDENTIFIER"))
    def ovc  = column[Option[String]]("ovc", O.SqlType("VARCHAR(30)"))
    def onvc = column[Option[String]]("onvc", O.SqlType("NVARCHAR(30)"))
    def odt  = column[Option[LocalDateTime]]("odt", O.SqlType("DATETIME"))
    def * = (id, vc, nvc, ch, nch, dt, sdt, dt2, d, t, dto, num, mny, rl, fl, tiny, bt, uid, ovc, onvc, odt)
      .mapTo[SqlServerRow]
  }
  val table = TableQuery[Rows]

  def setUp: DBIO[Unit] = DBIO.seq(table.schema.create, table ++= rows)

  def ids: Seq[Int] = rows.map(_.id)

  private def on(name: String)(seeker: Query[Rows, SqlServerRow, Seq] => SlickSeeker[
    Rows,
    SqlServerRow,
    String,
    ?,
    ?
  ]) =
    seekCase(name, seeker(table))(_.id, _.id)

  def cases: Seq[SeekCase] = Seq(
    on("varchar, id")(_.toSeeker.seek(_.vc.asc).seek(_.id.asc)),
    on("varchar desc, id desc")(_.toSeeker.seek(_.vc.desc).seek(_.id.desc)),
    on("nvarchar, id")(_.toSeeker.seek(_.nvc.asc).seek(_.id.asc)),
    on("char, id")(_.toSeeker.seek(_.ch.asc).seek(_.id.asc)),
    on("nchar desc, id")(_.toSeeker.seek(_.nch.desc).seek(_.id.asc)),
    on("datetime, id")(_.toSeeker.seek(_.dt.asc).seek(_.id.asc)),
    on("smalldatetime desc, id")(_.toSeeker.seek(_.sdt.desc).seek(_.id.asc)),
    on("datetime2(7), id")(_.toSeeker.seek(_.dt2.asc).seek(_.id.asc)),
    on("date desc, time, id")(_.toSeeker.seek(_.d.desc).seek(_.t.asc).seek(_.id.asc)),
    on("datetimeoffset(6), id")(_.toSeeker.seek(_.dto.asc).seek(_.id.asc)),
    on("decimal, money desc, id")(_.toSeeker.seek(_.num.asc).seek(_.mny.desc).seek(_.id.asc)),
    on("real, float desc, id")(_.toSeeker.seek(_.rl.asc).seek(_.fl.desc).seek(_.id.asc)),
    on("tinyint, bit desc, id")(_.toSeeker.seek(_.tiny.asc).seek(_.bt.desc).seek(_.id.asc)),
    on("uniqueidentifier, id")(_.toSeeker.seek(_.uid.asc).seek(_.id.asc)),
    on("Option varchar (default nulls), id")(_.toSeeker.seek(_.ovc.asc).seek(_.id.asc)),
    on("Option nvarchar desc nullsFirst, id")(_.toSeeker.seek(_.onvc.desc.nullsFirst).seek(_.id.asc)),
    on("Option datetime nullsLast, id desc")(_.toSeeker.seek(_.odt.asc.nullsLast).seek(_.id.desc)),
    on("mixed: varchar, datetime desc, nvarchar, id")(
      _.toSeeker.seek(_.vc.asc).seek(_.dt.desc).seek(_.nvc.asc).seek(_.id.asc)
    ),
    on("lower(nvarchar), upper(varchar) desc, id")(
      _.toSeeker.seek(_.nvc.toLowerCase.asc).seek(_.vc.toUpperCase.desc).seek(_.id.asc)
    ),
    seekCase(
      "mapped tuples: nvarchar, datetime, id",
      table.map(r => (r.id, (r.nvc, r.dt))).toSeeker.seek(_._2._1.asc).seek(_._2._2.desc).seek(_._1.asc)
    )(_._1, _._1)
  )
}

// Also SQL Server's own column types, other driver settings and a control without casts.
class SqlServerDatabaseSpec
    extends DatabaseSpec(
      TestDatabase.SqlServer,
      d => Seq(new MatrixSchema(d), new SqlServerTypesSchema(d, d.profile))
    ) {

  // Same tables as the suite's schema (not set up again)
  lazy val withoutCasts = new SqlServerTypesSchema(TestDatabase.SqlServer, MatrixProfile.SqlServerWithoutCasts)
  lazy val types        = new SqlServerTypesSchema(TestDatabase.SqlServer, TestDatabase.SqlServer.profile)

  def typeCase(schema: SeekSchema, name: String): SeekCase = schema.cases.find(_.name == name).get

  if (TestDatabase.SqlServer.selected) {
    "SQL Server" should {
      // Strings sent as varchar: varchar, char and datetime columns still page right (nvarchar values outside the code
      // page are lost before any cast, so nvarchar columns need the default)
      Seq("varchar, id", "varchar desc, id desc", "char, id", "datetime, id").foreach { name =>
        s"page with sendStringParametersAsUnicode=false: $name" in {
          checkCase(typeCase(types, name).copy(name = name), open(";sendStringParametersAsUnicode=false"))
        }
      }

      // What the casts fix: the driver's nvarchar and datetime2 parameters compare unlike the columns sort
      Seq("varchar, id", "datetime, id").foreach { name =>
        s"skip or repeat rows without casts: $name" in {
          a[TestFailedException] should be thrownBy checkCase(typeCase(withoutCasts, name))
        }
      }
    }
  }
}
