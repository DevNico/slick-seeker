package io.github.devnico.slickseeker.support

import slick.jdbc.H2Profile
import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor._

/** Test profile demonstrating the recommended pattern.
  *
  * To use SlickSeeker, import the profile API:
  * {{{
  * import MyH2Profile.api._
  * }}}
  */
trait MyH2Profile extends H2Profile with SlickSeekerSupport {

  object MyApi extends JdbcAPI with SeekImplicits {
    // Simple string-based cursor codec for tests
    implicit val stringCursorCodec: CursorCodec[String] = new CursorCodec[String] {
      def encode(values: Seq[String]): String = values.mkString("|")
      def decode(cursor: String): Either[String, Seq[String]] =
        Right(if (cursor.isEmpty) Seq.empty else cursor.split("\\|").toSeq)
    }

    implicit val cursorEnv: CursorEnvironment[String] =
      CursorEnvironment(stringCursorCodec, Base64Decorator())
  }

  override val api: MyApi.type = MyApi
}

object MyH2Profile extends MyH2Profile
