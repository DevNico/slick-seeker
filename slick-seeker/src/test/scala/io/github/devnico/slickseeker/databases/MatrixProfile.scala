package io.github.devnico.slickseeker.databases

import io.github.devnico.slickseeker.SlickSeekerSupport
import io.github.devnico.slickseeker.cursor._
import slick.ast.Ordering
import slick.jdbc._

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime}
import java.util.UUID

/** A profile of the database suites: cursors of URL-encoded strings, and cursor value codecs for the tested types. */
trait MatrixProfile extends JdbcProfile with SlickSeekerSupport {

  object MatrixApi extends JdbcAPI with SeekImplicits with MatrixCodecs {
    implicit val cursorEnv: CursorEnvironment[String] = CursorEnvironment(MatrixCodecs.cursorCodec, Base64Decorator())
  }

  override val api: MatrixApi.type = MatrixApi
}

object MatrixProfile {
  object H2         extends H2Profile with MatrixProfile
  object Hsqldb     extends HsqldbProfile with MatrixProfile
  object SQLite     extends SQLiteProfile with MatrixProfile
  object Derby      extends DerbyProfile with MatrixProfile
  object PostgreSql extends PostgresProfile with MatrixProfile
  object MySql      extends MySQLProfile with MatrixProfile
  object SqlServer  extends SQLServerProfile with MatrixProfile
  object Oracle     extends OracleProfile with JdbcActionComponent.OneRowPerStatementOnly with MatrixProfile

  /** SQL Server without casting cursor values: the driver's types decide how they compare (a control). */
  object SqlServerWithoutCasts extends SQLServerProfile with MatrixProfile {
    override def seekerCursorValueCast(sqlType: String): Option[String] = None
  }

  /** CockroachDB uses PostgresProfile, but sorts nulls first ascending: the override the docs recommend. */
  object CockroachDb extends PostgresProfile with MatrixProfile {
    override def seekerDefaultNulls(direction: Ordering.Direction): Ordering.NullOrdering =
      if (direction == Ordering.Asc) Ordering.NullsFirst else Ordering.NullsLast
  }
}

trait MatrixCodecs {
  private def codec[T](enc: T => String, dec: String => Option[T]): CursorValueCodec[T, String] =
    new CursorValueCodec[T, String] {
      def encode(value: T): String         = enc(value)
      def decode(value: String): Option[T] = dec(value)
    }

  implicit val intCodec: CursorValueCodec[Int, String]         = codec(_.toString, _.toIntOption)
  implicit val shortCodec: CursorValueCodec[Short, String]     = codec(_.toString, _.toShortOption)
  implicit val stringCodec: CursorValueCodec[String, String]   = codec(identity, Some(_))
  implicit val booleanCodec: CursorValueCodec[Boolean, String] = codec(_.toString, _.toBooleanOption)
  implicit val floatCodec: CursorValueCodec[Float, String]     = codec(_.toString, _.toFloatOption)
  implicit val doubleCodec: CursorValueCodec[Double, String]   = codec(_.toString, _.toDoubleOption)
  implicit val bigDecimalCodec: CursorValueCodec[BigDecimal, String] =
    codec(_.toString, s => scala.util.Try(BigDecimal(s)).toOption)
  implicit val localDateTimeCodec: CursorValueCodec[LocalDateTime, String] =
    codec(_.toString, s => scala.util.Try(LocalDateTime.parse(s)).toOption)
  implicit val localDateCodec: CursorValueCodec[LocalDate, String] =
    codec(_.toString, s => scala.util.Try(LocalDate.parse(s)).toOption)
  implicit val localTimeCodec: CursorValueCodec[LocalTime, String] =
    codec(_.toString, s => scala.util.Try(LocalTime.parse(s)).toOption)
  implicit val offsetDateTimeCodec: CursorValueCodec[OffsetDateTime, String] =
    codec(_.toString, s => scala.util.Try(OffsetDateTime.parse(s)).toOption)
  implicit val uuidCodec: CursorValueCodec[UUID, String] =
    codec(_.toString, s => scala.util.Try(UUID.fromString(s)).toOption)

  implicit def optionCodec[T](implicit inner: CursorValueCodec[T, String]): CursorValueCodec[Option[T], String] =
    codec(_.fold("-")("+" + inner.encode(_)), s => if (s == "-") Some(None) else inner.decode(s.drop(1)).map(Some(_)))
}

object MatrixCodecs {

  /** Cursor values URL-encoded and joined with `,`, so any string round-trips. */
  val cursorCodec: CursorCodec[String] = new CursorCodec[String] {
    def encode(values: Seq[String]): String = values.map(URLEncoder.encode(_, UTF_8.name)).mkString(",")
    def decode(cursor: String): Either[String, Seq[String]] =
      Right(if (cursor.isEmpty) Seq.empty else cursor.split(",", -1).toSeq.map(URLDecoder.decode(_, UTF_8.name)))
  }
}
