package io.github.devnico.slickseeker.playjson

import io.github.devnico.slickseeker.{IdentityDecorator, SlickSeekerSupport}
import io.github.devnico.slickseeker.cursor.CursorEnvironment
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json._
import slick.jdbc.H2Profile

class PlayJsonSeekerSupportSpec extends AnyWordSpec with Matchers {

  "PlayJsonSeekerSupport" should {

    "provide codecs for basic types" in {
      trait TestProfile extends H2Profile with SlickSeekerSupport with PlayJsonSeekerSupport {
        object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits
        override val api: MyApi.type = MyApi
      }

      object TestProfile extends TestProfile

      import TestProfile.api._

      val intCodec = new TestProfile.JsonCursorValueCodec[Int]
      intCodec.encode(42) shouldBe JsNumber(42)
      intCodec.decode(JsNumber(42)) shouldBe Some(42)

      val stringCodec = new TestProfile.JsonCursorValueCodec[String]
      stringCodec.encode("test") shouldBe JsString("test")
      stringCodec.decode(JsString("test")) shouldBe Some("test")
    }

    "provide codec for custom types using Format" in {
      trait TestProfile extends H2Profile with SlickSeekerSupport with PlayJsonSeekerSupport {
        object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits {}
        override val api: MyApi.type = MyApi
      }

      object TestProfile extends TestProfile

      import TestProfile.api._

      case class Person(id: Int, name: String)
      implicit val personFormat: Format[Person] = Json.format[Person]

      val codec  = new TestProfile.JsonCursorValueCodec[Person]
      val person = Person(1, "Alice")
      val json   = codec.encode(person)

      (json \ "id").as[Int] shouldBe 1
      (json \ "name").as[String] shouldBe "Alice"

      codec.decode(json) shouldBe Some(person)
    }

    "provide JsonCursorCodec" in {
      trait TestProfile extends H2Profile with SlickSeekerSupport with PlayJsonSeekerSupport {
        object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits {}
        override val api: MyApi.type = MyApi
      }

      object TestProfile extends TestProfile

      import TestProfile.api._

      val cursorCodec = new TestProfile.JsonCursorCodec
      val encoded     = cursorCodec.encode(Seq(JsNumber(1), JsString("test")))
      encoded shouldBe "[1,\"test\"]"

      cursorCodec.decode(encoded) shouldBe Right(Seq(JsNumber(1), JsString("test")))
    }

    "provide cursor environment through JsonSeekerImplicits" in {
      trait TestProfile extends H2Profile with SlickSeekerSupport with PlayJsonSeekerSupport {
        object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits {}
        override val api: MyApi.type = MyApi
      }

      object TestProfile extends TestProfile

      import TestProfile.api._

      val env = implicitly[CursorEnvironment[JsValue]]

      val values = Seq(JsNumber(1), JsString("test"))
      val cursor = env.encode(values)

      // Should be Base64 encoded by default
      cursor should not equal "[1,\"test\"]"
      cursor should not be empty

      // Should decode back correctly
      val decoded = env.decode(Some(cursor))
      decoded.isRight shouldBe true
      decoded.toOption.get shouldBe Some(values)
    }

    "allow custom cursor environment" in {
      trait CustomProfile extends H2Profile with SlickSeekerSupport with PlayJsonSeekerSupport {
        object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits {
          implicit override val cursorEnvironment: CursorEnvironment[JsValue] =
            CursorEnvironment(jsonCursorCodec, IdentityDecorator())
        }
        override val api: MyApi.type = MyApi
      }

      object CustomProfile extends CustomProfile

      import CustomProfile.api._

      val env = implicitly[CursorEnvironment[JsValue]]

      val values = Seq(JsNumber(1), JsString("test"))
      val cursor = env.encode(values)

      // Should NOT be Base64 encoded with IdentityDecorator
      cursor should include("1")
      cursor should include("test")

      val decoded = env.decode(Some(cursor))
      decoded.isRight shouldBe true
      decoded.toOption.get shouldBe Some(values)
    }
  }
}
