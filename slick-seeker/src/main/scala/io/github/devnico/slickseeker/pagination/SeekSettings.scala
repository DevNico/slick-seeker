package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.SlickSeekerSupport
import slick.ast.{
  Bind,
  ElementSymbol,
  FieldSymbol,
  FilteredQuery,
  GroupBy,
  IfThenElse,
  Join,
  Library,
  LiteralNode,
  Node,
  OptionTypedType,
  Ordering,
  ProductNode,
  Pure,
  Select,
  StructNode,
  TableNode,
  TypeMapping,
  TypedType,
  Union
}
import slick.jdbc.{JdbcProfile, SQLServerProfile}
import slick.lifted.{LiteralColumn, Rep}
import slick.sql.SqlProfile
import slick.util.ConstArray

/** What the profile a page runs with decides about its SQL (see the `seeker…` members of [[SlickSeekerSupport]]). */
final private[slickseeker] case class SeekSettings(
    profile: JdbcProfile,
    rowComparisons: Boolean,
    untypedStringParams: Boolean,
    cursorValueCast: String => Option[String],
    ascNulls: Ordering.NullOrdering,
    descNulls: Ordering.NullOrdering
) {

  /** The type cursor values of `tpe` are bound with. */
  def paramType(tpe: TypedType[Any]): TypedType[Any] =
    if (untypedStringParams) UntypedStringParam(profile)(tpe) else tpe

  /** The cursor value of an ad-hoc page as a parameter of `tpe`, not a literal: a literal can compare unequal to the
    * column's value (e.g. `0.1` to a REAL 0.1f on PostgreSQL). Written as a literal, as before parameters, if the value
    * has another type (from codecs that don't decode the column's type).
    */
  def bind[T](value: T, tpe: TypedType[T]): Rep[T] =
    if (!SeekSettings.hasType(value, tpe)) LiteralColumn(value)(tpe)
    else Rep.forNode[T](LiteralNode(paramType(tpe.asInstanceOf[TypedType[Any]]), value, vol = true))(tpe)

  /** `value` (a cursor value of `tpe` for `column` of the query `base`) cast to the type [[cursorValueCast]] gives for
    * the column's declared type, so it compares like the column's values; unchanged without one.
    */
  def compareAs[T](column: Node, base: Node, value: Rep[T], tpe: TypedType[T]): Rep[T] =
    SeekSettings
      .declaredType(profile, column, base, tpe)
      .flatMap(cursorValueCast)
      .fold(value)(sqlType => Rep.forNode[T](Library.Cast.typed(tpe, value.toNode, LiteralNode(sqlType)))(tpe))

  /** Whether cursor columns are selected as expressions rather than as columns: SQL Server resolves `order by "id"`
    * against the selected columns, and fails if a seek column is selected twice (in the item and the cursor).
    */
  def cursorColumnsAsExpressions: Boolean = profile.isInstanceOf[SQLServerProfile]

  /** Where the database puts nulls without a nulls clause. */
  def defaultNulls(direction: Ordering.Direction): Ordering.NullOrdering =
    if (direction == Ordering.Asc) ascNulls else descNulls
}

