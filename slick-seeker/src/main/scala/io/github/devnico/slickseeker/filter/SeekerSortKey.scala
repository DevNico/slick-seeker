package io.github.devnico.slickseeker.filter

import io.github.devnico.slickseeker.cursor.CursorValueCodec
import slick.ast.{BaseTypedType, TypedType}
import slick.lifted.{ColumnOrdered, FlatShapeLevel, Rep, Shape}

trait SeekerSortKey[T, K, CVE] {
  def mapCol(col: ColumnOrdered[T]): ColumnOrdered[K]
  def shape: Shape[FlatShapeLevel, Rep[K], K, Rep[K]]
  def filter: ColumnSeekFilter[K]
  def codec: CursorValueCodec[K, CVE]

  /** The type of the sort key, to bind cursor values as query parameters ([[pagination.CompiledSeekPage]]). Optional:
    * without it, compiled pages run their cursor pages as ad-hoc pages.
    */
  def typedType: TypedType[K] =
    throw new UnsupportedOperationException(s"${getClass.getName} does not support compiled pages (no typedType)")
}

// Note: SeekerSortKey implicits are provided by SlickSeekerSupport trait
// where the profile API is available. This allows the library to work
// with any JDBC profile without hardcoding profile imports.
object SeekerSortKey
