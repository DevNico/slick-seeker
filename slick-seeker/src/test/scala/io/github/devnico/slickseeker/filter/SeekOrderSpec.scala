package io.github.devnico.slickseeker.filter

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SeekOrderSpec extends AnyWordSpec with Matchers {

  sealed trait Priority
  object Priority {
    case object Low    extends Priority
    case object Medium extends Priority
    case object High   extends Priority
  }

  sealed trait Status
  object Status {
    case object Draft    extends Status
    case object Pending  extends Status
    case object Approved extends Status
    case object Rejected extends Status
  }

  "SeekOrder" should {
    "store values in order" in {
      val order = SeekOrder(IndexedSeq(Priority.Low, Priority.Medium, Priority.High))

      order.values should have size 3
      order.values(0) shouldBe Priority.Low
      order.values(1) shouldBe Priority.Medium
      order.values(2) shouldBe Priority.High
    }

    "provide index lookup" in {
      val order = SeekOrder(IndexedSeq(Priority.Low, Priority.Medium, Priority.High))

      order.index(Priority.Low) shouldBe 0
      order.index(Priority.Medium) shouldBe 1
      order.index(Priority.High) shouldBe 2
    }

    "return 0 for unknown values" in {
      val order = SeekOrder(IndexedSeq(Priority.Low, Priority.Medium))

      order.index(Priority.High) shouldBe 0
    }

    "handle single value" in {
      val order = SeekOrder(IndexedSeq(Priority.Low))

      order.values should have size 1
      order.index(Priority.Low) shouldBe 0
    }

    "handle multiple different enums" in {
      val priorityOrder = SeekOrder(IndexedSeq(Priority.Low, Priority.Medium, Priority.High))
      val statusOrder   = SeekOrder(IndexedSeq(Status.Draft, Status.Pending, Status.Approved, Status.Rejected))

      priorityOrder.values should have size 3
      statusOrder.values should have size 4

      statusOrder.index(Status.Pending) shouldBe 1
      statusOrder.index(Status.Rejected) shouldBe 3
    }

    "work with custom ordering" in {
      val order = SeekOrder(IndexedSeq(Priority.High, Priority.Low, Priority.Medium))

      order.index(Priority.High) shouldBe 0
      order.index(Priority.Low) shouldBe 1
      order.index(Priority.Medium) shouldBe 2
    }

    "be immutable" in {
      val original = IndexedSeq(Priority.Low, Priority.Medium, Priority.High)
      val order    = SeekOrder(original)

      order.orderedValues shouldBe original
      order.orderedValues should not be theSameInstanceAs(original)
    }
  }

  "SeekOrder with real-world examples" should {
    "handle task priorities" in {
      val order = SeekOrder(IndexedSeq(Priority.High, Priority.Medium, Priority.Low))

      order.index(Priority.High) should be < order.index(Priority.Medium)
      order.index(Priority.Medium) should be < order.index(Priority.Low)
    }

    "handle workflow states" in {
      val order = SeekOrder(IndexedSeq(
        Status.Draft,
        Status.Pending,
        Status.Approved,
        Status.Rejected
      ))

      order.index(Status.Draft) shouldBe 0
      order.index(Status.Approved) shouldBe 2
    }
  }
}