private[slickseeker] object SeekSettings {
  def of(profile: JdbcProfile): SeekSettings = profile match {
    case support: SlickSeekerSupport =>
      SeekSettings(
        profile,
        support.seekerRowComparisons,
        support.seekerUntypedStringParams,
        support.seekerCursorValueCast,
        support.seekerDefaultNulls(Ordering.Asc),
        support.seekerDefaultNulls(Ordering.Desc)
      )
    case other =>
      SeekSettings(
        other,
        SlickSeekerSupport.defaultRowComparisons(other),
        SlickSeekerSupport.defaultUntypedStringParams(other),
        SlickSeekerSupport.defaultCursorValueCast(other, _),
        SlickSeekerSupport.defaultNulls(other, Ordering.Asc),
        SlickSeekerSupport.defaultNulls(other, Ordering.Desc)
      )
  }

  /** Whether `value` is a value of `tpe` at runtime (cursor value codecs are not checked to decode their type). */
  def hasType(value: Any, tpe: TypedType[?]): Boolean = (value, tpe) match {
    case (None, _: OptionTypedType[?])          => true
    case (Some(v), o: OptionTypedType[?])       => hasType(v, o.elementType)
    case (_, _: OptionTypedType[?]) | (null, _) => false
    case _                                      => boxed(tpe.classTag.runtimeClass).isInstance(value)
  }

  /** The declared SQL type of `column` (a column or expression of `tpe` over the elements of the query `base`): that of
    * its column (`O.SqlType`, else the type Slick's DDL gives it), or that of the columns of the same type in an
    * expression if they all have the same one. None if that is not known (e.g. a column of a query this can't follow,
    * or an expression over columns of different declared types).
    */
  def declaredType(profile: JdbcProfile, column: Node, base: Node, tpe: TypedType[?]): Option[String] = {
    val sqlType = profile.jdbcTypeFor(tpe).sqlType
    def declared(f: FieldSymbol): Option[String] =
      scala.util
        .Try(
          f.findColumnOption[SqlProfile.ColumnOption.SqlType]
            .fold(profile.jdbcTypeFor(f.tpe).sqlTypeName(Some(f)))(_.typeName)
        )
        .toOption
    def sameType(f: FieldSymbol): Boolean =
      scala.util.Try(profile.jdbcTypeFor(f.tpe).sqlType == sqlType).getOrElse(false)
    // The declared types of the columns of `tpe` in `n`, None for one that is not known; element paths in `n` select
    // from the elements of `scope`
    def types(n: Node, scope: Node, top: Boolean): List[Option[String]] = n match {
      case Select(_, f: FieldSymbol) => if (top || sameType(f)) List(declared(f)) else Nil
      case path @ Select(_, _: ElementSymbol) =>
        element(scope, elementPath(path)).fold(List(Option.empty[String])) { case (e, s) => types(e, s, top) }
      case query if isQuery(query) => List(None)
      case other                   => other.children.toSeq.toList.flatMap(types(_, scope, top = false))
    }
    types(column, base, top = true).distinct match {
      case List(known) => known
      case _           => None
    }
  }

  private def isQuery(n: Node): Boolean = n match {
    case _: Bind | _: FilteredQuery | _: Join | _: TableNode | _: Union | _: GroupBy => true
    case _                                                                           => false
  }

  // The element symbols of a path into the query's elements, e.g. `_._2` of a query of tuples: List(ElementSymbol(2)).
  private def elementPath(n: Node): List[ElementSymbol] = n match {
    case Select(in, e: ElementSymbol) => elementPath(in) :+ e
    case _                            => Nil
  }

  // The expression a query's element path selects, with the query its own element paths select from, if the query
  // maps its elements (`query.map(t => (t.a, t.b))`), possibly under filters, sorts, joins and further maps.
  private def element(query: Node, path: List[ElementSymbol]): Option[(Node, Node)] = query match {
    case Bind(_, from, Pure(value, _)) => select(value, path, from)
    case filtered: FilteredQuery       => element(filtered.from, path)
    case join: Join =>
      path match {
        case e :: rest if e.idx == 1 => element(join.left, rest)
        case e :: rest if e.idx == 2 => element(join.right, rest)
        case _                       => None
      }
    case _ => None
  }

  // The node `path` selects in `value`, an element of a query over the elements of `scope`.
  private def select(value: Node, path: List[ElementSymbol], scope: Node): Option[(Node, Node)] = (value, path) match {
    case (_, Nil)                                 => Some((value, scope))
    case (inner @ Select(_, _: ElementSymbol), _) => element(scope, elementPath(inner) ++ path)
    case (n, e :: rest)                           => child(n, e.idx).flatMap(select(_, rest, scope))
  }

  private def child(n: Node, idx: Int): Option[Node] = n match {
    case p: ProductNode => p.children.toSeq.lift(idx - 1)
    case s: StructNode  => s.elements.toSeq.lift(idx - 1).map(_._2)
    case t: TypeMapping => child(t.child, idx)
    case _              => None
  }

  /** `column` as an expression that is not a column reference, with the same value and type. */
  def asExpression(column: Node): Node = column match {
    case select: Select =>
      val always = Library.==.typed[Boolean](LiteralNode(1), LiteralNode(1))
      IfThenElse(ConstArray(always, select, select))
    case other => other
  }

  /** `tpe` for messages, e.g. `Option[Int]`. */
  def describe(tpe: TypedType[?]): String = tpe match {
    case o: OptionTypedType[?] => s"Option[${describe(o.elementType)}]"
    case _                     => tpe.classTag.runtimeClass.getName
  }

  private def boxed(cls: Class[?]): Class[?] =
    if (cls.isPrimitive) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(cls, 1), 0).getClass else cls
}
