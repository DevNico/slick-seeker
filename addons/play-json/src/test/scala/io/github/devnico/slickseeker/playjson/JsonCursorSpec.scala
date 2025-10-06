package io.github.devnico.slickseeker.playjson

import io.github.devnico.slickseeker.{Base64Decorator, CursorEnvironment}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json._

class JsonCursorSpec extends AnyWordSpec with Matchers {
  import PlayJsonSupport._

  case class Person(id: Int, name: String)
  implicit val LPersonEFormat: Format[Person] = Json.format[Person]

  "JsonCursorValueCodec" should {
    "encode and decode Int values" in {
      val codec = jsonCursorValueCodec[Int]

      val encoded = codec.encode(42)
      encoded shouldBe JsNumber(42)

      val decoded = codec.decode(encoded)
      decoded shouldBe Some(42)
    }

    "encode and decode String values" in {
      val codec = jsonCursorValueCodec[String]

      val encoded = codec.encode("hello")
      encoded shouldBe JsString("hello")

      val decoded = codec.decode(encoded)
      decoded shouldBe Some("hello")
    }

    "encode and decode Boolean values" in {
      val codec = jsonCursorValueCodec[Boolean]

      val encoded = codec.encode(true)
      encoded shouldBe JsBoolean(true)

      val decoded = codec.decode(encoded)
      decoded shouldBe Some(true)
    }

    "handle decode failures gracefully" in {
      val codec = jsonCursorValueCodec[Int]

      val decoded = codec.decode(JsString("not a number"))
      decoded shouldBe None
    }
  }

  "JsonOptionCursorValueCodec" should {
    "encode Some values" in {
      val codec = jsonOptionCursorValueCodec[String]

      val encoded = codec.encode(Some("hello"))
      encoded shouldBe JsString("hello")
    }

    "encode None as JsNull" in {
      val codec = jsonOptionCursorValueCodec[String]

      val encoded = codec.encode(None)
      encoded shouldBe JsNull
    }

    "decode Some values" in {
      val codec = jsonOptionCursorValueCodec[String]

      val decoded = codec.decode(JsString("hello"))
      decoded shouldBe Some(Some("hello"))
    }

    "decode JsNull as None" in {
      val codec = jsonOptionCursorValueCodec[String]

      val decoded = codec.decode(JsNull)
      decoded shouldBe Some(None)
    }

    "handle invalid JSON gracefully" in {
      val codec = jsonOptionCursorValueCodec[Int]

      val decoded = codec.decode(JsString("not a number"))
      decoded shouldBe Some(None)
    }
  }

  "JsonCursorCodec" should {
    val codec = jsonCursorCodec

    "encode sequence of JSON values" in {
      val values = Seq(
        JsNumber(42),
        JsString("hello"),
        JsBoolean(true)
      )

      val encoded = codec.encode(values)
      encoded should not be empty

      // Should be valid JSON array
      val parsed = Json.parse(encoded)
      parsed shouldBe JsArray(values)
    }

    "encode empty sequence" in {
      val encoded = codec.encode(Seq.empty)
      encoded shouldBe "[]"
    }

    "decode JSON array" in {
      val json = """[42,"hello",true]"""

      val decoded = codec.decode(json)
      decoded shouldBe Right(Seq(
        JsNumber(42),
        JsString("hello"),
        JsBoolean(true)
      ))
    }

    "decode empty array" in {
      val decoded = codec.decode("[]")
      decoded shouldBe Right(Seq.empty)
    }

    "fail on invalid JSON" in {
      val result = codec.decode("not valid json")
      result.isLeft shouldBe true
      result.swap.getOrElse("") should include("Failed to parse cursor json")
    }

    "fail on non-array JSON" in {
      val result = codec.decode("""{"key": "value"}""")
      result.isLeft shouldBe true
      result.swap.getOrElse("") should include("unexpected structure")
    }

    "handle complex nested JSON" in {
      val values = Seq(
        Json.obj("id" -> 1, "name" -> "Alice"),
        Json.obj("id" -> 2, "name" -> "Bob"),
        JsNull
      )

      val encoded = codec.encode(values)
      val decoded = codec.decode(encoded)

      decoded shouldBe Right(values)
    }
  }

  "jsonCursorEnvironment" should {
    val env = defaultJsonCursorEnvironment

    "be available as given instance" in {
      implicitly[CursorEnvironment[JsValue]] shouldBe env
    }

    "use JsonCursorCodec" in {
      env.codec shouldBe a[JsonCursorCodec]
    }

    "use Base64Decorator" in {
      env.decorator shouldBe a[Base64Decorator]
    }

    "encode and decode full cursor" in {
      val values = Seq(
        JsNumber(1),
        JsString("Alice"),
        JsNumber(25)
      )

      val cursor = env.encode(values)
      cursor should not be empty

      val decoded = env.decode(Some(cursor))
      decoded shouldBe Right(Some(values))
    }

    "handle round-trip with various types" in {
      val values = Seq(
        JsNumber(42),
        JsString("test"),
        JsBoolean(true),
        JsNull,
        Json.obj("key" -> "value"),
        Json.arr(1, 2, 3)
      )

      val cursor  = env.encode(values)
      val decoded = env.decodeOrThrow(Some(cursor))

      decoded shouldBe Some(values)
    }
  }

  "Given instances" should {
    "provide codec for common types" in {
      implicitly[JsonCursorValueCodec[Int]] should not be null
      implicitly[JsonCursorValueCodec[String]] should not be null
      implicitly[JsonCursorValueCodec[Boolean]] should not be null
      implicitly[JsonCursorValueCodec[Long]] should not be null
      implicitly[JsonCursorValueCodec[Double]] should not be null
    }

    "provide codec for Option types" in {
      implicitly[JsonOptionCursorValueCodec[Int]] should not be null
      implicitly[JsonOptionCursorValueCodec[String]] should not be null
    }

    "provide cursor codec" in {
      implicitly[JsonCursorCodec] should not be null
    }

    "provide cursor environment" in {
      implicitly[CursorEnvironment[JsValue]] should not be null
    }
  }

  "Integration with custom types" should {
    "encode and decode custom types" in {
      val codec = jsonCursorValueCodec[Person]

      val person  = Person(1, "Alice")
      val encoded = codec.encode(person)

      encoded shouldBe Json.obj("id" -> 1, "name" -> "Alice")

      val decoded = codec.decode(encoded)
      decoded shouldBe Some(person)
    }

    "work in cursor environment" in {
      val env   = defaultJsonCursorEnvironment
      val codec = jsonCursorValueCodec[Person]

      val people = Seq(
        codec.encode(Person(1, "Alice")),
        codec.encode(Person(2, "Bob"))
      )

      val cursor  = env.encode(people)
      val decoded = env.decodeOrThrow(Some(cursor))

      decoded shouldBe Some(people)
    }
  }

  "Error handling" should {
    val env = defaultJsonCursorEnvironment

    "handle corrupted cursors" in {
      val result = env.decode(Some("!!!invalid base64!!!"))
      result.isLeft shouldBe true
    }

    "throw on decodeOrThrow with invalid cursor" in {
      assertThrows[IllegalArgumentException] {
        env.decodeOrThrow(Some("!!!invalid!!!"))
      }
    }

    "handle empty cursor" in {
      env.decode(Some("")) shouldBe Right(None)
      env.decode(None) shouldBe Right(None)
    }
  }
}
