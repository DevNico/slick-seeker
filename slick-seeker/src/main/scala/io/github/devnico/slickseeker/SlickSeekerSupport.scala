package io.github.devnico.slickseeker

import io.github.devnico.slickseeker.cursor.{CursorEnvironment, CursorValueCodec}
import io.github.devnico.slickseeker.filter.{
  ColumnSeekFilter,
  ColumnSeekFilterTypes,
  NullableSeekFilter,
  RowComparableSeekFilter,
  SeekerSortKey,
  SeekOrder
}
import io.github.devnico.slickseeker.pagination.{SeekSettings, SlickSeeker}
import slick.ast.{BaseTypedType, Ordering, ScalaBaseType, TypedType}
import slick.jdbc.{
  DB2Profile,
  DerbyProfile,
  H2Profile,
  HsqldbProfile,
  JdbcProfile,
  MySQLProfile,
  OracleProfile,
  PostgresProfile,
  SQLServerProfile,
  SQLiteProfile
}
import slick.lifted

/** SlickSeekerSupport trait provides cursor-based pagination support for Slick queries.
  *
  * Mix this trait into your Slick profile and define a cursor environment:
  *
  * {{{
  * import slick.jdbc.PostgresProfile
  * import io.github.devnico.slickseeker.SlickSeekerSupport
  * import io.github.devnico.slickseeker.playjson.PlayJsonSeekerSupport
  *
  * trait MyPostgresProfile extends PostgresProfile
  *   with SlickSeekerSupport
  *   with PlayJsonSeekerSupport {
  *
  *   object MyApi extends JdbcAPI with SeekImplicits with JsonSeekerImplicits
  *   override val api: MyApi.type = MyApi
  * }
  *
  * object MyPostgresProfile extends MyPostgresProfile
  * }}}
  *
  * Then use throughout your application with a single import:
  * {{{
  * import MyPostgresProfile.api._
  *
  * val seeker = users.toSeeker
  *   .seek(_.name.asc)
  *   .seek(_.id.asc)
  *
  * val page = db.run(seeker.page(limit = 20, cursor = None))
  * }}}
  */
trait SlickSeekerSupport { driver: JdbcProfile =>
  import driver.api._
  import ColumnSeekFilterTypes._

  // Re-export error types for convenience
  type DecodeError = CursorEnvironment.DecodeError
  val DecodeError: CursorEnvironment.DecodeError.type = CursorEnvironment.DecodeError

  /** Whether seekers compare their seek columns with one row value comparison `(a, b) > (x, y)` when all of them are
    * non-null with built-in filters and sort in the same direction (else they use `a > x OR (a = x AND b > y)`). On by
    * default for PostgreSQL and H2; override to change it, e.g. for another database that supports row values.
    */
  def seekerRowComparisons: Boolean = SlickSeekerSupport.defaultRowComparisons(driver)

  /** Whether seekers bind string-backed cursor values (`String` and types mapped to it) untyped, as literals would be,
    * so they compare with PostgreSQL enums, `uuid` and other columns mapped to `String`. On by default for PostgreSQL.
    */
  def seekerUntypedStringParams: Boolean = SlickSeekerSupport.defaultUntypedStringParams(driver)

  /** Where the database puts nulls in `direction` without a nulls clause. Seekers write it out for `Option` columns
    * without one, so pages follow the database's own order and indexes. Defaults: last ascending and first descending
    * for PostgreSQL, Oracle, DB2 and Derby (and unknown databases); first ascending and last descending for H2, MySQL,
    * SQLite and SQL Server; first in both directions for HSQLDB. Override it if your database or its configuration
    * sorts nulls differently (a wrong placement costs index use, not correctness).
    */
  def seekerDefaultNulls(direction: Ordering.Direction): Ordering.NullOrdering =
    SlickSeekerSupport.defaultNulls(driver, direction)

