package io.github.devnico.slickseeker.support

import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor._
import slick.jdbc.PostgresProfile

/** PostgreSQL test profile (see [[MyH2Profile]]). */
trait MyPgProfile extends PostgresProfile with SlickSeekerSupport {

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

object MyPgProfile extends MyPgProfile

/** Without row value comparisons: seekers always chain their conditions. */
object ChainPgProfile extends MyPgProfile {
  override def seekerRowComparisons: Boolean = false
}

/** Cursor parameters bound with the column's JDBC type (`varchar` for strings), as before untyped string parameters. */
object TypedParamsPgProfile extends MyPgProfile {
  override def seekerUntypedStringParams: Boolean = false
}

/** Assuming nulls sort low, though PostgreSQL sorts them high: pages get an order no index serves. */
object NullsLowPgProfile extends MyPgProfile {
  override def seekerNullsSortHigh: Boolean = false
}
