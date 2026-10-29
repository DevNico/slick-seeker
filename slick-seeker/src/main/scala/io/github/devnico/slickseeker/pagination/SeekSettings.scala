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
  OptionApply,
  OptionTypedType,
  Ordering,
  ProductNode,
  Pure,
  Ref,
  Select,
  StructNode,
  TableExpansion,
  TableNode,
  TypeMapping,
  TypedType,
  Union
}
import slick.jdbc.{JdbcProfile, MySQLProfile, SQLServerProfile}
import slick.lifted.{LiteralColumn, Rep, SimpleExpression}
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
    * has another type (from codecs that don't decode the column's type). A `null` value (from a codec decoding
    * `Some(null)`) throws `IllegalArgumentException`: as a literal or parameter, it would match no row.
    */
  def bind[T](value: T, tpe: TypedType[T]): Rep[T] =
    if (value == null)
      throw new IllegalArgumentException(
        s"The cursor value codec of a column of type ${SeekSettings.describe(tpe)} decoded null"
      )
    else if (!SeekSettings.hasType(value, tpe)) LiteralColumn(value)(tpe)
    else Rep.forNode[T](LiteralNode(paramType(tpe.asInstanceOf[TypedType[Any]]), value, vol = true))(tpe)

  /** `value` (a cursor value of `tpe` for `column` of the query `base`) cast to the type [[cursorValueCast]] gives for
    * the column's declared type, so it compares like the column's values; unchanged without one.
    */
  def compareAs[T](column: Node, base: Node, value: Rep[T], tpe: TypedType[T]): Rep[T] =
    SeekSettings
      .declaredType(profile, column, base, tpe)
      .flatMap(cursorValueCast)
      .fold(value)(sqlType => SeekSettings.cast(profile, value, sqlType, tpe))

  /** Whether cursor columns, and columns the items select more than once, are selected as expressions rather than as
    * columns: SQL Server resolves `order by "id"` against the selected columns, and fails if a column is selected twice
    * (in the item and the cursor, or twice in the item).
    */
  def cursorColumnsAsExpressions: Boolean = profile.isInstanceOf[SQLServerProfile]

  /** Whether pages select cursor columns (or items) other than as given (see [[cursorColumn]]). */
  def rewritesSelection: Boolean = cursorColumnsAsExpressions || profile.isInstanceOf[MySQLProfile]

  /** The cursor column `column` (over the elements of the query `scope`) as pages select it: as an expression on SQL
    * Server (see [[cursorColumnsAsExpressions]]); on MySQL, cast to `DOUBLE` if its values are cast to `FLOAT` (see
    * [[SlickSeekerSupport.seekerCursorValueCast]]), since MySQL and MariaDB send `FLOAT` values as text with 6
    * significant digits, which the cursor value would keep; as given elsewhere.
    */
  def cursorColumn(column: Rep[?], scope: Node): Rep[?] =
    if (cursorColumnsAsExpressions) Rep.forNodeUntyped[Any](SeekSettings.asExpression(column.toNode))
    else
      column match {
        case typed: Rep.TypedRep[?] if profile.isInstanceOf[MySQLProfile] =>
          val rep = typed.asInstanceOf[Rep.TypedRep[Any]]
          val single = SeekSettings
            .declaredType(profile, rep.toNode, scope, rep.tpe)
            .flatMap(cursorValueCast)
            .exists(_.trim.equalsIgnoreCase("FLOAT"))
          if (single) SeekSettings.cast(profile, rep, "DOUBLE", rep.tpe) else column
        case _ => column
      }

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

  /** `value` (what a query over the elements of `from` selects) with the columns it selects under a name it selects
    * more than once (e.g. `(t.id, t.id)`, or `t.id` beside the whole row `t`) as expressions, so that no two selected
    * columns have the same name. Whole rows are counted, but stay as they are.
    */
  def withoutRepeatedColumns(value: Node, from: Node): Node = {
    val counts   = columnNames(value, from).groupBy(identity).map { case (name, all) => (name, all.size) }
    val repeated = counts.filter(_._2 > 1).keySet
    def distinct(n: Node): Node = n match {
      case select: Select =>
        if (column(select, from).exists(repeated)) asExpression(select) else select
      case _: ProductNode | _: StructNode | _: TypeMapping | _: OptionApply => n.mapChildren(distinct)
      case other                                                            => other
    }
    if (repeated.isEmpty) value else distinct(value)
  }

  // The name of the column `n` (a path into the elements of `scope`) selects, if it selects one column.
  private def column(n: Node, scope: Node): Option[String] = n match {
    case Select(_, f: FieldSymbol) => Some(f.name)
    case path @ Select(_, _: ElementSymbol) =>
      element(scope, elementPath(path)).flatMap { case (e, s) => column(e, s) }
    case OptionApply(child) => column(child, scope)
    case _                  => None
  }

  // The names of the columns `n` (part of what a query over the elements of `scope` selects) selects as columns.
  private def columnNames(n: Node, scope: Node): List[String] = n match {
    case _: Select | _: OptionApply if column(n, scope).isDefined => column(n, scope).toList
    case path @ Select(_, _: ElementSymbol) =>
      element(scope, elementPath(path)).fold(List.empty[String]) { case (e, s) => columnNames(e, s) }
    case _: Ref => rowColumnNames(scope)
    case _: ProductNode | _: StructNode | _: TypeMapping | _: OptionApply =>
      n.children.toSeq.toList.flatMap(columnNames(_, scope))
    case _ => Nil
  }

  // The names of the columns of a whole element of the query `scope`.
  private def rowColumnNames(scope: Node): List[String] = scope match {
    case TableExpansion(_, _, columns) => columnNames(columns, scope)
    case filtered: FilteredQuery       => rowColumnNames(filtered.from)
    case Bind(_, from, Pure(value, _)) => columnNames(value, from)
    case join: Join                    => rowColumnNames(join.left) ++ rowColumnNames(join.right)
    case _                             => Nil
  }

  /** `CAST(value AS sqlType)`. Written out on MySQL, where Slick writes casts as JDBC escapes (`{fn convert(…)}`),
    * which its drivers turn into other expressions or leave out for most types (e.g. `FLOAT`).
    */
  def cast[T](profile: JdbcProfile, value: Rep[T], sqlType: String, tpe: TypedType[T]): Rep[T] = profile match {
    case _: MySQLProfile =>
      SimpleExpression.unary[T, T] { (v, qb) =>
        qb.sqlBuilder += "cast("
        qb.expr(v)
        qb.sqlBuilder += s" as $sqlType)"
      }(tpe)(value)
    case _ => Rep.forNode[T](Library.Cast.typed(tpe, value.toNode, LiteralNode(sqlType)))(tpe)
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