  /** The SQL type seekers cast a cursor value to, given its seek column's declared type (`O.SqlType`, else the type
    * Slick's DDL gives the column; for expressions, that of the first column of the same type in them, else the default
    * type of their Scala type), or `None` to compare the parameter as bound. On SQL Server, string values are cast to
    * `VARCHAR(MAX)` or `NVARCHAR(MAX)` and timestamps of `DATETIME` and `SMALLDATETIME` columns to their type, since
    * its driver binds strings as `nvarchar` and timestamps as `datetime2`, which compare differently with these columns
    * than they sort. On MySQL and MariaDB, values of single-precision `FLOAT` columns are cast to `FLOAT` (MySQL
    * 8.0.17+ and MariaDB 10.4.5+), since their drivers send them as text by default, whose `DOUBLE` value never equals
    * the column's single-precision one; `REAL`, Slick's type for `Float`, is `DOUBLE` there. `None` elsewhere. On MySQL
    * the type is written as `CAST(? AS type)`, so it must be one MySQL's `CAST` takes.
    */
  def seekerCursorValueCast(sqlType: String): Option[String] =
    SlickSeekerSupport.defaultCursorValueCast(driver, sqlType)

  // Helper to get BaseTypedType[Int]
  protected def intBaseType: BaseTypedType[Int] = ScalaBaseType.intType match {
    case btt: BaseTypedType[Int @unchecked] => btt
  }

  // Provide ColumnSeekFilter implicits with profile API in scope
  trait ColumnSeekFilterImplicits {
    // These implicits have access to driver.api._ from the outer scope

    implicit def seekFilterT[T](implicit btt: BaseTypedType[T]): ColumnSeekFilter[T] =
      new RowComparableSeekFilter[T] {
        def valueType: TypedType[T] = btt

        // Seekers bind the value and call applyParam (as compiled pages do); this is for direct calls.
        def apply(ordered: lifted.ColumnOrdered[T], value: T, prevCond: FilterCond): FilterCond =
          applyParam(ordered, SeekSettings.of(driver).bind(value, valueType), valueIsNull = false, prevCond)

        override def applyParam(
            ordered: lifted.ColumnOrdered[T],
            value: lifted.Rep[T],
            valueIsNull: Boolean,
            prevCond: FilterCond
        ): FilterCond =
          ordered.ord.direction match {
            case Ordering.Asc  => ordered.column > value || (ordered.column === value && prevCond)
            case Ordering.Desc => ordered.column < value || (ordered.column === value && prevCond)
          }
      }

    implicit def seekFilterOptT[T](implicit btt: BaseTypedType[T]): ColumnSeekFilter[Option[T]] =
      new NullableSeekFilter[Option[T]] {
        def valueType: TypedType[Option[T]] = btt.optionType

        def apply(ordered: lifted.ColumnOrdered[Option[T]], value: Option[T], prevCond: FilterCond): FilterCond =
          applyParam(ordered, SeekSettings.of(driver).bind(value, valueType), value.isEmpty, prevCond)

        override def applyParam(
            ordered: lifted.ColumnOrdered[Option[T]],
            v: lifted.Rep[Option[T]],
            valueIsNull: Boolean,
            prevCond: FilterCond
        ): FilterCond = {
          val col = ordered.column
          (ordered.ord.direction, ordered.ord.nulls, valueIsNull) match {
            case (Ordering.Asc, Ordering.NullsFirst, false) =>
              (col > v) || (col === v && prevCond)
            case (Ordering.Desc, Ordering.NullsFirst, false) =>
              (col < v) || (col === v && prevCond)
            case (_, Ordering.NullsFirst, true) =>
              (col.isEmpty && prevCond) || col.isDefined

            case (Ordering.Asc, _, false) =>
              (col > v) || (col === v && prevCond) || col.isEmpty
            case (Ordering.Desc, _, false) =>
              (col < v) || (col === v && prevCond) || col.isEmpty
            case (_, _, true) =>
              col.isEmpty && prevCond
          }
        }
      }
  }

  // Lower priority implicits for identity mappings (no custom ordering)
  trait SeekerSortKeyLowPriorityImplicits extends ColumnSeekFilterImplicits {
    // Identity mapping for types without custom ordering
    implicit def seekerSortKeyIdentity[T, CVE](implicit
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[T],
        cvc: CursorValueCodec[T, CVE]
    ): SeekerSortKey[T, T, CVE] =
      new SeekerSortKey[T, T, CVE] {
        def mapCol(col: lifted.ColumnOrdered[T]): lifted.ColumnOrdered[T] = col
        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[T], T, lifted.Rep[T]] =
          lifted.Shape.repColumnShape[T, lifted.FlatShapeLevel](using btt)
        def filter: ColumnSeekFilter[T]      = csf
        def codec: CursorValueCodec[T, CVE]  = cvc
        override def typedType: TypedType[T] = btt
      }

