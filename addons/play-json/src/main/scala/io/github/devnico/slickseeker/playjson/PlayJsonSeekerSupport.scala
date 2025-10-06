package io.github.devnico.slickseeker.playjson

import io.github.devnico.slickseeker.Base64Decorator
import io.github.devnico.slickseeker.cursor.{CursorCodec, CursorEnvironment, CursorValueCodec}
import play.api.libs.json._
import slick.jdbc.JdbcProfile

import scala.util.Try

/** Trait to mix into Slick profiles for Play JSON cursor support.
  *
  * This trait provides implicit codecs and cursor environment for using Play JSON with SlickSeeker pagination.
  *
  * Example usage:
  * {{{
  * import slick.jdbc.PostgresProfile
  * import io.github.devnico.slickseeker.SlickSeekerSupport
  * import io.github.devnico.slickseeker.playjson.PlayJsonSeekerSupport
  *
  * trait MyPostgresProfile extends PostgresProfile
  *   with SlickSeekerSupport
  *   with PlayJsonSeekerSupport {
  *
  *   object MyApi extends API with SeekImplicits with JsonSeekerImplicits
  *   
  *   override val api: MyApi.type = MyApi
  * }
  *
  * object MyPostgresProfile extends MyPostgresProfile
  *
  * // Usage in DAOs - just one import!
  * import MyPostgresProfile.api._
  *
  * class UserDao {
  *   val seeker = users.toSeeker.seek(_.id.asc)
  * }
  * }}}
  */
trait PlayJsonSeekerSupport { driver: JdbcProfile =>

  /** Codec for encoding/decoding values to/from JSON. */
  class JsonCursorValueCodec[T: Format] extends CursorValueCodec[T, JsValue] {
    def encode(value: T): JsValue         = Json.toJson(value)
    def decode(value: JsValue): Option[T] = value.asOpt[T]
  }

  /** Codec for encoding/decoding Option values to/from JSON. */
  class JsonOptionCursorValueCodec[T: Format] extends CursorValueCodec[Option[T], JsValue] {
    def encode(value: Option[T]): JsValue = value.map(Json.toJson(_)).getOrElse(JsNull)

    def decode(value: JsValue): Option[Option[T]] = value match {
      case JsNull => Some(None)
      case json   => Try(json.asOpt[T]).toOption
    }
  }

  /** Codec for encoding/decoding cursor sequences to/from JSON strings. */
  class JsonCursorCodec extends CursorCodec[JsValue] {
    def encode(values: Seq[JsValue]): String = Json.stringify(Json.toJson(values))

    def decode(cursor: String): Either[String, Seq[JsValue]] =
      Try(Json.parse(cursor)).toEither.left
        .map(err => s"Failed to parse cursor json: ${err.getMessage}")
        .flatMap(json =>
          Try(json.as[Seq[JsValue]]).toEither.left.map(err =>
            s"Cursor json has unexpected structure: ${err.getMessage}"
          )
        )
  }

  /** Trait with all JSON cursor implicits. Mix this into your profile's API object.
    *
    * Example:
    * {{{
    * trait MyProfile extends PostgresProfile 
    *   with SlickSeekerSupport 
    *   with PlayJsonSeekerSupport {
    *   
    *   object MyApi extends API with SeekImplicits with JsonSeekerImplicits
    *   
    *   override val api: MyApi.type = MyApi
    * }
    * }}}
    */
  trait JsonSeekerImplicits {
    implicit val jsonCursorValueCodecInt: JsonCursorValueCodec[Int]       = new JsonCursorValueCodec[Int]
    implicit val jsonCursorValueCodecString: JsonCursorValueCodec[String] = new JsonCursorValueCodec[String]

    implicit def jsonCursorValueCodec[T: Format]: JsonCursorValueCodec[T] = new JsonCursorValueCodec[T]

    implicit def jsonOptionCursorValueCodec[T: Format]: JsonOptionCursorValueCodec[T] =
      new JsonOptionCursorValueCodec[T]

    implicit val jsonCursorCodec: JsonCursorCodec = new JsonCursorCodec()

    /** Default cursor environment using JSON encoding with Base64 decoration.
      *
      * This is automatically available when you mix in JsonSeekerImplicits, providing a sensible default for most
      * applications.
      */
    implicit val cursorEnvironment: CursorEnvironment[JsValue] =
      CursorEnvironment(jsonCursorCodec, Base64Decorator())
  }
}
