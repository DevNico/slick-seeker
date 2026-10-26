package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.support.MyH2Profile
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class CompiledSeekPageSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // Codecs that parse cursor values back into their types, as bound parameters need them.
  implicit val intCodec: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = value.toIntOption
  }
  implicit val optionStringCodec: CursorValueCodec[Option[String], String] =
    new CursorValueCodec[Option[String], String] {
      def encode(value: Option[String]): String = value.fold("N")("S" + _)
      def decode(value: String): Option[Option[String]] =
        if (value == "N") Some(None) else if (value.startsWith("S")) Some(Some(value.drop(1))) else None
    }

  case class Person(id: Int, firstName: String, lastName: Option[String], age: Int)

  class Persons(tag: Tag) extends Table[Person](tag, "persons_compiled") {
    def id        = column[Int]("id", O.PrimaryKey)
    def firstName = column[String]("first_name")
    def lastName  = column[Option[String]]("last_name")
    def age       = column[Int]("age")

    def * = (id, firstName, lastName, age).mapTo[Person]
  }

  val persons = TableQuery[Persons]
  val db      = Database.forConfig("h2mem")

  val testData = Seq(
    Person(1, "Alice", Some("Anderson"), 25),
    Person(2, "Bob", Some("Barnes"), 30),
    Person(3, "Charlie", Some("Clark"), 35),
    Person(4, "Diana", Some("Dove"), 28),
    Person(5, "Eve", Some("Evans"), 32),
    Person(6, "Frank", None, 45),
    Person(7, "Grace", Some("Green"), 29),
    Person(8, "Henry", Some("Hill"), 31),
    Person(9, "Iris", None, 27),
    Person(10, "Jack", Some("Jones"), 33)
  )

  override def beforeAll(): Unit =
    Await.result(db.run(DBIO.seq(persons.schema.dropIfExists, persons.schema.create, persons ++= testData)), 5.seconds)

  override def afterAll(): Unit = db.close()

  def await[T](f: Future[T]): T = Await.result(f, 5.seconds)

  def adHoc(minAge: Int) =
    persons.filter(_.age >= minAge).toSeeker.seek(_.lastName.desc.nullsLast).seek(_.id.asc)

  val byMinAge = CompiledSeekPage[Int]((minAge: ConstColumn[Int]) =>
    persons.filter(_.age >= minAge).toSeeker.seek(_.lastName.desc.nullsLast).seek(_.id.asc)
  )(p => (p.id, p.lastName))

  "CompiledSeekPage" should {

    "return the same pages and cursors as the ad-hoc seeker, forward and backward" in {
      var compiledCursor: Option[String] = None
      var adHocCursor: Option[String]    = None
      val forward = (1 to 4).map { _ =>
        val compiled = await(db.run(byMinAge.pageWithoutCount(params = 0, limit = 3, cursor = compiledCursor)))
        val expected =
          await(db.run(adHoc(0).pageWithoutCountAs(limit = 3, cursor = adHocCursor)(p => (p.id, p.lastName))))
        compiled shouldBe expected
        compiledCursor = compiled.nextCursor
        adHocCursor = expected.nextCursor
        compiled
      }
      forward.flatMap(_.items.map(_._1)) shouldBe Seq(10, 8, 7, 5, 4, 3, 2, 1, 6, 9)
      forward.last.nextCursor shouldBe None

      val backward = forward.reverse.tail.foldLeft(Seq(forward.last)) { (pages, _) =>
        val back = await(db.run(byMinAge.pageWithoutCount(params = 0, limit = 3, cursor = pages.last.prevCursor)))
        back shouldBe await(
          db.run(adHoc(0).pageWithoutCountAs(limit = 3, cursor = pages.last.prevCursor)(p => (p.id, p.lastName)))
        )
        pages :+ back
      }
      backward.map(_.items) shouldBe forward.reverse.map(_.items)
    }

    "bind the application parameters" in {
      val page = await(db.run(byMinAge.pageWithoutCount(params = 31, limit = 10, cursor = None)))
      page.items.map(_._1) shouldBe Seq(10, 8, 5, 3, 6)
    }

    "reuse its compiled forms for other parameter and cursor values" in {
      val first  = await(db.run(byMinAge.pageWithoutCount(params = 0, limit = 2, cursor = None)))
      val second = await(db.run(byMinAge.pageWithoutCount(params = 0, limit = 2, cursor = first.nextCursor)))
      val forms  = byMinAge.createdForms
      val other  = await(db.run(byMinAge.pageWithoutCount(params = 26, limit = 4, cursor = None)))
      await(db.run(byMinAge.pageWithoutCount(params = 26, limit = 4, cursor = other.nextCursor)))
      await(db.run(byMinAge.pageWithoutCount(params = 0, limit = 2, cursor = second.nextCursor)))

      byMinAge.createdForms shouldBe forms
    }

    "compile one form per direction and None pattern of the cursor" in {
      val fresh = CompiledSeekPage[Int]((minAge: ConstColumn[Int]) =>
        persons.filter(_.age >= minAge).toSeeker.seek(_.lastName.desc.nullsLast).seek(_.id.asc)
      )(_.id)
      val first       = await(db.run(fresh.pageWithoutCount(params = 0, limit = 3, cursor = None)))
      val second      = await(db.run(fresh.pageWithoutCount(params = 0, limit = 3, cursor = first.nextCursor)))
      val beforeNulls = await(db.run(fresh.pageWithoutCount(params = 0, limit = 9, cursor = None)))
      val nullCursor  = await(db.run(fresh.pageWithoutCount(params = 0, limit = 9, cursor = beforeNulls.nextCursor)))
      await(db.run(fresh.pageWithoutCount(params = 0, limit = 3, cursor = second.prevCursor)))

      nullCursor.items shouldBe Seq(9)
      fresh.createdForms shouldBe 4 // first page, forward, forward after None, backward
    }
  }
}
