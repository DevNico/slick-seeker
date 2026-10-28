package io.github.devnico.slickseeker.databases

import io.github.devnico.slickseeker.filter.SeekOrder
import io.github.devnico.slickseeker.pagination.SlickSeeker
import io.github.devnico.slickseeker.support.SortDirection

import java.time.{LocalDate, LocalDateTime}

sealed trait Stage
object Stage {
  case object Draft     extends Stage
  case object Review    extends Stage
  case object Published extends Stage
  val all: Seq[Stage] = Seq(Draft, Review, Published)
}

final case class MatrixItem(
    id: Int,
    grp: Int,
    name: String,
    uname: String,
    tag: Option[String],
    at: LocalDateTime,
    legacyAt: LocalDateTime,
    amount: BigDecimal,
    weight: Float,
    score: Option[Int],
    day: LocalDate,
    active: Boolean,
    stage: Stage,
    ownerId: Int
)

object MatrixSchema {
  // Strings that sort differently by collation: hyphens and apostrophes (ignored or not), case, accents, spaces
  val names: Vector[String] = Vector(
    "co-op",
    "coop",
    "cop",
    "co op",
    "can't",
    "cant",
    "ab",
    "a-c",
    "a-b",
    "aa",
    "a'd",
    "ad",
    "b-a",
    "ba",
    "Ab",
    "AB",
    "ab ",
    "é",
    "e",
    "f",
    "Z",
    "z",
    "_x",
    "%y",
    "10",
    "9",
    "-b"
  )
  // Outside every single-byte code page: needs nvarchar on SQL Server
  val unames: Vector[String] =
    Vector("Иван", "Борис", "Анна", "東京", "大阪", "京都", "Ölga", "øre", "Zoë", "😀", "ß", "ss", "ẞ", "Ω", "ω", "日本")
  val tags: Vector[String]       = Vector("t-1", "t1", "T1", "t 1", "t'1")
  val owners: Seq[(Int, String)] = Seq(1 -> "Zed", 2 -> "alice", 3 -> "Bob", 4 -> "émile")

  val base: LocalDateTime = LocalDateTime.of(2024, 2, 29, 23, 59, 59)

  val items: Seq[MatrixItem] = (1 to 40).map { i =>
    MatrixItem(
      id = i,
      grp = i % 3,
      name = names(i % names.size),
      uname = unames(i % unames.size),
      tag = if (i % 3 == 0) None else Some(tags(i % tags.size)),
      // Sub-millisecond steps, with ties
      at = base.plusNanos((i % 11) * 150000L),
      // Steps SQL Server's datetime keeps (1/300 s ticks shown as .003, .007, …)
      legacyAt = base.plusNanos(Seq(0, 3, 7, 10, 13, 17, 20)(i % 7) * 1000000L),
      amount = BigDecimal(i % 9 - 4) * BigDecimal("0.3333"),
      // Not exact in binary: 0.1f, 0.2f, …
      weight = (i % 7) / 10f,
      score = if (i % 4 == 0) None else Some(i % 3),
      day = base.toLocalDate.plusDays((i % 6).toLong),
      active = i % 2 == 0,
      stage = Stage.all(i % 3),
      ownerId = 1 + i % 4
    )
  }
}

/** The tables, rows and seeker configurations every database pages (with `profile`, by default the database's). */
final class MatrixSchema(database: TestDatabase, profile0: MatrixProfile) extends SeekSchema(database, profile0) {
  def this(database: TestDatabase) = this(database, database.profile)

  import profile.api._
  import MatrixSchema._

  implicit val stageType: BaseColumnType[Stage] = MappedColumnType.base[Stage, String](
    s => s.toString.toLowerCase,
    s => Stage.all.find(_.toString.toLowerCase == s).get
  )
  implicit val stageOrder: SeekOrder[Stage] = SeekOrder(IndexedSeq(Stage.Review, Stage.Draft, Stage.Published))

  class Owners(t: Tag) extends Table[(Int, String)](t, "seek_owners") {
    def id   = column[Int]("id", O.PrimaryKey)
    def name = column[String]("name", declared("name", O.Length(40)): _*)
    def *    = (id, name)
  }
  val ownersTable = TableQuery[Owners]

