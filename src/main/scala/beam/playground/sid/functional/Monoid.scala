package beam.playground.sid.functional

/**
  * Created by sfeygin on 4/10/17.
  */
trait Monoid[T] {
  def zero: T

  def op(t1: T, t2: T): T
}

object Monoid {

  def apply[T](implicit monoid: Monoid[T]): Monoid[T] = monoid

  implicit val IntAdditionMonoid = new Monoid[Int] {
    val zero = 0

    def op(i: Int, j: Int): Int = i + j
  }

  implicit val BigDecimalAdditionMonoid = new Monoid[BigDecimal] {
    val zero = BigDecimal(0)

    def op(i: BigDecimal, j: BigDecimal): BigDecimal = i + j
  }

  implicit def MapMonoid[K, V: Monoid] = new Monoid[Map[K, V]] {
    def zero = Map.empty[K, V]

    def op(m1: Map[K, V], m2: Map[K, V]): Map[K, V] = m2.foldLeft(m1) { (a, e) =>
      val (key, value) = e
      a.get(key)
        .map(v => a + ((key, implicitly[Monoid[V]].op(v, value))))
        .getOrElse(a + ((key, value)))
    }
  }
}
