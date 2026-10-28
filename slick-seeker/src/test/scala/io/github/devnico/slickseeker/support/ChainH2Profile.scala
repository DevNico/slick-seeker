package io.github.devnico.slickseeker.support

/** [[MyH2Profile]] without row value comparisons: seekers always chain their conditions (`a > x OR (a = x AND …)`). */
object ChainH2Profile extends MyH2Profile {
  override def seekerRowComparisons: Boolean = false
}

/** [[MyH2Profile]] assuming PostgreSQL's nulls placement (last ascending), though H2 sorts nulls first ascending. */
object NullsHighH2Profile extends MyH2Profile {
  override def seekerDefaultNulls(direction: slick.ast.Ordering.Direction): slick.ast.Ordering.NullOrdering =
    if (direction == slick.ast.Ordering.Asc) slick.ast.Ordering.NullsLast else slick.ast.Ordering.NullsFirst
}
