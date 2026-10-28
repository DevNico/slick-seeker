package io.github.devnico.slickseeker.support

/** [[MyH2Profile]] without row value comparisons: seekers always chain their conditions (`a > x OR (a = x AND …)`). */
object ChainH2Profile extends MyH2Profile {
  override def seekerRowComparisons: Boolean = false
}

/** [[MyH2Profile]] assuming nulls sort high (as on PostgreSQL), though H2 sorts them low. */
object NullsHighH2Profile extends MyH2Profile {
  override def seekerNullsSortHigh: Boolean = true
}
