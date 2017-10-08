package beam.integration

trait IntegrationSpecCommon {

  def isOrdered[A](s: Seq[A])(cf: (A, A) => Boolean): Boolean = {
    val z1 = s.drop(1)
    val z2 = s.dropRight(1)
    val zip = z2 zip z1

    zip.forall{case (a, b) => cf(a, b)}
  }
}