  class Items(t: Tag) extends Table[MatrixItem](t, "seek_items") {
    def id       = column[Int]("id", O.PrimaryKey)
    def grp      = column[Int]("grp")
    def name     = column[String]("name", declared("name", O.Length(40)): _*)
    def uname    = column[String]("uname", declared("uname", O.Length(40)): _*)
    def tag      = column[Option[String]]("tag", declared("tag", O.Length(20)): _*)
    def at       = column[LocalDateTime]("at", declared("at"): _*)
    def legacyAt = column[LocalDateTime]("legacy_at", declared("legacy_at"): _*)
    def amount   = column[BigDecimal]("amount", declared("amount", O.SqlType("DECIMAL(12,4)")): _*)
    def weight   = column[Float]("weight")
    def score    = column[Option[Int]]("score")
    def day      = column[LocalDate]("day")
    def active   = column[Boolean]("active", declared("active"): _*)
    def stage    = column[Stage]("stage", O.Length(10))
    def ownerId  = column[Int]("owner_id")
    def * =
      (id, grp, name, uname, tag, at, legacyAt, amount, weight, score, day, active, stage, ownerId).mapTo[MatrixItem]
  }
  val itemsTable = TableQuery[Items]

  def setUp: DBIO[Unit] =
    DBIO.seq(
      (ownersTable.schema ++ itemsTable.schema).create,
      ownersTable ++= owners,
      itemsTable ++= items
    )

  def ids: Seq[Int] = items.map(_.id)

  private type ItemSeeker = SlickSeeker[Items, MatrixItem, String, ?, ?]
  private def onItems(name: String, rows: Seq[Int] = ids)(seeker: Query[Items, MatrixItem, Seq] => ItemSeeker) =
    seekCase(name, seeker(itemsTable), rows)(_.id, _.id)

  def cases: Seq[SeekCase] = Seq(
    onItems("name, id")(_.toSeeker.seek(_.name.asc).seek(_.id.asc)),
    onItems("name desc, id desc")(_.toSeeker.seek(_.name.desc).seek(_.id.desc)),
    onItems("uname (unicode), id")(_.toSeeker.seek(_.uname.asc).seek(_.id.asc)),
    onItems("tag (default nulls), id")(_.toSeeker.seek(_.tag.asc).seek(_.id.asc)),
    onItems("tag desc (default nulls), id desc")(_.toSeeker.seek(_.tag.desc).seek(_.id.desc)),
    onItems("tag nullsLast, id")(_.toSeeker.seek(_.tag.asc.nullsLast).seek(_.id.asc)),
    onItems("tag desc nullsLast, score nullsFirst, id")(
      _.toSeeker.seek(_.tag.desc.nullsLast).seek(_.score.asc.nullsFirst).seek(_.id.asc)
    ),
    onItems("at (sub-ms), id")(_.toSeeker.seek(_.at.asc).seek(_.id.asc)),
    onItems("legacyAt desc, id")(_.toSeeker.seek(_.legacyAt.desc).seek(_.id.asc)),
    onItems("amount, grp desc, id")(_.toSeeker.seek(_.amount.asc).seek(_.grp.desc).seek(_.id.asc)),
    onItems("weight (REAL), id")(_.toSeeker.seek(_.weight.asc).seek(_.id.asc)),
    onItems("score desc (default nulls), amount, id")(
      _.toSeeker.seek(_.score.desc).seek(_.amount.asc).seek(_.id.asc)
    ),
    onItems("day desc, at, id")(_.toSeeker.seek(_.day.desc).seek(_.at.asc).seek(_.id.asc)),
    onItems("active desc, name, id")(_.toSeeker.seek(_.active.desc).seek(_.name.asc).seek(_.id.asc)),
    onItems("stage (SeekOrder), id")(_.toSeeker.seek(_.stage.asc).seek(_.id.asc)),
    onItems("lower(name), id")(_.toSeeker.seek(_.name.toLowerCase.asc).seek(_.id.asc)),
    onItems("lower(uname) desc, id")(_.toSeeker.seek(_.uname.toLowerCase.desc).seek(_.id.asc)),
    onItems("id + 0 desc")(_.toSeeker.seek(i => (i.id + 0).desc)),
    onItems("seekDirection desc: tag, score, id")(
      _.toSeeker.seek(_.tag).seek(_.score).seek(_.id).seekDirection(SortDirection.Desc)
    ),
    onItems("seekDirection asc: tag desc, uname, id")(
      _.toSeeker.seek(_.tag.desc).seek(_.uname.asc).seek(_.id.asc).seekDirection(SortDirection.Asc)
    ),
    onItems("filtered: name, id", items.filter(_.grp != 1).map(_.id))(
      _.filter(_.grp =!= 1).toSeeker.seek(_.name.asc).seek(_.id.asc)
    ),
    seekCase(
      "join: owner name, item name desc, item id",
      itemsTable
        .join(ownersTable)
        .on(_.ownerId === _.id)
        .toSeeker
        .seek(_._2.name.asc)
        .seek(_._1.name.desc)
        .seek(_._1.id.asc)
    )(_._1.id, _._1.id),
    seekCase(
      "mapped tuples: uname, legacyAt, id",
      itemsTable.map(i => (i.id, i.uname, i.legacyAt)).toSeeker.seek(_._2.asc).seek(_._3.desc).seek(_._1.asc)
    )(_._1, _._1)
  )
}
