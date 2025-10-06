package io.github.devnico.slickseeker.filter

import io.github.devnico.slickseeker.support.MyH2Profile
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import slick.ast.{BaseTypedType, Ordering, ScalaBaseType}
import slick.lifted.{ColumnOrdered, LiteralColumn}

class ColumnSeekFilterSpec extends AnyWordSpec with Matchers {
  import MyH2Profile.Implicits._

  implicit val boolType: BaseTypedType[Boolean] = ScalaBaseType.booleanType match {
    case bt: BaseTypedType[Boolean @unchecked] => bt
  }
  implicit val intType: BaseTypedType[Int] = ScalaBaseType.intType match {
    case bt: BaseTypedType[Int @unchecked] => bt
  }
  implicit val stringType: BaseTypedType[String] = ScalaBaseType.stringType match {
    case bt: BaseTypedType[String @unchecked] => bt
  }

  "ColumnSeekFilter" should {

    "have a filter for non-nullable types" in {
      val filter = implicitly[ColumnSeekFilter[Int]]
      filter should not be null
    }

    "have a filter for nullable types" in {
      val filter = implicitly[ColumnSeekFilter[Option[Int]]]
      filter should not be null
    }
  }

  "seekFilterT (non-nullable)" should {
    val filter = implicitly[ColumnSeekFilter[Int]]

    "generate filter for ASC ordering" in {
      val column   = LiteralColumn(42)
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Asc))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, 10, prevCond)

      result should not be null
      result.toString should include("or")
    }

    "generate filter for DESC ordering" in {
      val column   = LiteralColumn(42)
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Desc))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, 10, prevCond)

      result should not be null
      result.toString should include("or")
    }
  }

  "seekFilterOptT (nullable)" should {
    val filter = implicitly[ColumnSeekFilter[Option[Int]]]

    "handle ASC with NullsFirst and Some value" in {
      val column   = LiteralColumn(Option(42))
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Asc, Ordering.NullsFirst))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, Some(10), prevCond)

      result should not be null
      result.toString should include("or")
    }

    "handle ASC with NullsFirst and None value" in {
      val column   = LiteralColumn(Option(42))
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Asc, Ordering.NullsFirst))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, None, prevCond)

      result should not be null
      result.toString should include("or")
    }

    "handle ASC with NullsLast and Some value" in {
      val column   = LiteralColumn(Option(42))
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Asc, Ordering.NullsLast))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, Some(10), prevCond)

      result should not be null
      result.toString should include("or")
    }

    "handle ASC with NullsLast and None value" in {
      val column   = LiteralColumn(Option(42))
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Asc, Ordering.NullsLast))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, None, prevCond)

      result should not be null
      result.toString should include("and")
    }

    "handle DESC with NullsFirst" in {
      val column   = LiteralColumn(Option(42))
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Desc, Ordering.NullsFirst))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, Some(10), prevCond)

      result should not be null
      result.toString should include("or")
    }

    "handle NullsDefault (defaults to NullsLast for ASC)" in {
      val column   = LiteralColumn(Option(42))
      val ordered  = ColumnOrdered(column, Ordering(Ordering.Asc, Ordering.NullsDefault))
      val prevCond = LiteralColumn(Option(true))

      val result = filter(ordered, Some(10), prevCond)

      result should not be null
      result.toString should include("or")
    }
  }

  "Filter generation" should {
    "create different filters for different types" in {
      val intFilter    = implicitly[ColumnSeekFilter[Int]]
      val stringFilter = implicitly[ColumnSeekFilter[String]]
      val optIntFilter = implicitly[ColumnSeekFilter[Option[Int]]]

      intFilter.getClass should not be stringFilter.getClass.getSuperclass
      intFilter.getClass should not be optIntFilter.getClass.getSuperclass
    }
  }
}
