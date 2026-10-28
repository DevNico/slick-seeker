package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.support.MyHsqldbProfile
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyHsqldbProfile.api._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

// HSQLDB sorts nulls first in both directions: Option columns without a nulls clause keep that order.
class HsqldbSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val ec: ExecutionContext = ExecutionContext.global

  implicit val intCodec: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = value.toIntOption
  }
  implicit val optStrCodec: CursorValueCodec[Option[String], String] = new CursorValueCodec[Option[String], String] {
    def encode(value: Option[String]): String = value.fold("N")("S" + _)
    def decode(value: String): Option[Option[String]] =
      if (value == "N") Some(None) else if (value.startsWith("S")) Some(Some(value.drop(1))) else None
  }

  class Items(t: Tag) extends Table[(Int, Option[String])](t, "HSQL_ITEMS") {
    def id  = column[Int]("ID", O.PrimaryKey)
    def tag = column[Option[String]]("TAG")
    def *   = (id, tag)
  }
  val items = TableQuery[Items]
  val db    = Database.forURL("jdbc:hsqldb:mem:seeker", driver = "org.hsqldb.jdbc.JDBCDriver", keepAliveConnection = true)

  def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  override def beforeAll(): Unit =
    await(db.run(DBIO.seq(
      items.schema.create,
      items ++= (1 to 20).map(i => (i, Option.when(i % 3 != 0)(s"t${i % 4}")))
    )))
  override def afterAll(): Unit = db.close()

  type Config = SlickSeeker[Items, (Int, Option[String]), String, ?, ?]

  def forward(run: Option[String] => PaginatedResultWithoutCount[Int]): Vector[Int] = {
    @tailrec def go(cursor: Option[String], ids: Vector[Int]): Vector[Int] = {
      val page = run(cursor)
      if (page.nextCursor.isEmpty) ids ++ page.items else go(page.nextCursor, ids ++ page.items)
    }
    go(None, Vector.empty)
  }

  "Seekers on HSQLDB" should {
    Seq[(String, Config, Query[Items, (Int, Option[String]), Seq])](
      ("tag asc", items.toSeeker.seek(_.tag.asc).seek(_.id.asc), items.sortBy(r => (r.tag.asc, r.id.asc))),
      ("tag desc", items.toSeeker.seek(_.tag.desc).seek(_.id.asc), items.sortBy(r => (r.tag.desc, r.id.asc))),
      ("tag desc, id desc", items.toSeeker.seek(_.tag.desc).seek(_.id.desc), items.sortBy(r => (r.tag.desc, r.id.desc)))
    ).foreach { case (name, seeker, native) =>
      s"page every row once in the database's own order: $name" in {
        val expected = await(db.run(native.map(_.id).result))
        val page     = CompiledSeekPage[Unit]((_: Unit) => seeker)(_.id)
        forward(c => await(db.run(seeker.pageWithoutCountAs(3, c)(_.id)))) shouldBe expected
        forward(c => await(db.run(page.pageWithoutCount((), 3, c)))) shouldBe expected
      }
    }

    "accept a maxLimit of Int.MaxValue" in {
      val seeker = items.toSeeker.seek(_.id.asc)
      await(db.run(seeker.pageWithoutCountAs(Int.MaxValue, None, Int.MaxValue)(_.id))).items should have size 20
      val page = CompiledSeekPage[Unit]((_: Unit) => seeker)(_.id, maxLimit = Int.MaxValue)
      await(db.run(page.pageWithoutCount((), Int.MaxValue, None))).items should have size 20
    }
  }
}
