package io.github.devnico.slickseeker.pagination

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PaginatedResultWithoutCountSpec extends AnyWordSpec with Matchers {

  case class Person(id: Int, name: String)
  case class PersonDTO(name: String)

  "mapItems" should {
    "transform items while preserving metadata" in {
      val original = PaginatedResultWithoutCount(
        items = Seq(Person(1, "Alice"), Person(2, "Bob")),
        prevCursor = Some("prev"),
        nextCursor = Some("next")
      )

      val transformed = original.mapItems(p => PersonDTO(p.name))

      transformed.items shouldBe Seq(PersonDTO("Alice"), PersonDTO("Bob"))
      transformed.prevCursor shouldBe Some("prev")
      transformed.nextCursor shouldBe Some("next")
    }

    "handle empty items" in {
      val original = PaginatedResultWithoutCount[Person](
        items = Seq.empty,
        prevCursor = None,
        nextCursor = None
      )

      val transformed = original.mapItems(p => PersonDTO(p.name))

      transformed.items shouldBe empty
    }

    "allow chaining transformations" in {
      val original = PaginatedResultWithoutCount(
        items = Seq(Person(1, "Alice"), Person(2, "Bob")),
        prevCursor = None,
        nextCursor = Some("next")
      )

      val result = original
        .mapItems(_.name)
        .mapItems(_.toUpperCase)
        .mapItems(s => s"Hello, $s!")

      result.items shouldBe Seq("Hello, ALICE!", "Hello, BOB!")
      result.nextCursor shouldBe Some("next")
    }

    "preserve original on immutable semantics" in {
      val original = PaginatedResultWithoutCount(
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

  "PaginatedResultWithoutCount copy semantics" should {
    "allow copying with modifications" in {
      val original = PaginatedResultWithoutCount(
        items = Seq(Person(1, "Alice")),
        prevCursor = None,
        nextCursor = Some("next")
      )

      val modified = original.copy(nextCursor = Some("other"))

      modified.nextCursor shouldBe Some("other")
      modified.items shouldBe original.items
      modified.prevCursor shouldBe original.prevCursor
    }
  }

  "withCount" should {
    "convert to PaginatedResult" in {
      val without = PaginatedResultWithoutCount(
        items = Seq(Person(1, "Alice"), Person(2, "Bob")),
        prevCursor = Some("prev"),
        nextCursor = Some("next")
      )

      val withCount = without.withCount(100)

      withCount.total shouldBe 100
      withCount.items shouldBe Seq(Person(1, "Alice"), Person(2, "Bob"))
      withCount.prevCursor shouldBe Some("prev")
      withCount.nextCursor shouldBe Some("next")
    }
  }

  "round-trip conversion" should {
    "preserve all fields through withCount and withoutCount" in {
      val original = PaginatedResultWithoutCount(
        items = Seq(Person(1, "Alice")),
        prevCursor = None,
        nextCursor = Some("next")
      )

      val roundTripped = original.withCount(42).withoutCount

      roundTripped shouldBe original
    }
  }
}