    // Identity mapping for Option types
    implicit def seekerSortKeyIdentityOpt[T, CVE](implicit
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[Option[T]],
        cvc: CursorValueCodec[Option[T], CVE],
        optShape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Option[T]], Option[T], lifted.Rep[Option[T]]]
    ): SeekerSortKey[Option[T], Option[T], CVE] =
      new SeekerSortKey[Option[T], Option[T], CVE] {
        def mapCol(c: lifted.ColumnOrdered[Option[T]]): lifted.ColumnOrdered[Option[T]] = c
        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Option[T]], Option[T], lifted.Rep[Option[T]]] =
          optShape
        def filter: ColumnSeekFilter[Option[T]]      = csf
        def codec: CursorValueCodec[Option[T], CVE]  = cvc
        override def typedType: TypedType[Option[T]] = btt.optionType
      }
  }

  // Higher priority implicits for custom ordering
  trait SeekerSortKeyImplicits extends SeekerSortKeyLowPriorityImplicits {
    // Custom ordering mapping (highest priority)
    implicit def seekerSortKeyOrder[T, CVE](implicit
        order: SeekOrder[T],
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[Int],
        cvc: CursorValueCodec[Int, CVE]
    ): SeekerSortKey[T, Int, CVE] =
      new SeekerSortKey[T, Int, CVE] {
        def mapCol(c: lifted.ColumnOrdered[T]): lifted.ColumnOrdered[Int] = {
          val vs = order.orderedValues
          require(vs.nonEmpty, "SeekOrder: empty domain")
          val head                                 = vs.head
          implicit val intType: BaseTypedType[Int] = intBaseType
          val rep: lifted.Rep[Int] =
            vs.tail
              .foldLeft(
                lifted.Case.If(c.column === head).Then(order.index(head))
              )((acc, v) => acc.If(c.column === v).Then(order.index(v)))
              .Else(0)
          lifted.ColumnOrdered(rep, c.ord)
        }

        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Int], Int, lifted.Rep[Int]] = {
          val intType = intBaseType
          lifted.Shape.repColumnShape[Int, lifted.FlatShapeLevel](using intType)
        }

        def filter: ColumnSeekFilter[Int]      = csf
        def codec: CursorValueCodec[Int, CVE]  = cvc
        override def typedType: TypedType[Int] = intBaseType
      }

    // Custom ordering for Option types
    implicit def seekerSortKeyOptOrder[T, CVE](implicit
        order: SeekOrder[T],
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[Int],
        cvc: CursorValueCodec[Int, CVE]
    ): SeekerSortKey[Option[T], Int, CVE] =
      new SeekerSortKey[Option[T], Int, CVE] {
        def mapCol(c: lifted.ColumnOrdered[Option[T]]): lifted.ColumnOrdered[Int] = {
          val vs                                   = order.orderedValues
          val head                                 = vs.head
          implicit val intType: BaseTypedType[Int] = intBaseType
          val rep: lifted.Rep[Int] =
            vs.tail
              .foldLeft(
                lifted.Case.If(c.column === lifted.LiteralColumn(Option(head))).Then(order.index(head))
              )((acc, v) => acc.If(c.column === lifted.LiteralColumn(Option(v))).Then(order.index(v)))
              .Else(0)
          lifted.ColumnOrdered(rep, c.ord)
        }

        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Int], Int, lifted.Rep[Int]] = {
          val intType = intBaseType
          lifted.Shape.repColumnShape[Int, lifted.FlatShapeLevel](using intType)
        }

        def filter: ColumnSeekFilter[Int]      = csf
        def codec: CursorValueCodec[Int, CVE]  = cvc
        override def typedType: TypedType[Int] = intBaseType
      }
  }

  /** Trait with all core SlickSeeker implicits for column seek operations.
    *
    * Mix this into your profile's API object:
    * {{{
    * trait MyProfile extends PostgresProfile with SlickSeekerSupport {
    *   object MyApi extends JdbcAPI with SeekImplicits
    *   override val api: MyApi.type = MyApi
    * }
    * }}}
    */
  trait SeekImplicits extends SeekerSortKeyImplicits {

    /** Extension methods for Query to add `.toSeeker` method. */
    implicit class QuerySeekerOps[E, U](query: lifted.Query[E, U, Seq]) {
      def toSeeker[CVE](implicit
          cursorEnvironment: CursorEnvironment[CVE],
          shape: lifted.Shape[lifted.FlatShapeLevel, E, U, E],
          baseTypedType: BaseTypedType[Int]
      ): SlickSeeker[E, U, CVE, lifted.Rep[Int], Int] = SlickSeeker(query)

      /** Create a PostgreSQL tuple-optimized seeker with ASC ordering.
        *
        * Enforces type-level constraints:
        *   - All columns must be non-nullable (Option[T] will fail at compile time)
        *   - All columns must be ASC (enforced by type parameter)
        */
      @deprecated("toSeeker compares the same columns as a row value: use toSeeker.seek(_.col.asc)", "0.7.2")
      def toPgTupleSeekerAsc[CVE](implicit
          cursorEnvironment: CursorEnvironment[CVE],
          shape: lifted.Shape[lifted.FlatShapeLevel, E, U, E],
          baseTypedType: BaseTypedType[Int]
      ): pagination.SlickPgTupleSeeker[E, U, CVE, lifted.Rep[Int], Int, slick.ast.Ordering.Asc.type] =
        pagination.SlickPgTupleSeeker(
          query,
          Vector.empty,
          support.QueryWithCursor.seed[E, U, CVE],
          slick.ast.Ordering.Asc
        )

      /** Create a PostgreSQL tuple-optimized seeker with DESC ordering.
        *
        * Enforces type-level constraints:
        *   - All columns must be non-nullable (Option[T] will fail at compile time)
        *   - All columns must be DESC (enforced by type parameter)
        */
      @deprecated("toSeeker compares the same columns as a row value: use toSeeker.seek(_.col.desc)", "0.7.2")
      def toPgTupleSeekerDesc[CVE](implicit
          cursorEnvironment: CursorEnvironment[CVE],
          shape: lifted.Shape[lifted.FlatShapeLevel, E, U, E],
          baseTypedType: BaseTypedType[Int]
      ): pagination.SlickPgTupleSeeker[E, U, CVE, lifted.Rep[Int], Int, slick.ast.Ordering.Desc.type] =
        pagination.SlickPgTupleSeeker(
          query,
          Vector.empty,
          support.QueryWithCursor.seed[E, U, CVE],
          slick.ast.Ordering.Desc
        )
    }
  }

  // Make the profile available for the page method
  implicit def jdbcProfile: JdbcProfile = driver
}

