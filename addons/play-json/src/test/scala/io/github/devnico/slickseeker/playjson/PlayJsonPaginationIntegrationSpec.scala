package io.github.devnico.slickseeker.playjson

import io.github.devnico.slickseeker.SlickSeekerSupport
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import slick.jdbc.H2Profile

import java.sql.Timestamp
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class PlayJsonPaginationIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global

  trait PlayJsonTestProfile extends H2Profile with SlickSeekerSupport with PlayJsonSeekerSupport {
    object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits
    override val api: MyApi.type = MyApi
  }

  object PlayJsonTestProfile extends PlayJsonTestProfile

  import PlayJsonTestProfile.api._

  case class Person(id: Int, firstName: String, lastName: Option[String], age: Int, createdAt: Timestamp)

  class Persons(tag: Tag) extends Table[Person](tag, "persons") {
    def id        = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def firstName = column[String]("first_name")
    def lastName  = column[Option[String]]("last_name")
    def age       = column[Int]("age")
    def createdAt = column[Timestamp]("created_at")

    def * = (id, firstName, lastName, age, createdAt).mapTo[Person]
  }

  val persons = TableQuery[Persons]

  val db = Database.forConfig("h2mem")

  val testData = Seq(
    Person(1, "Alice", Some("Anderson"), 25, new Timestamp(1000)),
    Person(2, "Bob", Some("Barnes"), 30, new Timestamp(2000)),
    Person(3, "Charlie", Some("Clark"), 35, new Timestamp(3000)),
    Person(4, "Diana", Some("Dove"), 28, new Timestamp(4000)),
    Person(5, "Eve", Some("Evans"), 32, new Timestamp(5000)),
    Person(6, "Frank", None, 45, new Timestamp(6000)),
    Person(7, "Grace", Some("Green"), 29, new Timestamp(7000)),
    Person(8, "Henry", Some("Hill"), 31, new Timestamp(8000)),
    Person(9, "Iris", None, 27, new Timestamp(9000)),
    Person(10, "Jack", Some("Jones"), 33, new Timestamp(10000))
  )

  override def beforeAll(): Unit = {
    val setup = DBIO.seq(
      persons.schema.dropIfExists,
      persons.schema.create,
      persons ++= testData
    )
    Await.result(db.run(setup), 5.seconds)
  }

  override def afterAll(): Unit =
    db.close()

  def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  "PlayJSON cursor codec" should {

    "paginate forward through multiple pages" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      page1.total shouldBe 10
      page1.items should have size 3
      page1.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
      page1.nextCursor shouldBe defined
      page1.prevCursor shouldBe None

      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      page2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")
      page2.prevCursor shouldBe defined
      page2.nextCursor shouldBe defined

      val page3 = await(db.run(seeker.page(limit = 3, cursor = page2.nextCursor)))
      page3.items.map(_.firstName) shouldBe Seq("Grace", "Henry", "Iris")
      page3.prevCursor shouldBe defined
      page3.nextCursor shouldBe defined

      val page4 = await(db.run(seeker.page(limit = 3, cursor = page3.nextCursor)))
      page4.items.map(_.firstName) shouldBe Seq("Jack")
      page4.nextCursor shouldBe None
    }

    "paginate backward through multiple pages" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      // Navigate to page 3
      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      val page3 = await(db.run(seeker.page(limit = 3, cursor = page2.nextCursor)))

      page3.prevCursor shouldBe defined

      // Navigate backward
      val backToPage2 = await(db.run(seeker.page(limit = 3, cursor = page3.prevCursor)))
      backToPage2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")
      backToPage2.items shouldBe page2.items

      val backToPage1 = await(db.run(seeker.page(limit = 3, cursor = backToPage2.prevCursor)))
      backToPage1.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
      backToPage1.items shouldBe page1.items
    }

    "handle String columns in cursor encoding without ClassCastException" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.lastName.nullsLast.asc)
        .seek(_.id.asc)

      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      page1.items should have size 3
      page1.nextCursor shouldBe defined

      // This should not throw ClassCastException when encoding String values
      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      page2.items should have size 3
    }

    "handle Int columns in cursor encoding" in {
      val seeker = persons.toSeeker
        .seek(_.age.desc)
        .seek(_.id.asc)

      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      page1.items should have size 3
      page1.items.head.age shouldBe 45 // Frank is oldest
      page1.nextCursor shouldBe defined

      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      page2.items should have size 3
    }

    "handle Option[String] columns with nullsLast" in {
      val seeker = persons.toSeeker
        .seek(_.lastName.nullsLast.asc)
        .seek(_.id.asc)

      val result = await(db.run(seeker.page(limit = 100, cursor = None)))

      // NULLs should be last
      val lastNames = result.items.map(_.lastName)
      val nullCount = lastNames.count(_.isEmpty)

      nullCount shouldBe 2 // Frank and Iris have NULL last names

      // NULLs should appear at the end
      lastNames.takeRight(2).foreach(_ shouldBe None)
    }

    "handle Option[String] columns with nullsFirst" in {
      val seeker = persons.toSeeker
        .seek(_.lastName.nullsFirst.asc)
        .seek(_.id.asc)

      val result = await(db.run(seeker.page(limit = 100, cursor = None)))

      // NULLs should be first
      val lastNames = result.items.map(_.lastName)

      // First two should be NULLs
      lastNames.take(2).foreach(_ shouldBe None)
    }

    "handle multiple columns with different types" in {
      val seeker = persons.toSeeker
        .seek(_.age.desc)
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      page1.items should have size 3
      page1.nextCursor shouldBe defined

      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      page2.items should have size 3
      page2.prevCursor shouldBe defined

      // Go back
      val backToPage1 = await(db.run(seeker.page(limit = 3, cursor = page2.prevCursor)))
      backToPage1.items shouldBe page1.items
    }

    "handle empty results" in {
      val seeker = persons.filter(_.age > 1000).toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val result = await(db.run(seeker.page(limit = 10, cursor = None)))

      result.total shouldBe 0
      result.items shouldBe empty
      result.nextCursor shouldBe None
      result.prevCursor shouldBe None
    }

    "handle single item" in {
      val seeker = persons.filter(_.firstName === "Alice").toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val result = await(db.run(seeker.page(limit = 10, cursor = None)))

      result.items.size should be <= 1
      if (result.items.nonEmpty) {
        result.items.head.firstName shouldBe "Alice"
      }
    }

    "handle limit larger than total" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val result = await(db.run(seeker.page(limit = 100, cursor = None)))

      result.total shouldBe 10
      result.items should have size 10
      result.nextCursor shouldBe None
    }

    "respect maxLimit parameter" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val result = await(db.run(seeker.page(limit = 1000, cursor = None, maxLimit = 5)))

      result.items should have size 5 // Clamped to maxLimit
    }

    "handle limit = 1" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val result = await(db.run(seeker.page(limit = 1, cursor = None)))

      result.items should have size 1
      result.items.head.firstName shouldBe "Alice"
      result.nextCursor shouldBe defined
    }

    "paginate forward with pageWithoutCount" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val page1 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = None)))
      page1.items should have size 3
      page1.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
      page1.nextCursor shouldBe defined
      page1.prevCursor shouldBe None

      val page2 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = page1.nextCursor)))
      page2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")
      page2.prevCursor shouldBe defined
    }

    "produce cursors interoperable between page and pageWithoutCount" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      // Cursor from pageWithoutCount works with page
      val withoutCount1 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = None)))
      val withCount2    = await(db.run(seeker.page(limit = 3, cursor = withoutCount1.nextCursor)))

      withCount2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")
      withCount2.total shouldBe 10

      // Cursor from page works with pageWithoutCount
      val withoutCount3 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = withCount2.nextCursor)))
      withoutCount3.items.map(_.firstName) shouldBe Seq("Grace", "Henry", "Iris")
    }

    "encode and decode cursors correctly through multiple pages" in {
      val seeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.age.desc)
        .seek(_.id.asc)

      var cursor: Option[String] = None
      var pageCount              = 0
      var totalItems             = 0

      // Iterate through all pages using cursors
      while (pageCount < 10) { // Safety limit
        val page = await(db.run(seeker.page(limit = 2, cursor = cursor)))
        if (page.items.isEmpty) {
          pageCount = 10 // Exit
        } else {
          totalItems += page.items.size
          cursor = page.nextCursor
          pageCount += 1
          if (cursor.isEmpty) {
            pageCount = 10 // Exit - no more pages
          }
        }
      }

      totalItems shouldBe 10 // Should have iterated through all items
    }
  }
}
