package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.filter.ColumnSeekFilterTypes.FilterCond
import io.github.devnico.slickseeker.filter.{ColumnSeekFilter, SeekerSortKey}
import io.github.devnico.slickseeker.support.{MyH2Profile, SlickEquals}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._
import slick.ast.Ordering
import slick.lifted.ColumnOrdered

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

// The custom sort key of the docs (concepts.md, Custom Filters and Sort Keys), compiled and paged.
class DocsExampleSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with SlickEquals {
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val intCodec: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = value.toIntOption
  }

  class Users(t: Tag) extends Table[(Int, String)](t, "docs_users") {
    def id   = column[Int]("id", O.PrimaryKey)
    def name = column[String]("name")
    def *    = (id, name)
  }
  val users = TableQuery[Users]
  val db    = Database.forConfig("h2mem")

  override def beforeAll(): Unit = Await.result(
    db.run(DBIO.seq(users.schema.dropIfExists, users.schema.create, users ++= (1 to 9).map(i => (i, "x" * (10 - i))))),
    10.seconds
  )
  override def afterAll(): Unit = db.close()

  val byLength: SeekerSortKey[String, Int, String] = new SeekerSortKey[String, Int, String] {
    private val ints                                           = MyH2Profile.api.seekerSortKeyIdentity[Int, String]
    def mapCol(col: ColumnOrdered[String]): ColumnOrdered[Int] = ColumnOrdered(col.column.length, col.ord)
    def shape                                                  = ints.shape
    def codec                                                  = ints.codec
    def typedType                                              = ints.typedType
    def filter = new ColumnSeekFilter[Int] {
      def apply(column: ColumnOrdered[Int], value: Rep[Int], valueIsNull: Boolean, prevCond: FilterCond) =
        if (column.ord.direction == Ordering.Asc) column.column > value || (column.column === value && prevCond)
        else column.column < value || (column.column === value && prevCond)
    }
  }

  "The docs' custom sort key" should {
    "page by name length, ad-hoc and compiled" in {
      val seeker   = users.toSeeker.seek(_.name.asc)(byLength).seek(_.id.asc)
      val compiled = CompiledSeekPage[Unit]((_: Unit) => seeker)(_.id)
      val first    = Await.result(db.run(seeker.pageWithoutCountAs(4, None)(_.id)), 10.seconds)
      first.items shouldBe Seq(9, 8, 7, 6)
      Await.result(db.run(compiled.pageWithoutCount((), 4, first.nextCursor)), 10.seconds).items shouldBe Seq(
        5,
        4,
        3,
        2
      )
    }
  }
}