object SlickSeekerSupport {
  private[slickseeker] def defaultRowComparisons(profile: JdbcProfile): Boolean = profile match {
    case _: PostgresProfile | _: H2Profile => true
    case _                                 => false
  }

  private[slickseeker] def defaultUntypedStringParams(profile: JdbcProfile): Boolean =
    profile.isInstanceOf[PostgresProfile]

  private[slickseeker] def defaultNulls(profile: JdbcProfile, direction: Ordering.Direction): Ordering.NullOrdering = {
    val asc = direction == Ordering.Asc
    profile match {
      case _: HsqldbProfile => Ordering.NullsFirst
      case _: H2Profile | _: MySQLProfile | _: SQLiteProfile | _: SQLServerProfile =>
        if (asc) Ordering.NullsFirst else Ordering.NullsLast
      case _ => if (asc) Ordering.NullsLast else Ordering.NullsFirst
    }
  }

  private val sqlServerNationalString = "(?i)\\s*(nvarchar|nchar|national\\b).*".r
  private val sqlServerString         = "(?i)\\s*(varchar|char|character)\\b.*".r
  private val sqlServerDatetime       = "(?i)\\s*(datetime|smalldatetime)\\b.*".r

  // FLOAT, FLOAT(p) with p up to 24 and FLOAT(M,D): single precision (FLOAT(25) to FLOAT(53) are DOUBLE)
  private val mySqlFloat = "(?i)\\s*float\\b(?:\\s*\\(\\s*(\\d+)\\s*(,[^)]*)?\\))?.*".r

  private[slickseeker] def defaultCursorValueCast(profile: JdbcProfile, sqlType: String): Option[String] =
    profile match {
      case _: SQLServerProfile =>
        sqlType match {
          case sqlServerNationalString(_) => Some("NVARCHAR(MAX)")
          case sqlServerString(_)         => Some("VARCHAR(MAX)")
          case sqlServerDatetime(tpe)     => Some(tpe.toUpperCase)
          case _                          => None
        }
      case _: MySQLProfile =>
        sqlType match {
          case mySqlFloat(precision, scale) if scale != null || precision == null || precision.toInt <= 24 =>
            Some("FLOAT")
          case _ => None
        }
      case _ => None
    }
}
