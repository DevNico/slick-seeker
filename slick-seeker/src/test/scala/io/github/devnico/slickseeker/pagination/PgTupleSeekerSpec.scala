package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.support.MyH2Profile
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import java.sql.Timestamp

class PgTupleSeekerSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global

  case class StringValueCodec[T]() extends CursorValueCodec[T, String] {
    def encode(value: T): String = value.toString
    def decode(value: String): Option[T] = scala.util.Try {
      value match {
        case _ if value.isEmpty => value.asInstanceOf[T]
        case _                  => value.asInstanceOf[T]
      }
    }.toOption
  }

  implicit val stringIntCodec: CursorValueCodec[Int, String]             = StringValueCodec[Int]()
  implicit val stringStringCodec: CursorValueCodec[String, String]       = StringValueCodec[String]()
  implicit val stringTimestampCodec: CursorValueCodec[Timestamp, String] = StringValueCodec[Timestamp]()

  case class Person(id: Int, firstName: String, age: Int, createdAt: Timestamp)

  class Persons(tag: Tag) extends Table[Person](tag, "persons_pgtuple") {
    def id        = column[Int]("id", O.PrimaryKey)
    def firstName = column[String]("first_name")
    def age       = column[Int]("age")
    def createdAt = column[Timestamp]("created_at")

    def * = (id, firstName, age, createdAt).mapTo[Person]
  }

  val persons = TableQuery[Persons]

  val db = Database.forConfig("h2postgres")

  val testData = Seq(
    Person(1, "Alice", 30, Timestamp.valueOf("2023-01-01 10:00:00")),
    Person(2, "Bob", 25, Timestamp.valueOf("2023-01-02 10:00:00")),
    Person(3, "Charlie", 35, Timestamp.valueOf("2023-01-03 10:00:00")),
    Person(4, "Diana", 28, Timestamp.valueOf("2023-01-04 10:00:00")),
    Person(5, "Eve", 32, Timestamp.valueOf("2023-01-05 10:00:00")),
    Person(6, "Frank", 27, Timestamp.valueOf("2023-01-06 10:00:00")),
    Person(7, "Grace", 31, Timestamp.valueOf("2023-01-07 10:00:00")),
    Person(8, "Henry", 29, Timestamp.valueOf("2023-01-08 10:00:00")),
    Person(9, "Iris", 33, Timestamp.valueOf("2023-01-09 10:00:00")),
    Person(10, "Jack", 26, Timestamp.valueOf("2023-01-10 10:00:00"))
  )

  override def beforeAll(): Unit = {
    val setup = DBIO.seq(
      persons.schema.create,
      persons ++= testData
    )
    Await.result(db.run(setup), 5.seconds)
  }

  override def afterAll(): Unit =
    db.close()

  def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  "SlickPgTupleSeeker with ASC direction" should {

    "work with all non-nullable columns in ASC mode" in {
      val seeker = persons.toPgTupleSeekerAsc
        .seek(_.firstName) // No .asc needed - enforced by type
        .seek(_.age)
        .seek(_.id)

      val result = await(db.run(seeker.page(limit = 3, cursor = None)))

      result.items should have size 3
      result.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
    }

    "paginate forward with cursor" in {
      val seeker = persons.toPgTupleSeekerAsc
        .seek(_.firstName)
        .seek(_.id)

      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      page1.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
      page1.nextCursor shouldBe defined

      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      page2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")
    }

    "paginate backward with cursor" in {
      val seeker = persons.toPgTupleSeekerAsc
        .seek(_.firstName)
        .seek(_.id)

      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      val page3 = await(db.run(seeker.page(limit = 3, cursor = page2.nextCursor)))

      val back2 = await(db.run(seeker.page(limit = 3, cursor = page3.prevCursor)))
      back2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")

      val back1 = await(db.run(seeker.page(limit = 3, cursor = back2.prevCursor)))
      back1.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
    }
  }

  "SlickPgTupleSeeker with DESC direction" should {

    "work with all non-nullable columns in DESC mode" in {
      val seeker = persons.toPgTupleSeekerDesc
        .seek(_.age) // DESC enforced by type
        .seek(_.id)

      val result = await(db.run(seeker.page(limit = 3, cursor = None)))

      result.items should have size 3
      result.items.map(_.age) shouldBe Seq(35, 33, 32)
    }

    "paginate forward and backward" in {
      val seeker = persons.toPgTupleSeekerDesc
        .seek(_.firstName)
        .seek(_.id)

      val page1 = await(db.run(seeker.page(limit = 3, cursor = None)))
      page1.items.map(_.firstName) shouldBe Seq("Jack", "Iris", "Henry")

      val page2 = await(db.run(seeker.page(limit = 3, cursor = page1.nextCursor)))
      page2.items.map(_.firstName) shouldBe Seq("Grace", "Frank", "Eve")
    }
  }

  "SlickPgTupleSeeker type safety" should {

    "produce same results as standard SlickSeeker" in {
      val standardSeeker = persons.toSeeker
        .seek(_.firstName.asc)
        .seek(_.id.asc)

      val pgTupleSeeker = persons.toPgTupleSeekerAsc
        .seek(_.firstName)
        .seek(_.id)

      val standardResult = await(db.run(standardSeeker.page(limit = 5, cursor = None)))
      val pgTupleResult  = await(db.run(pgTupleSeeker.page(limit = 5, cursor = None)))

      standardResult.items shouldBe pgTupleResult.items
      standardResult.total shouldBe pgTupleResult.total
    }
  }

  "SlickPgTupleSeeker pageWithoutCount" should {

    "paginate forward without total count" in {
      val seeker = persons.toPgTupleSeekerAsc
        .seek(_.firstName)
        .seek(_.id)

      val page1 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = None)))

      page1.items should have size 3
      page1.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
      page1.nextCursor shouldBe defined
      page1.prevCursor shouldBe None

      val page2 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = page1.nextCursor)))
      page2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")
    }

    "paginate backward without total count" in {
      val seeker = persons.toPgTupleSeekerAsc
        .seek(_.firstName)
        .seek(_.id)

      val page1 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = None)))
      val page2 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = page1.nextCursor)))
      val page3 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = page2.nextCursor)))

      val back2 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = page3.prevCursor)))
      back2.items.map(_.firstName) shouldBe Seq("Diana", "Eve", "Frank")

      val back1 = await(db.run(seeker.pageWithoutCount(limit = 3, cursor = back2.prevCursor)))
      back1.items.map(_.firstName) shouldBe Seq("Alice", "Bob", "Charlie")
    }

    "return same items as page()" in {
      val seeker = persons.toPgTupleSeekerAsc
        .seek(_.firstName)
        .seek(_.id)

      val withCount    = await(db.run(seeker.page(limit = 5, cursor = None)))
      val withoutCount = await(db.run(seeker.pageWithoutCount(limit = 5, cursor = None)))

      withoutCount.items shouldBe withCount.items
      withoutCount.nextCursor shouldBe withCount.nextCursor
      withoutCount.prevCursor shouldBe withCount.prevCursor
    }
  }

  // These tests document compile-time failures (uncomment to see errors)
  /*
  "SlickPgTupleSeeker compile-time safety" should {

    "reject nullable columns" in {
      case class PersonWithNull(id: Int, email: Option[String])
      class PersonsWithNull(tag: Tag) extends Table[PersonWithNull](tag, "persons_null") {
        def id = column[Int]("id")
        def email = column[Option[String]]("email")
        def * = (id, email).mapTo[PersonWithNull]
      }
      val personsNull = TableQuery[PersonsWithNull]

      // This should NOT compile:
      val seeker = personsNull.toPgTupleSeekerAsc
        .seek(_.email)  // Compile error: ambiguous implicit for NotAnOption[Option[String]]
        .seek(_.id)
    }
  }
   */
}
