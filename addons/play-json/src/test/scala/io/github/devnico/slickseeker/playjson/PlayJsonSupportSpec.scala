package io.github.devnico.slickseeker.playjson

import io.github.devnico.slickseeker.Base64Decorator
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json._

class PlayJsonSupportSpec extends AnyWordSpec with Matchers {
  import PlayJsonSupport._

  case class Person(id: Int, name: String)
  implicit val personFormat: Format[Person] = Json.format[Person]

  case class Task(id: Int, title: String, completed: Boolean)
  implicit val taskFormat: Format[Task] = Json.format[Task]

  "JsonCursorValueCodec" should {
    "encode values to JSON" in {
      val codec = jsonCursorValueCodec[Person]
      val person = Person(1, "Alice")
      val json = codec.encode(person)
      
      (json \ "id").as[Int] shouldBe 1
      (json \ "name").as[String] shouldBe "Alice"
    }

    "decode values from JSON" in {
      val codec = jsonCursorValueCodec[Person]
      val json = Json.obj("id" -> 1, "name" -> "Alice")
      val result = codec.decode(json)
      
      result shouldBe Some(Person(1, "Alice"))
    }

    "round-trip correctly" in {
      val codec = jsonCursorValueCodec[Person]
      val original = Person(42, "Bob")
      val json = codec.encode(original)
      val decoded = codec.decode(json)
      
      decoded shouldBe Some(original)
    }

    "handle different types" in {
      val intCodec = jsonCursorValueCodecInt
      intCodec.encode(42) shouldBe JsNumber(42)
      intCodec.decode(JsNumber(42)) shouldBe Some(42)

      val stringCodec = jsonCursorValueCodecString
      stringCodec.encode("hello") shouldBe JsString("hello")
      stringCodec.decode(JsString("hello")) shouldBe Some("hello")
    }
  }

  "JsonOptionCursorValueCodec" should {
    "encode Some values" in {
      val codec = jsonOptionCursorValueCodec[Person]
      val person = Some(Person(1, "Alice"))
      val json = codec.encode(person)
      
      (json \ "id").as[Int] shouldBe 1
      (json \ "name").as[String] shouldBe "Alice"
    }

    "encode None as JsNull" in {
      val codec = jsonOptionCursorValueCodec[Person]
      val json = codec.encode(None)
      
      json shouldBe JsNull
    }

    "decode Some values" in {
      val codec = jsonOptionCursorValueCodec[Person]
      val json = Json.obj("id" -> 1, "name" -> "Alice")
      val result = codec.decode(json)
      
      result shouldBe Some(Some(Person(1, "Alice")))
    }

    "decode JsNull as None" in {
      val codec = jsonOptionCursorValueCodec[Person]
      val result = codec.decode(JsNull)
      
      result shouldBe Some(None)
    }

    "round-trip Some values" in {
      val codec = jsonOptionCursorValueCodec[Person]
      val original = Some(Person(42, "Bob"))
      val json = codec.encode(original)
      val decoded = codec.decode(json)
      
      decoded shouldBe Some(original)
    }

    "round-trip None values" in {
      val codec = jsonOptionCursorValueCodec[Person]
      val original = None
      val json = codec.encode(original)
      val decoded = codec.decode(json)
      
      decoded shouldBe Some(None)
    }
  }

  "JsonCursorCodec" should {
    "encode sequences to JSON string" in {
      val codec = jsonCursorCodec
      val values = Seq(JsNumber(1), JsString("test"), JsBoolean(true))
      val encoded = codec.encode(values)
      
      encoded shouldBe "[1,\"test\",true]"
    }

    "decode JSON string to sequences" in {
      val codec = jsonCursorCodec
      val json = "[1,\"test\",true]"
      val result = codec.decode(json)
      
      result shouldBe Right(Seq(JsNumber(1), JsString("test"), JsBoolean(true)))
    }

    "round-trip correctly" in {
      val codec = jsonCursorCodec
      val original = Seq(
        Json.obj("id" -> 1, "name" -> "Alice"),
        Json.obj("id" -> 2, "name" -> "Bob")
      )
      val encoded = codec.encode(original)
      val decoded = codec.decode(encoded)
      
      decoded shouldBe Right(original)
    }

    "handle empty sequences" in {
      val codec = jsonCursorCodec
      val empty = Seq.empty[JsValue]
      val encoded = codec.encode(empty)
      val decoded = codec.decode(encoded)
      
      decoded shouldBe Right(Seq.empty)
    }

    "fail on invalid JSON" in {
      val codec = jsonCursorCodec
      val result = codec.decode("not valid json")
      
      result.isLeft shouldBe true
      result.left.map(_ should include("Failed to parse cursor json"))
    }

    "fail on non-array JSON" in {
      val codec = jsonCursorCodec
      val result = codec.decode("{\"key\": \"value\"}")
      
      result.isLeft shouldBe true
      result.left.map(_ should include("unexpected structure"))
    }
  }

  "CursorEnvironment" should {
    "be created with default decorator" in {
      val env = cursorEnvironment()
      env should not be null
    }

    "be created with custom decorator" in {
      val env = cursorEnvironment(Base64Decorator())
      env should not be null
    }

    "have default implicit instance" in {
      val env = defaultJsonCursorEnvironment
      env should not be null
    }
  }
}
