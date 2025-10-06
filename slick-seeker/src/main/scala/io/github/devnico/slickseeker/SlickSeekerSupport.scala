package io.github.devnico.slickseeker

import io.github.devnico.slickseeker.cursor.{CursorEnvironment, CursorValueCodec}
import io.github.devnico.slickseeker.filter.{ColumnSeekFilter, ColumnSeekFilterTypes, SeekerSortKey, SeekOrder}
import io.github.devnico.slickseeker.pagination.SlickSeeker
import slick.ast.{BaseTypedType, Ordering, ScalaBaseType}
import slick.jdbc.JdbcProfile
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
  *   object MyAPI extends super.API with SeekImplicits with JsonSeekerImplicits
  *   override val api: MyAPI = MyAPI
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

  // Helper to get BaseTypedType[Int]
  protected def intBaseType: BaseTypedType[Int] = ScalaBaseType.intType match {
    case btt: BaseTypedType[Int @unchecked] => btt
  }

  // Provide ColumnSeekFilter implicits with profile API in scope
  trait ColumnSeekFilterImplicits {
    // These implicits have access to driver.api._ from the outer scope

    implicit def seekFilterT[T](implicit btt: BaseTypedType[T]): ColumnSeekFilter[T] =
      new ColumnSeekFilter[T] {
        def apply(ordered: lifted.ColumnOrdered[T], value: T, prevCond: FilterCond): FilterCond =
          ordered.ord.direction match {
            case Ordering.Asc  => ordered.column > value || (ordered.column === value && prevCond)
            case Ordering.Desc => ordered.column < value || (ordered.column === value && prevCond)
          }
      }

    implicit def seekFilterOptT[T](implicit btt: BaseTypedType[T]): ColumnSeekFilter[Option[T]] =
      new ColumnSeekFilter[Option[T]] {
        def apply(
            ordered: lifted.ColumnOrdered[Option[T]],
            value: Option[T],
            prevCond: FilterCond
        ): FilterCond = {
          val col = ordered.column
          (ordered.ord.direction, ordered.ord.nulls, value) match {
            case (Ordering.Asc, Ordering.NullsFirst, Some(v)) =>
              (col > v) || (col === v && prevCond)
            case (Ordering.Desc, Ordering.NullsFirst, Some(v)) =>
              (col < v) || (col === v && prevCond)
            case (_, Ordering.NullsFirst, None) =>
              (col.isEmpty && prevCond) || col.isDefined

            case (Ordering.Asc, Ordering.NullsLast, Some(v)) =>
              (col > v) || (col === v && prevCond) || col.isEmpty
            case (Ordering.Desc, Ordering.NullsLast, Some(v)) =>
              (col < v) || (col === v && prevCond) || col.isEmpty
            case (_, Ordering.NullsLast, None) =>
              col.isEmpty && prevCond

            case (Ordering.Asc, Ordering.NullsDefault, Some(v)) =>
              (col > v) || (col === v && prevCond) || col.isEmpty
            case (Ordering.Desc, Ordering.NullsDefault, Some(v)) =>
              (col < v) || (col === v && prevCond) || col.isEmpty
            case (_, Ordering.NullsDefault, None) =>
              col.isEmpty && prevCond
          }
        }
      }
  }

  // Provide SeekerSortKey implicits with profile API in scope
  trait SeekerSortKeyImplicits extends ColumnSeekFilterImplicits {
    // Identity mapping for types without custom ordering (higher priority)
    implicit def seekerSortKeyIdentity[T, CVE](implicit
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[T],
        cvc: CursorValueCodec[T, CVE]
    ): SeekerSortKey[T, CVE] =
      new SeekerSortKey[T, CVE] {
        type Key = T
        def mapCol(col: lifted.ColumnOrdered[T]): lifted.ColumnOrdered[Key] = col
        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Key], Key, lifted.Rep[Key]] =
          lifted.Shape.repColumnShape[T, lifted.FlatShapeLevel](using btt)
        def filter: ColumnSeekFilter[T]       = csf
        def codec: CursorValueCodec[Key, CVE] = cvc
      }

    // Identity mapping for Option types
    implicit def seekerSortKeyIdentityOpt[T, CVE](implicit
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[Option[T]],
        cvc: CursorValueCodec[Option[T], CVE],
        optShape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Option[T]], Option[T], lifted.Rep[Option[T]]]
    ): SeekerSortKey[Option[T], CVE] =
      new SeekerSortKey[Option[T], CVE] {
        type Key = Option[T]
        def mapCol(c: lifted.ColumnOrdered[Option[T]]): lifted.ColumnOrdered[Key]             = c
        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Key], Key, lifted.Rep[Key]] = optShape
        def filter: ColumnSeekFilter[Option[T]]                                               = csf
        def codec: CursorValueCodec[Option[T], CVE]                                           = cvc
      }

    // Custom ordering mapping (highest priority)
    implicit def seekerSortKeyOrder[T, CVE](implicit
        order: SeekOrder[T],
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[Int],
        cvc: CursorValueCodec[Int, CVE]
    ): SeekerSortKey[T, CVE] =
      new SeekerSortKey[T, CVE] {
        type Key = Int

        def mapCol(c: lifted.ColumnOrdered[T]): lifted.ColumnOrdered[Key] = {
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

        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Key], Key, lifted.Rep[Key]] = {
          val intType = intBaseType
          lifted.Shape.repColumnShape[Int, lifted.FlatShapeLevel](using intType)
        }

        def filter: ColumnSeekFilter[Int]     = csf
        def codec: CursorValueCodec[Key, CVE] = cvc
      }

    // Custom ordering for Option types
    implicit def seekerSortKeyOptOrder[T, CVE](implicit
        order: SeekOrder[T],
        btt: BaseTypedType[T],
        csf: ColumnSeekFilter[Int],
        cvc: CursorValueCodec[Int, CVE]
    ): SeekerSortKey[Option[T], CVE] =
      new SeekerSortKey[Option[T], CVE] {
        type Key = Int

        def mapCol(c: lifted.ColumnOrdered[Option[T]]): lifted.ColumnOrdered[Key] = {
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

        def shape: lifted.Shape[lifted.FlatShapeLevel, lifted.Rep[Key], Key, lifted.Rep[Key]] = {
          val intType = intBaseType
          lifted.Shape.repColumnShape[Int, lifted.FlatShapeLevel](using intType)
        }

        def filter: ColumnSeekFilter[Int]     = csf
        def codec: CursorValueCodec[Key, CVE] = cvc
      }
  }

  /** Trait with all core SlickSeeker implicits for column seek operations.
    *
    * Mix this into your profile's API object:
    * {{{
    * trait MyProfile extends PostgresProfile with SlickSeekerSupport {
    *   object MyApi extends API with SeekImplicits
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
    }
  }

  // Make the profile available for the page method
  implicit def jdbcProfile: JdbcProfile = driver
}
