package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.filter.{ColumnSeekFilter, ColumnSeekFilterTypes, SeekerSortKey}
import io.github.devnico.slickseeker.support.{MyH2Profile, SortDirection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

// Custom filters on Option columns without a nulls clause, on H2, which sorts nulls first ascending.
class CustomFilterSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val ec: ExecutionContext = ExecutionContext.global

  implicit val intCodec: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = value.toIntOption
  }
  implicit val optStrCodec: CursorValueCodec[Option[String], String] = new CursorValueCodec[Option[String], String] {
    def encode(value: Option[String]): String = value.fold("N")("S" + _)
    def decode(value: String): Option[Option[String]] =
      if (value == "N") Some(None) else if (value.startsWith("S")) Some(Some(value.drop(1))) else None
  }

  class Items(t: Tag) extends Table[(Int, Option[String])](t, "custom_filter_items") {
    def id  = column[Int]("id", O.PrimaryKey)
    def tag = column[Option[String]]("tag")
    def *   = (id, tag)
  }
  val items = TableQuery[Items]
  val db    = Database.forConfig("h2mem")

  def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  override def beforeAll(): Unit =
    await(db.run(DBIO.seq(
      items.schema.dropIfExists,
      items.schema.create,
      items ++= (1 to 20).map(i => (i, Option.when(i % 3 != 0)(s"t${i % 4}")))
    )))
  override def afterAll(): Unit = db.close()

  // A custom filter (not one of the built-in ones) that compares like the built-in Option filter
  val builtIn = MyH2Profile.api.seekerSortKeyIdentityOpt[String, String]
  val custom: SeekerSortKey[Option[String], Option[String], String] =
    new SeekerSortKey[Option[String], Option[String], String] {
      def mapCol(col: slick.lifted.ColumnOrdered[Option[String]]) = builtIn.mapCol(col)
      def shape                                                   = builtIn.shape
      def codec: CursorValueCodec[Option[String], String]         = builtIn.codec
      def typedType                                               = builtIn.typedType
      def filter: ColumnSeekFilter[Option[String]] = new ColumnSeekFilter[Option[String]] {
        def apply(
            column: slick.lifted.ColumnOrdered[Option[String]],
            value: Rep[Option[String]],
            valueIsNull: Boolean,
            prevCond: ColumnSeekFilterTypes.FilterCond
        ) = builtIn.filter(column, value, valueIsNull, prevCond)
      }
    }

  def walk(run: Option[String] => PaginatedResultWithoutCount[Int]): (Vector[Int], Vector[Int]) = {
    @tailrec def forward(
        cursor: Option[String],
        pages: Vector[PaginatedResultWithoutCount[Int]]
    ): Vector[PaginatedResultWithoutCount[Int]] = {
      val page = run(cursor)
      if (page.nextCursor.isEmpty) pages :+ page else forward(page.nextCursor, pages :+ page)
    }
    @tailrec def backward(cursor: Option[String], ids: Vector[Int]): Vector[Int] = cursor match {
      case None => ids
      case Some(_) =>
        val page = run(cursor)
        backward(page.prevCursor, page.items.toVector ++ ids)
    }
    val pages = forward(None, Vector.empty)
    (pages.flatMap(_.items), backward(pages.last.prevCursor, pages.last.items.toVector))
  }

  "Custom filters on Option columns without a nulls clause" should {
    Seq[(
        String,
        Query[Items, (Int, Option[String]), Seq] => SlickSeeker[Items, (Int, Option[String]), String, ?, ?],
        Query[Items, (Int, Option[String]), Seq]
    )](
      ("tag asc", _.toSeeker.seek(_.tag.asc)(custom).seek(_.id.asc), items.sortBy(r => (r.tag.asc, r.id.asc))),
      ("tag desc", _.toSeeker.seek(_.tag.desc)(custom).seek(_.id.asc), items.sortBy(r => (r.tag.desc, r.id.asc))),
      (
        "upper(tag) asc",
        _.toSeeker.seek(_.tag.toUpperCase.asc)(custom).seek(_.id.asc),
        items.sortBy(r => (r.tag.toUpperCase.asc, r.id.asc))
      ),
      (
        "case tag desc",
        _.toSeeker.seek(r => Case.If(r.id > 0).Then(r.tag).Else(Option.empty[String]).desc)(custom).seek(_.id.asc),
        items.sortBy(r => (r.tag.desc, r.id.asc))
      )
    ).foreach { case (name, seeker, native) =>
      s"page every row once in the database's order, forward and backward: $name" in {
        val expected            = await(db.run(native.map(_.id).result)).toVector
        val (forward, backward) = walk(c => await(db.run(seeker(items).pageWithoutCountAs(3, c)(_.id))))
        forward shouldBe expected
        backward shouldBe expected
        val compiled = CompiledSeekPage[Unit]((_: Unit) => seeker(items))(_.id)
        walk(c => await(db.run(compiled.pageWithoutCount((), 3, c)))) shouldBe ((expected, expected))
      }
    }

    "page them with seekDirection in compiled pages" in {
      Seq[(SlickSeeker[Items, (Int, Option[String]), String, ?, ?], Query[Items, (Int, Option[String]), Seq])](
        (items.toSeeker.seek(_.tag.asc)(custom).seek(_.id.asc), items.sortBy(r => (r.tag.asc, r.id.asc))),
        (items.toSeeker.seek(_.tag.desc)(custom).seek(_.id.asc), items.sortBy(r => (r.tag.desc, r.id.asc))),
        (
          items.toSeeker.seek(_.tag)(custom).seek(_.id).seekDirection(SortDirection.Desc),
          items.sortBy(r => (r.tag.desc, r.id.desc))
        )
      ).foreach { case (seeker, native) =>
        val expected = await(db.run(native.map(_.id).result)).toVector
        val compiled = CompiledSeekPage[Unit]((_: Unit) => seeker)(_.id)
        compiled.maxForms shouldBe 5
        walk(c => await(db.run(compiled.pageWithoutCount((), 3, c)))) shouldBe ((expected, expected))
      }
    }

    "page a column of a mapped query in the database's order, forward and backward" in {
      val seeker   = items.map(r => (r.id, r.tag)).toSeeker.seek(_._2.asc)(custom).seek(_._1.asc)
      val expected = await(db.run(items.sortBy(r => (r.tag.asc, r.id.asc)).map(_.id).result)).toVector
      walk(c => await(db.run(seeker.pageWithoutCountAs(3, c)(_._1)))) shouldBe ((expected, expected))
    }
  }

  "Custom filters on non-null columns" should {
    "get no nulls clause on backward pages, which MySQL, SQLite and SQL Server would emulate" in {
      val builtInId = MyH2Profile.api.seekerSortKeyIdentity[Int, String]
      val customId: SeekerSortKey[Int, Int, String] = new SeekerSortKey[Int, Int, String] {
        def mapCol(col: slick.lifted.ColumnOrdered[Int]) = builtInId.mapCol(col)
        def shape                                        = builtInId.shape
        def codec: CursorValueCodec[Int, String]         = builtInId.codec
        def typedType                                    = builtInId.typedType
        def filter: ColumnSeekFilter[Int] = new ColumnSeekFilter[Int] {
          def apply(
              column: slick.lifted.ColumnOrdered[Int],
              value: Rep[Int],
              valueIsNull: Boolean,
              prevCond: ColumnSeekFilterTypes.FilterCond
          ) = builtInId.filter(column, value, valueIsNull, prevCond)
        }
      }
      val env     = implicitly[CursorEnvironment[String]]
      val decoded = env.decodeWithDirectionOrThrow(Some(env.encode(Seq("5"), CursorDirection.Backward)))
      Seq(slick.jdbc.MySQLProfile, slick.jdbc.SQLiteProfile, slick.jdbc.SQLServerProfile).foreach { profile =>
        Seq(
          items.toSeeker.seek(_.id.asc)(customId),
          items.toSeeker.seek(_.id)(customId).seekDirection(io.github.devnico.slickseeker.support.SortDirection.Desc)
        ).foreach { seeker =>
          val query = seeker.pageQuery(decoded, 2, SeekSettings.of(profile))
          val sql   = profile.api.streamableQueryActionExtensionMethods(query).result.statements.head.toLowerCase
          sql should not include "null"
        }
      }
    }
  }
}
