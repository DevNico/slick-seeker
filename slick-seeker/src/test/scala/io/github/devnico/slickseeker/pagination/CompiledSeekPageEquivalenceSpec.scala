package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.cursor._
import io.github.devnico.slickseeker.filter.{ColumnSeekFilter, SeekOrder, SeekerSortKey}
import io.github.devnico.slickseeker.support.{ChainH2Profile, MyH2Profile, SortDirection}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import MyH2Profile.api._
import slick.lifted.{ColumnsShapeLevel, FlatShapeLevel, Shape}

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

sealed trait Prio
object Prio {
  case object Low  extends Prio
  case object Mid  extends Prio
  case object High extends Prio
}

// Compiled pages against ad-hoc pages for every reachable cursor of many seeker configurations, with row value
// comparisons (where a configuration qualifies) against chained conditions only.
// In this spec `===` is scalactic's (Matchers): use `=!=` / `!(…)` in Slick predicates.
class CompiledSeekPageEquivalenceSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  implicit val ec: ExecutionContext = ExecutionContext.global

  implicit val intCodec: CursorValueCodec[Int, String] = new CursorValueCodec[Int, String] {
    def encode(value: Int): String         = value.toString
    def decode(value: String): Option[Int] = value.toIntOption
  }
  implicit val strCodec: CursorValueCodec[String, String] = new CursorValueCodec[String, String] {
    def encode(value: String): String         = value
    def decode(value: String): Option[String] = Some(value)
  }
  implicit val optStrCodec: CursorValueCodec[Option[String], String] = new CursorValueCodec[Option[String], String] {
    def encode(value: Option[String]): String = value.fold("N")("S" + _)
    def decode(value: String): Option[Option[String]] =
      if (value == "N") Some(None) else if (value.startsWith("S")) Some(Some(value.drop(1))) else None
  }
  implicit val optIntCodec: CursorValueCodec[Option[Int], String] = new CursorValueCodec[Option[Int], String] {
    def encode(value: Option[Int]): String = value.fold("N")("I" + _)
    def decode(value: String): Option[Option[Int]] =
      if (value == "N") Some(None) else if (value.startsWith("I")) value.drop(1).toIntOption.map(Some(_)) else None
  }

  implicit val prioType: BaseColumnType[Prio] = MappedColumnType.base[Prio, String](
    { case Prio.Low => "low"; case Prio.Mid => "mid"; case Prio.High => "high" },
    { case "low" => Prio.Low; case "mid" => Prio.Mid; case "high" => Prio.High }
  )
  implicit val prioOrder: SeekOrder[Prio] = SeekOrder(IndexedSeq(Prio.High, Prio.Low, Prio.Mid))

  case class Row(id: Int, name: String, tag: Option[String], score: Option[Int], prio: Prio, oprio: Option[Prio])

  class Rows(t: Tag) extends Table[Row](t, "compiled_equivalence_rows") {
    def id    = column[Int]("id", O.PrimaryKey)
    def name  = column[String]("name")
    def tag   = column[Option[String]]("tag")
    def score = column[Option[Int]]("score")
    def prio  = column[Prio]("prio")
    def oprio = column[Option[Prio]]("oprio")
    def *     = (id, name, tag, score, prio, oprio).mapTo[Row]
  }
  val rows = TableQuery[Rows]
  val db   = Database.forConfig("h2mem")

  val prios = Vector(Prio.Low, Prio.Mid, Prio.High)
  val data = (1 to 23).map { i =>
    Row(
      i,
      s"n${i % 4}",
      if (i % 3 == 0) None else Some(s"t${i % 5}"),
      if (i % 4 == 0) None else Some(i % 6),
      prios(i % 3),
      if (i % 5 == 0) None else Some(prios((i + 1) % 3))
    )
  }

  override def beforeAll(): Unit =
    Await.result(db.run(DBIO.seq(rows.schema.dropIfExists, rows.schema.create, rows ++= data)), 10.seconds)
  override def afterAll(): Unit = db.close()
  def await[T](f: Future[T]): T = Await.result(f, 10.seconds)

  val configs: Seq[(String, Query[Rows, Row, Seq] => SlickSeeker[Rows, Row, String, ?, ?])] = Seq(
    "tag asc"                 -> (_.toSeeker.seek(_.tag.asc).seek(_.id.asc)),
    "tag desc"                -> (_.toSeeker.seek(_.tag.desc).seek(_.id.asc)),
    "tag asc nullsFirst"      -> (_.toSeeker.seek(_.tag.asc.nullsFirst).seek(_.id.asc)),
    "tag asc nullsLast"       -> (_.toSeeker.seek(_.tag.asc.nullsLast).seek(_.id.asc)),
    "tag desc nullsFirst"     -> (_.toSeeker.seek(_.tag.desc.nullsFirst).seek(_.id.desc)),
    "tag desc nullsLast"      -> (_.toSeeker.seek(_.tag.desc.nullsLast).seek(_.id.desc)),
    "tag + score mixed"       -> (_.toSeeker.seek(_.tag.asc.nullsFirst).seek(_.score.desc.nullsLast).seek(_.id.asc)),
    "name desc, score asc nf" -> (_.toSeeker.seek(_.name.desc).seek(_.score.asc.nullsFirst).seek(_.id.desc)),
    "SeekOrder"               -> (_.toSeeker.seek(_.prio.asc).seek(_.id.asc)),
    "Option SeekOrder desc"   -> (_.toSeeker.seek(_.oprio.desc).seek(_.id.asc)),
    "seekDirection desc"      -> (_.toSeeker.seek(_.tag.asc.nullsFirst).seek(_.id.asc).seekDirection(SortDirection.Desc)),
    "seekDirection desc dflt" -> (_.toSeeker.seek(_.tag.asc).seek(_.id.asc).seekDirection(SortDirection.Desc)),
    "seekDirection asc"       -> (_.toSeeker.seek(_.score.desc.nullsLast).seek(_.id.desc).seekDirection(SortDirection.Asc)),
    // Row value comparisons: non-null columns with built-in filters, one direction
    "row: name, id asc"        -> (_.toSeeker.seek(_.name.asc).seek(_.id.asc)),
    "row: name, id desc"       -> (_.toSeeker.seek(_.name.desc).seek(_.id.desc)),
    "row: SeekOrder, name, id" -> (_.toSeeker.seek(_.prio.desc).seek(_.name.desc).seek(_.id.desc)),
    "row: seekDirection desc"  -> (_.toSeeker.seek(_.name.asc).seek(_.id.asc).seekDirection(SortDirection.Desc)),
    "chain: name asc, id desc" -> (_.toSeeker.seek(_.name.asc).seek(_.id.desc)),
    "chain: name asc, tag, id" -> (_.toSeeker.seek(_.name.asc).seek(_.tag.asc.nullsLast).seek(_.id.asc)),
    "chain: single column"     -> (_.toSeeker.seek(_.id.desc))
  )

  def adHoc(config: Query[Rows, Row, Seq] => SlickSeeker[Rows, Row, String, ?, ?], minId: Int) =
    config(rows.filter(_.id >= minId))

  def compiled(config: Query[Rows, Row, Seq] => SlickSeeker[Rows, Row, String, ?, ?]) =
    CompiledSeekPage[Int]((minId: ConstColumn[Int]) => config(rows.filter(_.id >= minId)))(_.id, maxLimit = 7)

  val idShape: Shape[FlatShapeLevel, Rep[Int], Int, Rep[Int]]          = implicitly
  val minIdShape: Shape[ColumnsShapeLevel, Int, Int, ConstColumn[Int]] = implicitly
  val stringEnv: CursorEnvironment[String]                             = implicitly

  def compiledChain(config: Query[Rows, Row, Seq] => SlickSeeker[Rows, Row, String, ?, ?]) =
    CompiledSeekPage[Int]((minId: ConstColumn[Int]) => config(rows.filter(_.id >= minId)))(_.id, maxLimit = 7)(
      minIdShape,
      idShape,
      ChainH2Profile
    )

  def adHocChain(config: Query[Rows, Row, Seq] => SlickSeeker[Rows, Row, String, ?, ?], minId: Int)(
      limit: Int,
      cursor: Option[String]
  ) =
    adHoc(config, minId).pageWithoutCountAs(limit, cursor, maxLimit = 7)(_.id)(idShape, ChainH2Profile, stringEnv, ec)

  "CompiledSeekPage" should {
    configs.foreach { case (name, config) =>
      s"match the ad-hoc seeker for every reachable cursor: $name" in {
        val page  = compiled(config)
        val chain = compiledChain(config)
        for {
          limit <- Seq(1, 2, 5, 0, -3, 500)
          minId <- Seq(0, 7, 99)
        } {
          // Every cursor reachable from the first page, following next and prev cursors.
          @tailrec def check(pending: List[Option[String]], seen: Set[Option[String]]): Unit = pending match {
            case Nil => ()
            case cursor :: rest =>
              val expected = await(db.run(adHoc(config, minId).pageWithoutCountAs(limit, cursor, maxLimit = 7)(_.id)))
              withClue(s"$name limit=$limit minId=$minId cursor=$cursor: ") {
                await(db.run(page.pageWithoutCount(minId, limit, cursor))) shouldBe expected
                await(db.run(adHocChain(config, minId)(limit, cursor))) shouldBe expected
                await(db.run(chain.pageWithoutCount(minId, limit, cursor))) shouldBe expected
              }
              val next = (expected.nextCursor.toList ++ expected.prevCursor.toList).map(Some(_)).filterNot(seen)
              check(next ++ rest, seen ++ next)
          }
          check(List(None), Set(None))

          // Following next cursors from the first page returns every row exactly once.
          @tailrec def forward(cursor: Option[String], ids: Vector[Int]): Vector[Int] = {
            val result = await(db.run(page.pageWithoutCount(minId, limit, cursor)))
            if (result.nextCursor.isEmpty) ids ++ result.items else forward(result.nextCursor, ids ++ result.items)
          }
          withClue(s"$name limit=$limit minId=$minId: ") {
            forward(None, Vector.empty).sorted shouldBe data.map(_.id).filter(_ >= minId)
          }
        }
        page.createdForms should be <= page.maxForms
      }
    }

    "count its query in page" in {
      val page   = compiled(configs.head._2)
      val result = await(db.run(page.page(params = 7, limit = 2, cursor = None)))
      result.total shouldBe 17
      result.items shouldBe await(db.run(adHoc(configs.head._2, 7).pageWithoutCountAs(2, None)(_.id))).items
    }

    "accept a maxLimit per call" in {
      val page = compiled(configs.head._2)
      await(db.run(page.pageWithoutCount(0, 50, None, maxLimit = 3))).items should have size 3
    }

    "bound its forms by the nullable cursor columns" in {
      compiled(configs(0)._2).maxForms shouldBe 5 // tag
      compiled(configs(6)._2).maxForms shouldBe 9 // tag, score
      compiled(configs(8)._2).maxForms shouldBe 3 // none nullable
    }

    "give the first page for cursors whose values don't fit the cursor columns" in {
      val env   = implicitly[CursorEnvironment[String]]
      val page  = compiled(configs.head._2)
      val first = await(db.run(page.pageWithoutCount(0, 3, None)))
      Seq(
        env.encode(Seq("Sx", "notAnInt"), CursorDirection.Forward),
        env.encode(Seq("Sx", "notAnInt"), CursorDirection.Backward),
        env.encode(Seq("St2"), CursorDirection.Forward),
        env.encode(Seq.empty, CursorDirection.Backward)
      ).foreach(cursor => await(db.run(page.pageWithoutCount(0, 3, Some(cursor)))) shouldBe first)
    }

    "be usable concurrently" in {
      val config = configs(6)._2
      val page   = compiled(config)
      val first  = await(db.run(adHoc(config, 0).pageWithoutCountAs(2, None)(_.id)))
      val pages  = await(Future.sequence((1 to 64).map(_ => db.run(page.pageWithoutCount(0, 2, first.nextCursor)))))
      pages.distinct shouldBe Seq(await(db.run(adHoc(config, 0).pageWithoutCountAs(2, first.nextCursor)(_.id))))
    }

    "fail on creation for a sort key without typedType" in {
      val base = MyH2Profile.api.seekerSortKeyIdentity[Int, String]
      val withoutType: SeekerSortKey[Int, Int, String] = new SeekerSortKey[Int, Int, String] {
        def mapCol(col: slick.lifted.ColumnOrdered[Int]) = base.mapCol(col)
        def shape                                        = base.shape
        def filter: ColumnSeekFilter[Int]                = base.filter
        def codec: CursorValueCodec[Int, String]         = base.codec
      }
      an[UnsupportedOperationException] should be thrownBy
        CompiledSeekPage[Unit]((_: Unit) => rows.toSeeker.seek(_.id.asc)(withoutType))(_.id)
    }

    "reject a seeker without seek columns" in {
      an[IllegalArgumentException] should be thrownBy
        CompiledSeekPage[Unit]((_: Unit) => rows.toSeeker)(_.id)
    }

    "bind tuple, Option and Unit parameters and select whole rows" in {
      val byTag = CompiledSeekPage[(Int, Option[String])]((p: (ConstColumn[Int], ConstColumn[Option[String]])) =>
        rows.filter(r => r.id >= p._1 && (p._2.isEmpty || !(r.tag =!= p._2))).toSeeker.seek(_.id.asc)
      )(r => r)
      val first  = await(db.run(byTag.pageWithoutCount((0, Some("t1")), 2, None)))
      val second = await(db.run(byTag.pageWithoutCount((0, Some("t1")), 2, first.nextCursor)))
      (first.items ++ second.items).map(_.tag).distinct shouldBe Seq(Some("t1"))

      val all = CompiledSeekPage[Unit]((_: Unit) => rows.toSeeker.seek(_.id.asc))(_.id)
      await(db.run(all.pageWithoutCount((), 2, None))).items shouldBe Seq(1, 2)
    }
  }
}
