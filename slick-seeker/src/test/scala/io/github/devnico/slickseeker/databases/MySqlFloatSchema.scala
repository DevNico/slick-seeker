package io.github.devnico.slickseeker.databases

final case class FloatRow(id: Int, single: Float, singleAsDouble: Double, optSingle: Option[Float])

object MySqlFloatSchema {
  // Not exact in binary, more than 6 significant digits, negative and zero, with ties
  val rows: Seq[FloatRow] = (1 to 40).map { i =>
    FloatRow(
      id = i,
      single = (i         % 9 - 4) / 7f,
      singleAsDouble = (i % 7) / 10d,
      optSingle = if (i % 4 == 0) None else Some((i % 5) / 3f)
    )
  }
}

/** Single-precision `FLOAT` columns on MySQL and MariaDB (Slick declares `Float` as `REAL`, which is `DOUBLE` there),
  * inserted both in a batch and one by one (MariaDB Connector/J sends them differently), paged with `profile`.
  */
final class MySqlFloatSchema(database: TestDatabase, profile0: MatrixProfile) extends SeekSchema(database, profile0) {
  import profile.api._
  import MySqlFloatSchema._

  class Rows(tag: Tag) extends Table[FloatRow](tag, "seek_mysql_floats") {
    def id             = column[Int]("id", O.PrimaryKey)
    def single         = column[Float]("single", O.SqlType("FLOAT"))
    def singleAsDouble = column[Double]("single_as_double", O.SqlType("FLOAT"))
    def optSingle      = column[Option[Float]]("opt_single", O.SqlType("FLOAT"))
    def *              = (id, single, singleAsDouble, optSingle).mapTo[FloatRow]
  }
  val table = TableQuery[Rows]

  def setUp: DBIO[Unit] =
    DBIO.seq(table.schema.create, table ++= rows.take(30), DBIO.sequence(rows.drop(30).map(table += _)))

  def ids: Seq[Int] = rows.map(_.id)

  def cases: Seq[SeekCase] = Seq(
    seekCase("FLOAT, id", table.toSeeker.seek(_.single.asc).seek(_.id.asc))(_.id, _.id),
    seekCase("FLOAT read as Double desc, id", table.toSeeker.seek(_.singleAsDouble.desc).seek(_.id.asc))(_.id, _.id),
    seekCase("Option FLOAT nullsLast, id desc", table.toSeeker.seek(_.optSingle.asc.nullsLast).seek(_.id.desc))(
      _.id,
      _.id
    ),
    seekCase("FLOAT * 2, id", table.toSeeker.seek(r => (r.single * 2f).asc).seek(_.id.asc))(_.id, _.id)
  )
}
