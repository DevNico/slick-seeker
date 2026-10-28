package io.github.devnico.slickseeker.pagination

import io.github.devnico.slickseeker.SlickSeekerSupport
import slick.ast.{LiteralNode, OptionTypedType, Ordering, TypedType}
import slick.jdbc.JdbcProfile
import slick.lifted.{LiteralColumn, Rep}

/** What the profile a page runs with decides about its SQL (see the `seeker…` members of [[SlickSeekerSupport]]). */
final private[slickseeker] case class SeekSettings(
    profile: JdbcProfile,
    rowComparisons: Boolean,
    untypedStringParams: Boolean,
    bindCursorValues: Boolean,
    ascNulls: Ordering.NullOrdering,
    descNulls: Ordering.NullOrdering
) {

  /** The type cursor values of `tpe` are bound with. */
  def paramType(tpe: TypedType[Any]): TypedType[Any] =
    if (untypedStringParams) UntypedStringParam(profile)(tpe) else tpe

  /** The cursor value of an ad-hoc page as a parameter of `tpe`, not a literal: a literal can compare unequal to the
    * column's value (e.g. `0.1` to a REAL 0.1f on PostgreSQL). Written as a literal, as before parameters, if the
    * profile says so or the value has another type (from codecs that don't decode the column's type).
    */
  def bind[T](value: T, tpe: TypedType[T]): Rep[T] =
    if (!bindCursorValues || !SeekSettings.hasType(value, tpe)) LiteralColumn(value)(tpe)
    else Rep.forNode[T](LiteralNode(paramType(tpe.asInstanceOf[TypedType[Any]]), value, vol = true))(tpe)

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
        support.seekerBindCursorValues,
        support.seekerDefaultNulls(Ordering.Asc),
        support.seekerDefaultNulls(Ordering.Desc)
      )
    case other =>
      SeekSettings(
        other,
        SlickSeekerSupport.defaultRowComparisons(other),
        SlickSeekerSupport.defaultUntypedStringParams(other),
        SlickSeekerSupport.defaultBindCursorValues(other),
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

  /** `tpe` for messages, e.g. `Option[Int]`. */
  def describe(tpe: TypedType[?]): String = tpe match {
    case o: OptionTypedType[?] => s"Option[${describe(o.elementType)}]"
    case _                     => tpe.classTag.runtimeClass.getName
  }

  private def boxed(cls: Class[?]): Class[?] =
    if (cls.isPrimitive) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(cls, 1), 0).getClass else cls
}
