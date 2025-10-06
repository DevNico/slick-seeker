package io.github.devnico.slickseeker.support

import slick.jdbc.H2Profile
import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor._
import scala.util.Try

/** Test profile that demonstrates the recommended pattern.
  *
  * Define cursor environment inside your profile, similar to how you would define custom database type mappings.
  *
  * To use SlickSeeker, import the profile API:
  * {{{
  * import MyH2Profile.api._
  * import MyH2Profile.Implicits._
  * }}}
  */
object MyH2Profile extends H2Profile with SlickSeekerSupport {

  // Cursor codec for tests - simple string-based encoding
  case class StringCursorCodec() extends CursorCodec[String] {
    def encode(values: Seq[String]): String = values.mkString("|")
    def decode(cursor: String): Either[String, Seq[String]] =
      Right(if (cursor.isEmpty) Seq.empty else cursor.split("\\|").toSeq)
  }

  // Define cursor environment inside the profile (like custom DB types)
  implicit val cursorEnv: CursorEnvironment[String] =
    CursorEnvironment(StringCursorCodec(), Base64Decorator())
}
