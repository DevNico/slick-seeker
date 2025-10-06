package io.github.devnico.slickseeker.cursor

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

class CursorSpec extends AnyWordSpec with Matchers {

  case class TestCodec() extends CursorCodec[String] {
    def encode(values: Seq[String]): String = values.mkString(",")
    def decode(cursor: String): Either[String, Seq[String]] =
      Right(cursor.split(",").toSeq)
  }

  case class TestValueCodec() extends CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = Try(value.toInt).toOption
  }

  implicit val testCodec: CursorValueCodec[Int, String] = TestValueCodec()

  "CursorDirection" should {
    "have Forward and Backward cases" in {
      CursorDirection.Forward shouldBe CursorDirection.Forward
      CursorDirection.Backward shouldBe CursorDirection.Backward
    }
  }

  "Base64Decorator" should {
    val decorator = Base64Decorator()

    "encode and decode strings correctly" in {
      val original = "test-cursor-value"
      val encoded  = decorator.encode(original)
      val decoded  = decorator.decode(encoded)

      encoded should not be original
      decoded shouldBe Right(original)
    }

    "handle empty strings" in {
      val encoded = decorator.encode("")
      val decoded = decorator.decode(encoded)

      decoded shouldBe Right("")
    }

    "handle special characters" in {
      val original = "cursor|with|pipes:and:colons,commas"
      val encoded  = decorator.encode(original)
      val decoded  = decorator.decode(encoded)

      decoded shouldBe Right(original)
    }

    "fail on invalid base64" in {
      val invalid = "not-valid-base64!!!"
      val result  = decorator.decode(invalid)

      result.isLeft shouldBe true
    }
  }

  "CursorEnvironment" should {
    val env = CursorEnvironment(TestCodec(), Base64Decorator())

    "encode cursors with Forward direction" in {
      val cursor = env.encode(Seq("1", "2", "3"))

      cursor should not be empty
      val decoded = env.decodeWithDirection(Some(cursor))

      decoded shouldBe Right(Some((CursorDirection.Forward, Seq("1", "2", "3"))))
    }

    "encode cursors with Backward direction" in {
      val cursor = env.encode(Seq("1", "2", "3"), CursorDirection.Backward)

      cursor should not be empty
      val decoded = env.decodeWithDirection(Some(cursor))

      decoded shouldBe Right(Some((CursorDirection.Backward, Seq("1", "2", "3"))))
    }

    "decode None cursor" in {
      val result = env.decode(None)
      result shouldBe Right(None)
    }

    "decode blank cursor" in {
      val result = env.decode(Some(""))
      result shouldBe Right(None)

      val result2 = env.decode(Some("   "))
      result2 shouldBe Right(None)
    }

    "handle backward compatibility (cursors without direction prefix)" in {
      val codec        = TestCodec()
      val rawCursor    = codec.encode(Seq("1", "2"))
      val decorator    = Base64Decorator()
      val legacyCursor = decorator.encode(rawCursor)

      val result = env.decodeWithDirection(Some(legacyCursor))
      result shouldBe Right(Some((CursorDirection.Forward, Seq("1", "2"))))
    }

    "decodeOrThrow should throw on error" in {
      assertThrows[IllegalArgumentException] {
        env.decodeOrThrow(Some("invalid!!!"))
      }
    }

    "decodeOrThrow should return None for empty" in {
      env.decodeOrThrow(None) shouldBe None
      env.decodeOrThrow(Some("")) shouldBe None
    }
  }

  "CursorEnvironment.DecodeError" should {
    "store message and cause" in {
      val error = CursorEnvironment.DecodeError("test message")
      error.message shouldBe "test message"
      error.cause shouldBe None

      val cause  = new RuntimeException("cause")
      val error2 = CursorEnvironment.DecodeError("test", Some(cause))
      error2.message shouldBe "test"
      error2.cause shouldBe Some(cause)
    }
  }

  "Decorator chaining" should {
    case class UpperDecorator(inner: CursorDecorator = IdentityDecorator()) extends CursorDecorator {
      override def encode(value: String): String = {
        val innerEncoded = inner.encode(value)
        innerEncoded.toUpperCase
      }

      override def decode(cursor: String): Either[Throwable, String] =
        Try(cursor.toLowerCase).toEither.flatMap(inner.decode)
    }

    "apply decorators in correct order (inside-out)" in {
      val decorator = Base64Decorator(UpperDecorator())

      val original = "test value"
      val encoded  = decorator.encode(original)
      val decoded  = decorator.decode(encoded)

      decoded shouldBe Right(original)

      val manualUpper = UpperDecorator().encode(original)
      manualUpper shouldBe "TEST VALUE"
      val manualBase64 = Base64Decorator().encode(manualUpper)
      encoded shouldBe manualBase64
    }

    "work with Identity decorator (no-op)" in {
      val decorator = IdentityDecorator()

      val value   = "test"
      val encoded = decorator.encode(value)
      val decoded = decorator.decode(encoded)

      encoded shouldBe value
      decoded shouldBe Right(value)
    }

    "compose multiple custom decorators" in {
      case class ReverseDecorator(inner: CursorDecorator = IdentityDecorator()) extends CursorDecorator {
        override def encode(value: String): String = {
          val innerEncoded = inner.encode(value)
          innerEncoded.reverse
        }

        override def decode(cursor: String): Either[Throwable, String] =
          Try(cursor.reverse).toEither.flatMap(inner.decode)
      }

      val decorator = ReverseDecorator(UpperDecorator())

      val original = "test"
      val encoded  = decorator.encode(original)
      val decoded  = decorator.decode(encoded)

      decoded shouldBe Right(original)
      encoded shouldBe "TSET"
    }

    "work in CursorEnvironment" in {
      case class PrefixDecorator(prefix: String, inner: CursorDecorator = IdentityDecorator())
          extends CursorDecorator {
        override def encode(value: String): String = {
          val innerEncoded = inner.encode(value)
          s"$prefix$innerEncoded"
        }

        override def decode(cursor: String): Either[Throwable, String] =
          if (cursor.startsWith(prefix)) {
            inner.decode(cursor.drop(prefix.length))
          } else {
            Left(new IllegalArgumentException(s"Missing prefix: $prefix"))
          }
      }

      val codec     = TestCodec()
      val decorator = Base64Decorator(PrefixDecorator("v1:"))
      val env       = CursorEnvironment(codec, decorator)

      val encoded = env.encode(Seq("value1", "value2"))
      val decoded = env.decode(Some(encoded))

      decoded shouldBe Right(Some(Seq("value1", "value2")))

      val decodedBase64 = Base64Decorator().decode(encoded)
      decodedBase64 should matchPattern { case Right(s: String) if s.contains("v1:") => }
    }
  }
}
