package io.github.devnico.slickseeker.playjson

import io.github.devnico.slickseeker._
import play.api.libs.json.{Format, JsNull, JsValue, Json}

import scala.util.Try

object PlayJsonSupport {

  class JsonCursorValueCodec[T: Format] extends CursorValueCodec[T, JsValue] {
    def encode(value: T): JsValue         = Json.toJson(value)
    def decode(value: JsValue): Option[T] = value.asOpt[T]
  }

  class JsonOptionCursorValueCodec[T: Format] extends CursorValueCodec[Option[T], JsValue] {
    def encode(value: Option[T]): JsValue = value.map(Json.toJson(_)).getOrElse(JsNull)

    def decode(value: JsValue): Option[Option[T]] = value match {
      case JsNull => Some(None)
      case json   => Try(json.asOpt[T]).toOption
    }

  }

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

  implicit val jsonCursorValueCodecInt: JsonCursorValueCodec[Int]                   = new JsonCursorValueCodec[Int]
  implicit val jsonCursorValueCodecString: JsonCursorValueCodec[String]             = new JsonCursorValueCodec[String]
  implicit def jsonCursorValueCodec[T: Format]: JsonCursorValueCodec[T]             = new JsonCursorValueCodec[T]
  implicit def jsonOptionCursorValueCodec[T: Format]: JsonOptionCursorValueCodec[T] = new JsonOptionCursorValueCodec[T]
  implicit val jsonCursorCodec: JsonCursorCodec                                     = new JsonCursorCodec()

  /** Create a cursor environment with Play JSON codec and custom decorator.
    *
    * Example:
    * {{{
    * implicit val cursorEnv: CursorEnvironment[JsValue] = PlayJsonSupport.cursorEnvironment()
    * // or with custom decorator:
    * implicit val cursorEnv: CursorEnvironment[JsValue] = PlayJsonSupport.cursorEnvironment(IdentityDecorator())
    * }}}
    */
  def cursorEnvironment(decorator: CursorDecorator = Base64Decorator()): CursorEnvironment[JsValue] =
    CursorEnvironment(jsonCursorCodec, decorator)

  /** Default cursor environment using JSON encoding with Base64 decoration. Use this for convenience, or create your
    * own with cursorEnvironment().
    */
  implicit val defaultJsonCursorEnvironment: CursorEnvironment[JsValue] = cursorEnvironment()

}
