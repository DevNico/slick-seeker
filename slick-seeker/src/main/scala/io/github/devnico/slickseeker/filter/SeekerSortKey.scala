package io.github.devnico.slickseeker.filter

import io.github.devnico.slickseeker.cursor.CursorValueCodec
import slick.ast.BaseTypedType
import slick.lifted.{ColumnOrdered, FlatShapeLevel, Rep, Shape}

trait SeekerSortKey[T, CVE] {
  type Key
  def mapCol(col: ColumnOrdered[T]): ColumnOrdered[Key]
  def shape: Shape[FlatShapeLevel, Rep[Key], Key, Rep[Key]]
  def filter: ColumnSeekFilter[Key]
  def codec: CursorValueCodec[Key, CVE]
}

// Note: SeekerSortKey implicits are provided by SlickSeekerSupport trait
// where the profile API is available. This allows the library to work
// with any JDBC profile without hardcoding profile imports.
object SeekerSortKey
