package io.github.devnico.slickseeker.filter

import io.github.devnico.slickseeker.cursor.CursorValueCodec
import slick.ast.BaseTypedType
import slick.lifted.{ColumnOrdered, FlatShapeLevel, Rep, Shape}

trait SeekerSortKey[T, K, CVE] {
  def mapCol(col: ColumnOrdered[T]): ColumnOrdered[K]
  def shape: Shape[FlatShapeLevel, Rep[K], K, Rep[K]]
  def filter: ColumnSeekFilter[K]
  def codec: CursorValueCodec[K, CVE]
}

// Note: SeekerSortKey implicits are provided by SlickSeekerSupport trait
// where the profile API is available. This allows the library to work
// with any JDBC profile without hardcoding profile imports.
object SeekerSortKey
