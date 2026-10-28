package io.github.devnico.slickseeker.support

import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor._
import slick.jdbc.HsqldbProfile

/** HSQLDB test profile (see [[MyH2Profile]]). */
trait MyHsqldbProfile extends HsqldbProfile with SlickSeekerSupport {

  object MyApi extends JdbcAPI with SeekImplicits {
    implicit val stringCursorCodec: CursorCodec[String] = new CursorCodec[String] {
      def encode(values: Seq[String]): String = values.mkString("|")
      def decode(cursor: String): Either[String, Seq[String]] =
        Right(if (cursor.isEmpty) Seq.empty else cursor.split("\\|").toSeq)
    }

    implicit val cursorEnv: CursorEnvironment[String] = CursorEnvironment(stringCursorCodec, Base64Decorator())
  }

  override val api: MyApi.type = MyApi
}

object MyHsqldbProfile extends MyHsqldbProfile
