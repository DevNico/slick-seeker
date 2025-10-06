package io.github.devnico.slickseeker.filter

import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor.{Base64Decorator, CursorCodec, CursorEnvironment, CursorValueCodec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import slick.jdbc.H2Profile

import scala.language.existentials

/** Test that verifies implicit priority is correct to avoid ambiguous implicit errors when both SeekOrder and regular
  * type mapping are available.
  *
  * This test ensures that the fix for ambiguous implicits works correctly. Before the fix, having a SeekOrder[T] would
  * cause compilation to fail with: "Ambiguous given instances: both method seekerSortKeyIdentity and method
  * seekerSortKeyOrder"
  */
class SeekerSortKeyImplicitPrioritySpec extends AnyWordSpec with Matchers {

  // Define an enum type that could cause ambiguous implicits
  sealed trait Priority
  object Priority {
    case object Low    extends Priority
    case object Medium extends Priority
    case object High   extends Priority
  }

  sealed trait Status
  object Status {
    case object Draft  extends Status
    case object Active extends Status
  }

  // Create a test profile that includes SeekOrder instances
  trait TestProfile extends H2Profile with SlickSeekerSupport {

    object TestAPI extends JdbcAPI with SeekImplicits {
      // Cursor codec setup (required)
      implicit val stringCursorCodec: CursorCodec[String] = new CursorCodec[String] {
        def encode(values: Seq[String]): String = values.mkString("|")
        def decode(cursor: String): Either[String, Seq[String]] =
          Right(if (cursor.isEmpty) Seq.empty else cursor.split("\\|").toSeq)
      }

      implicit val cursorEnv: CursorEnvironment[String] =
        CursorEnvironment(stringCursorCodec, Base64Decorator())

      // Provide SeekOrder for Priority
      implicit val priorityOrder: SeekOrder[Priority] =
        SeekOrder(IndexedSeq(Priority.Low, Priority.Medium, Priority.High))

      // Provide SeekOrder for Status
      implicit val statusOrder: SeekOrder[Status] =
        SeekOrder(IndexedSeq(Status.Draft, Status.Active))

      // Provide BaseTypedType for Priority (required for Slick column mapping)
      implicit val priorityMapper: BaseColumnType[Priority] =
        MappedColumnType.base[Priority, String](
          {
            case Priority.Low    => "LOW"
            case Priority.Medium => "MEDIUM"
            case Priority.High   => "HIGH"
          },
          {
            case "LOW"    => Priority.Low
            case "MEDIUM" => Priority.Medium
            case "HIGH"   => Priority.High
          }
        )

      // Provide BaseTypedType for Status
      implicit val statusMapper: BaseColumnType[Status] =
        MappedColumnType.base[Status, String](
          {
            case Status.Draft  => "DRAFT"
            case Status.Active => "ACTIVE"
          },
          {
            case "DRAFT"  => Status.Draft
            case "ACTIVE" => Status.Active
          }
        )

      // Provide codecs for cursor value encoding/decoding
      implicit val intCodec: CursorValueCodec[Int, String] =
        new CursorValueCodec[Int, String] {
          def encode(value: Int): String         = value.toString
          def decode(value: String): Option[Int] = scala.util.Try(value.toInt).toOption
        }

      implicit val stringCodec: CursorValueCodec[String, String] =
        new CursorValueCodec[String, String] {
          def encode(value: String): String         = value
          def decode(value: String): Option[String] = Some(value)
        }

      implicit val longCodec: CursorValueCodec[Long, String] =
        new CursorValueCodec[Long, String] {
          def encode(value: Long): String         = value.toString
          def decode(value: String): Option[Long] = scala.util.Try(value.toLong).toOption
        }
    }

    override val api: TestAPI.type = TestAPI
  }

  object TestProfile extends TestProfile

  "SeekerSortKey implicits with SeekOrder" should {
    import TestProfile.api._

    "compile without ambiguous implicit errors when SeekOrder exists" in {
      // Should resolve to seekerSortKeyOrder (higher priority, defined in SeekerSortKeyImplicits)
      // not seekerSortKeyIdentity (lower priority, defined in SeekerSortKeyLowPriorityImplicits)
      val sk = implicitly[SeekerSortKey[Priority, Int, String]]

      sk should not be null
      info("Successfully resolved SeekerSortKey[Priority, String] without ambiguity")
    }

    "work with multiple SeekOrder types without conflicts" in {
      // Both should resolve without ambiguity
      val prioritySk = implicitly[SeekerSortKey[Priority, Int, String]]
      val statusSk   = implicitly[SeekerSortKey[Status, Int, String]]

      prioritySk should not be null
      statusSk should not be null
      info("Multiple enum types with SeekOrder resolved correctly")
    }

    "work with toSeeker and seek() for enum types" in {
      // Define a table with enum column
      class TestTable(tag: Tag) extends Table[(Long, Priority, String)](tag, "test") {
        def id       = column[Long]("id", O.PrimaryKey)
        def priority = column[Priority]("priority")
        def name     = column[String]("name")
        def *        = (id, priority, name)
      }

      val testTable = TableQuery[TestTable]

      // This should compile without "Ambiguous given instances" error
      // The .seek(_.priority.asc) call requires SeekerSortKey[Priority, String]
      // which should resolve to seekerSortKeyOrder (not seekerSortKeyIdentity)
      val seeker = testTable.toSeeker
        .seek(_.priority.asc) // Should use seekerSortKeyOrder (Priority -> Int mapping)
        .seek(_.name.asc)     // Should use seekerSortKeyIdentity (String -> String mapping)

      // Just verify it compiles and returns a SlickSeeker
      seeker should not be null
      info("Successfully created seeker with enum column using SeekOrder")
    }

    "handle Option types with SeekOrder" in {
      // Verify that Option[T] with SeekOrder[T] also works correctly
      val sk = implicitly[SeekerSortKey[Option[Priority], Int, String]]

      sk should not be null
      info("Option type with SeekOrder resolved correctly")
    }
  }

  "SeekerSortKey implicits without SeekOrder" should {
    import TestProfile.api._

    "use identity mapping for regular types" in {
      // For types without SeekOrder, should use seekerSortKeyIdentity
      val sk = implicitly[SeekerSortKey[String, String, String]]

      sk should not be null
      info("String type without SeekOrder uses identity mapping")
    }
  }

  "Implicit priority mechanism" should {
    import TestProfile.api._

    "use seekerSortKeyOrder for types with SeekOrder" in {
      // When SeekOrder[Priority] exists, should use seekerSortKeyOrder
      // which maps Priority to Int (not identity mapping to Priority)
      val sk = implicitly[SeekerSortKey[Priority, Int, String]]

      // The key characteristic of seekerSortKeyOrder is that it uses Int as the Key type
      // We can verify by checking the codec can decode integer strings
      val decoded = sk.codec.decode("123")
      decoded should not be None

      // Verify filter and shape are present
      sk.filter should not be null
      sk.shape should not be null

      info("Verified seekerSortKeyOrder is used for Priority (maps to Int)")
    }

    "use seekerSortKeyIdentity for types without SeekOrder" in {
      // String has no SeekOrder, so should use seekerSortKeyIdentity
      // which maps String to String (identity mapping)
      val sk = implicitly[SeekerSortKey[String, String, String]]

      // The codec should work with String values
      val decoded = sk.codec.decode("hello")
      decoded shouldBe Some("hello")

      // Verify filter and shape are present
      sk.filter should not be null
      sk.shape should not be null

      info("Verified seekerSortKeyIdentity is used for String (identity mapping)")
    }

    "prefer higher priority implicit when both could apply" in {
      val sk = implicitly[SeekerSortKey[Priority, Int, String]]

      // If we got seekerSortKeyOrder, the codec should work with integer string representations
      // If we got seekerSortKeyIdentity, it would fail with Priority-based encoding
      val result = sk.codec.decode("0")
      result should not be None // Should successfully decode an integer string

      sk.filter should not be null
      sk.shape should not be null

      info("Successfully resolved to seekerSortKeyOrder (higher priority)")
      info("The trait hierarchy (SeekerSortKeyImplicits > SeekerSortKeyLowPriorityImplicits) works correctly")
      info("This prevents the ambiguous implicit error that would occur if both were at the same priority level")
    }
  }
}
