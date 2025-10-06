package io.github.devnico.slickseeker.pagination

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PaginatedResultSpec extends AnyWordSpec with Matchers {

  case class Person(id: Int, name: String)
  case class PersonDTO(name: String)

  "PaginatedResult" should {
    "store all pagination metadata" in {
      val result = PaginatedResult(
        total = 100,
        items = Seq(Person(1, "Alice"), Person(2, "Bob")),
        prevCursor = Some("prev-cursor"),
        nextCursor = Some("next-cursor")
      )

      result.total shouldBe 100
      result.items should have size 2
      result.prevCursor shouldBe Some("prev-cursor")
      result.nextCursor shouldBe Some("next-cursor")
    }

    "handle no cursors for single page" in {
      val result = PaginatedResult(
        total = 2,
        items = Seq(Person(1, "Alice"), Person(2, "Bob")),
        prevCursor = None,
        nextCursor = None
      )

      result.prevCursor shouldBe None
      result.nextCursor shouldBe None
    }

    "handle empty items" in {
      val result = PaginatedResult[Person](
        total = 0,
        items = Seq.empty,
        prevCursor = None,
        nextCursor = None
      )

      result.total shouldBe 0
      result.items shouldBe empty
    }
  }

  "mapItems" should {
    "transform items while preserving metadata" in {
      val original = PaginatedResult(
        total = 100,
        items = Seq(Person(1, "Alice"), Person(2, "Bob")),
        prevCursor = Some("prev"),
        nextCursor = Some("next")
      )

      val transformed = original.mapItems(p => PersonDTO(p.name))

      transformed.total shouldBe 100
      transformed.items shouldBe Seq(PersonDTO("Alice"), PersonDTO("Bob"))
      transformed.prevCursor shouldBe Some("prev")
      transformed.nextCursor shouldBe Some("next")
    }

    "handle empty items" in {
      val original = PaginatedResult[Person](
        total = 0,
        items = Seq.empty,
        prevCursor = None,
        nextCursor = None
      )

      val transformed = original.mapItems(p => PersonDTO(p.name))

      transformed.items shouldBe empty
      transformed.total shouldBe 0
    }

    "allow chaining transformations" in {
      val original = PaginatedResult(
        total = 10,
        items = Seq(Person(1, "Alice"), Person(2, "Bob")),
        prevCursor = None,
        nextCursor = Some("next")
      )

      val result = original
        .mapItems(_.name)
        .mapItems(_.toUpperCase)
        .mapItems(s => s"Hello, $s!")

      result.items shouldBe Seq("Hello, ALICE!", "Hello, BOB!")
      result.total shouldBe 10
      result.nextCursor shouldBe Some("next")
    }

    "preserve original on immutable semantics" in {
      val original = PaginatedResult(
        total = 10,
        items = Seq(Person(1, "Alice")),
        prevCursor = None,
        nextCursor = None
      )

      val transformed = original.mapItems(p => PersonDTO(p.name))

      original.items should have size 1
      original.items.head shouldBe Person(1, "Alice")

      transformed.items should have size 1
      transformed.items.head shouldBe PersonDTO("Alice")
    }
  }

  "PaginatedResult copy semantics" should {
    "allow copying with modifications" in {
      val original = PaginatedResult(
        total = 100,
        items = Seq(Person(1, "Alice")),
        prevCursor = None,
        nextCursor = Some("next")
      )

      val modified = original.copy(total = 200)

      modified.total shouldBe 200
      modified.items shouldBe original.items
      modified.nextCursor shouldBe original.nextCursor
    }
  }
}
